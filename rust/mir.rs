use crate::{
    CheckedProgram, ErrorCode, HirArgument, HirConstructor, HirExpr, HirKind, HirLiteral,
    HirParameter, HirPattern, HirPatternConstructor, HirPatternKind, HirProgram, HirSymbol,
    HirSymbolKind, ParsedProgram, SourceSpan, SymbolId, Type, Value, YinError, check_hir_program,
};
use indexmap::IndexMap;
use num_bigint::BigInt;
use num_traits::{ToPrimitive, Zero};
use std::cell::RefCell;
use std::rc::Rc;

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub struct FunctionId(pub u32);

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub struct BlockId(pub u32);

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub struct LocalId(pub u32);

#[derive(Clone, Debug, PartialEq)]
pub struct MirProgram {
    pub symbols: Vec<HirSymbol>,
    pub records: IndexMap<SymbolId, MirRecordDefinition>,
    pub functions: Vec<MirFunction>,
    pub entry: FunctionId,
    pub result_type: Type,
}

#[derive(Clone, Debug, PartialEq)]
pub struct MirRecordDefinition {
    pub symbol: SymbolId,
    pub name: String,
    pub fields: Vec<String>,
    pub parents: Vec<SymbolId>,
    pub variant: Option<String>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct MirFunction {
    pub id: FunctionId,
    pub parameters: Vec<MirParameter>,
    pub blocks: Vec<MirBasicBlock>,
    pub return_type: Type,
}

#[derive(Clone, Debug, PartialEq)]
pub struct MirParameter {
    pub symbol: SymbolId,
    pub name: String,
    pub ty: Type,
    pub required: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub struct MirBasicBlock {
    pub id: BlockId,
    pub parameters: Vec<MirBlockParameter>,
    pub instructions: Vec<MirInstruction>,
    pub terminator: MirTerminator,
}

#[derive(Clone, Debug, PartialEq)]
pub struct MirBlockParameter {
    pub local: LocalId,
    pub ty: Type,
}

#[derive(Clone, Debug, PartialEq)]
pub struct MirInstruction {
    pub output: LocalId,
    pub kind: MirInstructionKind,
    pub ty: Type,
    pub span: SourceSpan,
}

#[derive(Clone, Debug, PartialEq)]
pub enum MirInstructionKind {
    Constant(HirLiteral),
    Void,
    ReadSymbol(SymbolId),
    BindSymbol {
        symbol: SymbolId,
        value: LocalId,
    },
    Vector(Vec<LocalId>),
    Closure(FunctionId),
    Call {
        callee: LocalId,
        arguments: Vec<MirArgument>,
    },
    Construct {
        constructor: HirConstructor,
        arguments: Vec<MirArgument>,
    },
    Field {
        value: LocalId,
        name: String,
    },
}

#[derive(Clone, Debug, PartialEq)]
pub struct MirArgument {
    pub keyword: Option<String>,
    pub value: LocalId,
}

#[derive(Clone, Debug, PartialEq)]
pub struct MirMatchTarget {
    pub pattern: HirPattern,
    pub block: BlockId,
}

#[derive(Clone, Debug, PartialEq)]
pub enum MirTerminator {
    Return(LocalId),
    Jump {
        target: BlockId,
        arguments: Vec<LocalId>,
    },
    Branch {
        condition: LocalId,
        then_block: BlockId,
        else_block: BlockId,
    },
    Match {
        value: LocalId,
        targets: Vec<MirMatchTarget>,
    },
    Unreachable,
}

pub fn check_mir_program(program: &ParsedProgram) -> Result<MirProgram, YinError> {
    let checked = check_hir_program(program)?;
    lower_mir_program(&checked)
}

pub fn lower_mir_program(checked: &CheckedProgram) -> Result<MirProgram, YinError> {
    Lowerer::new(&checked.hir).lower()
}

struct Lowerer<'a> {
    hir: &'a HirProgram,
    functions: Vec<Option<MirFunction>>,
    records: IndexMap<SymbolId, MirRecordDefinition>,
}

impl<'a> Lowerer<'a> {
    fn new(hir: &'a HirProgram) -> Self {
        Self {
            hir,
            functions: Vec::new(),
            records: IndexMap::new(),
        }
    }

    fn lower(mut self) -> Result<MirProgram, YinError> {
        let entry = self.reserve_function();
        let mut builder = FunctionBuilder::new(entry, Vec::new(), self.hir.result_type.clone());
        let result = self.sequence(&mut builder, &self.hir.expressions)?;
        builder.terminate(MirTerminator::Return(result));
        self.finish_function(entry, builder);
        Ok(MirProgram {
            symbols: self.hir.symbols.clone(),
            records: self.records,
            functions: self
                .functions
                .into_iter()
                .map(|function| function.expect("reserved MIR function was not lowered"))
                .collect(),
            entry,
            result_type: self.hir.result_type.clone(),
        })
    }

    fn sequence(
        &mut self,
        builder: &mut FunctionBuilder,
        expressions: &[HirExpr],
    ) -> Result<LocalId, YinError> {
        let mut result = None;
        for expression in expressions {
            result = Some(self.expression(builder, expression)?);
        }
        Ok(match result {
            Some(result) => result,
            None => builder.emit(MirInstructionKind::Void, Type::Void, synthetic_span()),
        })
    }

    fn expression(
        &mut self,
        builder: &mut FunctionBuilder,
        expression: &HirExpr,
    ) -> Result<LocalId, YinError> {
        let span = expression.span.clone();
        let ty = expression.ty.clone();
        let kind = match &expression.kind {
            HirKind::Literal(value) => MirInstructionKind::Constant(value.clone()),
            HirKind::Reference(symbol) => {
                self.validate_builtin(*symbol, expression)?;
                MirInstructionKind::ReadSymbol(*symbol)
            }
            HirKind::Vector(values) => {
                let values = values
                    .iter()
                    .map(|value| self.expression(builder, value))
                    .collect::<Result<Vec<_>, _>>()?;
                MirInstructionKind::Vector(values)
            }
            HirKind::Define { symbol, value } => {
                let value = self.expression(builder, value)?;
                MirInstructionKind::BindSymbol {
                    symbol: *symbol,
                    value,
                }
            }
            HirKind::Function { parameters, body } => {
                let function = self.function(parameters, body, &ty)?;
                MirInstructionKind::Closure(function)
            }
            HirKind::Record {
                symbol,
                parents,
                fields,
            } => {
                self.records.insert(
                    *symbol,
                    MirRecordDefinition {
                        symbol: *symbol,
                        name: self.symbol_name(*symbol).to_owned(),
                        fields: fields.iter().map(|field| field.name.clone()).collect(),
                        parents: parents.clone(),
                        variant: None,
                    },
                );
                return Ok(builder.emit(MirInstructionKind::Void, Type::Void, span));
            }
            HirKind::Variant { symbol, cases } => {
                let variant = self.symbol_name(*symbol).to_owned();
                for case in cases {
                    self.records.insert(
                        case.symbol,
                        MirRecordDefinition {
                            symbol: case.symbol,
                            name: case.name.clone(),
                            fields: case.fields.iter().map(|field| field.name.clone()).collect(),
                            parents: Vec::new(),
                            variant: Some(variant.clone()),
                        },
                    );
                }
                return Ok(builder.emit(MirInstructionKind::Void, Type::Void, span));
            }
            HirKind::Constructor {
                constructor,
                arguments,
            } => {
                let arguments = self.arguments(builder, arguments)?;
                MirInstructionKind::Construct {
                    constructor: constructor.clone(),
                    arguments,
                }
            }
            HirKind::Call { callee, arguments } => {
                let callee = self.expression(builder, callee)?;
                let arguments = self.arguments(builder, arguments)?;
                MirInstructionKind::Call { callee, arguments }
            }
            HirKind::If {
                condition,
                then_branch,
                else_branch,
            } => {
                return self.if_expression(
                    builder,
                    expression,
                    condition,
                    then_branch,
                    else_branch,
                );
            }
            HirKind::Sequence(values) => return self.sequence(builder, values),
            HirKind::Match { target, arms } => {
                return self.match_expression(builder, expression, target, arms);
            }
            HirKind::FieldPath { root, fields } => {
                let mut value = builder.emit(
                    MirInstructionKind::ReadSymbol(*root),
                    Type::Any,
                    span.clone(),
                );
                for (index, field) in fields.iter().enumerate() {
                    let field_type = if index + 1 == fields.len() {
                        ty.clone()
                    } else {
                        Type::Any
                    };
                    value = builder.emit(
                        MirInstructionKind::Field {
                            value,
                            name: field.clone(),
                        },
                        field_type,
                        span.clone(),
                    );
                }
                return Ok(value);
            }
            HirKind::Field { target, name } => {
                let value = self.expression(builder, target)?;
                MirInstructionKind::Field {
                    value,
                    name: name.clone(),
                }
            }
            HirKind::DecodeJson { .. } | HirKind::EncodeJson(_) => {
                return Err(mir_error(
                    expression,
                    "JSON boundaries are outside MIR phase 1",
                ));
            }
        };
        Ok(builder.emit(kind, ty, span))
    }

    fn function(
        &mut self,
        parameters: &[HirParameter],
        body: &[HirExpr],
        ty: &Type,
    ) -> Result<FunctionId, YinError> {
        let Type::Function(_, _, result) = ty else {
            return Err(YinError::language("MIR function has no function type"));
        };
        let id = self.reserve_function();
        let parameters = parameters
            .iter()
            .map(|parameter| MirParameter {
                symbol: parameter.symbol,
                name: self.symbol_name(parameter.symbol).to_owned(),
                ty: parameter.ty.clone(),
                required: parameter.required,
            })
            .collect::<Vec<_>>();
        let mut builder = FunctionBuilder::new(id, parameters, (**result).clone());
        let value = self.sequence(&mut builder, body)?;
        builder.terminate(MirTerminator::Return(value));
        self.finish_function(id, builder);
        Ok(id)
    }

    fn arguments(
        &mut self,
        builder: &mut FunctionBuilder,
        arguments: &[HirArgument],
    ) -> Result<Vec<MirArgument>, YinError> {
        arguments
            .iter()
            .map(|argument| {
                Ok(MirArgument {
                    keyword: argument.keyword.clone(),
                    value: self.expression(builder, &argument.value)?,
                })
            })
            .collect()
    }

    fn if_expression(
        &mut self,
        builder: &mut FunctionBuilder,
        expression: &HirExpr,
        condition: &HirExpr,
        then_branch: &HirExpr,
        else_branch: &HirExpr,
    ) -> Result<LocalId, YinError> {
        let condition = self.expression(builder, condition)?;
        let then_block = builder.new_block(Vec::new());
        let else_block = builder.new_block(Vec::new());
        let (join_block, parameters) = builder.new_block(vec![expression.ty.clone()]);
        let result = parameters[0];
        builder.terminate(MirTerminator::Branch {
            condition,
            then_block: then_block.0,
            else_block: else_block.0,
        });

        builder.switch_to(then_block.0);
        let then_value = self.expression(builder, then_branch)?;
        builder.terminate(MirTerminator::Jump {
            target: join_block,
            arguments: vec![then_value],
        });

        builder.switch_to(else_block.0);
        let else_value = self.expression(builder, else_branch)?;
        builder.terminate(MirTerminator::Jump {
            target: join_block,
            arguments: vec![else_value],
        });
        builder.switch_to(join_block);
        Ok(result)
    }

    fn match_expression(
        &mut self,
        builder: &mut FunctionBuilder,
        expression: &HirExpr,
        target: &HirExpr,
        arms: &[crate::HirMatchArm],
    ) -> Result<LocalId, YinError> {
        let value = self.expression(builder, target)?;
        let (join_block, parameters) = builder.new_block(vec![expression.ty.clone()]);
        let result = parameters[0];
        let mut targets = Vec::new();
        let mut arm_blocks = Vec::new();
        for arm in arms {
            let block = builder.new_block(Vec::new()).0;
            targets.push(MirMatchTarget {
                pattern: arm.pattern.clone(),
                block,
            });
            arm_blocks.push((block, arm));
        }
        builder.terminate(MirTerminator::Match { value, targets });
        for (block, arm) in arm_blocks {
            builder.switch_to(block);
            let value = self.expression(builder, &arm.body)?;
            builder.terminate(MirTerminator::Jump {
                target: join_block,
                arguments: vec![value],
            });
        }
        builder.switch_to(join_block);
        Ok(result)
    }

    fn validate_builtin(&self, symbol: SymbolId, expression: &HirExpr) -> Result<(), YinError> {
        let Some(definition) = self.hir.symbols.get(symbol.0 as usize) else {
            return Ok(());
        };
        if definition.kind != HirSymbolKind::Builtin {
            return Ok(());
        }
        if matches!(
            definition.name.as_str(),
            "+" | "-"
                | "*"
                | "/"
                | "<"
                | "<="
                | ">"
                | ">="
                | "="
                | "and"
                | "or"
                | "not"
                | "length"
                | "at"
                | "append"
        ) {
            Ok(())
        } else {
            Err(mir_error(
                expression,
                format!("builtin {} is outside MIR phase 1", definition.name),
            ))
        }
    }

    fn reserve_function(&mut self) -> FunctionId {
        let id = FunctionId(self.functions.len() as u32);
        self.functions.push(None);
        id
    }

    fn finish_function(&mut self, id: FunctionId, builder: FunctionBuilder) {
        self.functions[id.0 as usize] = Some(builder.finish());
    }

    fn symbol_name(&self, symbol: SymbolId) -> &str {
        self.hir
            .symbols
            .get(symbol.0 as usize)
            .map(|symbol| symbol.name.as_str())
            .unwrap_or("<unknown>")
    }
}

struct FunctionBuilder {
    id: FunctionId,
    parameters: Vec<MirParameter>,
    blocks: Vec<MirBasicBlock>,
    current: BlockId,
    next_local: u32,
    return_type: Type,
}

impl FunctionBuilder {
    fn new(id: FunctionId, parameters: Vec<MirParameter>, return_type: Type) -> Self {
        Self {
            id,
            parameters,
            blocks: vec![MirBasicBlock {
                id: BlockId(0),
                parameters: Vec::new(),
                instructions: Vec::new(),
                terminator: MirTerminator::Unreachable,
            }],
            current: BlockId(0),
            next_local: 0,
            return_type,
        }
    }

    fn emit(&mut self, kind: MirInstructionKind, ty: Type, span: SourceSpan) -> LocalId {
        let output = self.local();
        self.block_mut().instructions.push(MirInstruction {
            output,
            kind,
            ty,
            span,
        });
        output
    }

    fn new_block(&mut self, types: Vec<Type>) -> (BlockId, Vec<LocalId>) {
        let id = BlockId(self.blocks.len() as u32);
        let mut locals = Vec::new();
        let parameters = types
            .into_iter()
            .map(|ty| {
                let local = self.local();
                locals.push(local);
                MirBlockParameter { local, ty }
            })
            .collect();
        self.blocks.push(MirBasicBlock {
            id,
            parameters,
            instructions: Vec::new(),
            terminator: MirTerminator::Unreachable,
        });
        (id, locals)
    }

    fn terminate(&mut self, terminator: MirTerminator) {
        self.block_mut().terminator = terminator;
    }

    fn switch_to(&mut self, block: BlockId) {
        self.current = block;
    }

    fn block_mut(&mut self) -> &mut MirBasicBlock {
        &mut self.blocks[self.current.0 as usize]
    }

    fn local(&mut self) -> LocalId {
        let local = LocalId(self.next_local);
        self.next_local += 1;
        local
    }

    fn finish(self) -> MirFunction {
        MirFunction {
            id: self.id,
            parameters: self.parameters,
            blocks: self.blocks,
            return_type: self.return_type,
        }
    }
}

#[derive(Clone)]
enum RuntimeValue {
    Data(Box<Value>),
    Closure(Rc<Closure>),
    Builtin(String),
}

#[derive(Clone)]
struct Closure {
    function: FunctionId,
    environment: Environment,
}

#[derive(Clone, Default)]
struct Environment(Rc<RefCell<Frame>>);

#[derive(Clone, Default)]
struct Frame {
    values: IndexMap<SymbolId, RuntimeValue>,
    parent: Option<Environment>,
}

impl Environment {
    fn child(&self) -> Self {
        Self(Rc::new(RefCell::new(Frame {
            values: IndexMap::new(),
            parent: Some(self.clone()),
        })))
    }

    fn define(&self, symbol: SymbolId, value: RuntimeValue) {
        self.0.borrow_mut().values.insert(symbol, value);
    }

    fn get(&self, symbol: SymbolId) -> Option<RuntimeValue> {
        let frame = self.0.borrow();
        frame
            .values
            .get(&symbol)
            .cloned()
            .or_else(|| frame.parent.as_ref().and_then(|parent| parent.get(symbol)))
    }
}

pub fn evaluate_mir(program: &MirProgram) -> Result<Value, YinError> {
    let mut evaluator = Evaluator { program };
    let value = evaluator.call_function(program.entry, Environment::default(), Vec::new())?;
    match value {
        RuntimeValue::Data(value) => Ok(*value),
        RuntimeValue::Closure(_) => Err(YinError::language("MIR program returned a function")),
        RuntimeValue::Builtin(_) => Err(YinError::language("MIR program returned a builtin")),
    }
}

struct Evaluator<'a> {
    program: &'a MirProgram,
}

impl Evaluator<'_> {
    fn call_function(
        &mut self,
        function: FunctionId,
        environment: Environment,
        arguments: Vec<MirArgumentValue>,
    ) -> Result<RuntimeValue, YinError> {
        let function = self
            .program
            .functions
            .get(function.0 as usize)
            .ok_or_else(|| YinError::language("unknown MIR function"))?;
        let call_environment = environment.child();
        bind_arguments(function, arguments, &call_environment)?;
        self.run_blocks(function, call_environment)
    }

    fn run_blocks(
        &mut self,
        function: &MirFunction,
        mut environment: Environment,
    ) -> Result<RuntimeValue, YinError> {
        let mut block = BlockId(0);
        let mut incoming = Vec::new();
        let mut locals = IndexMap::<LocalId, RuntimeValue>::new();
        loop {
            let current = function
                .blocks
                .get(block.0 as usize)
                .ok_or_else(|| YinError::language("unknown MIR block"))?;
            if incoming.len() != current.parameters.len() {
                return Err(YinError::language("MIR block argument arity mismatch"));
            }
            for (parameter, value) in current.parameters.iter().zip(incoming.drain(..)) {
                locals.insert(parameter.local, value);
            }
            for instruction in &current.instructions {
                let value = self.instruction(instruction, &locals, &environment)?;
                locals.insert(instruction.output, value);
            }
            match &current.terminator {
                MirTerminator::Return(value) => return local(&locals, *value),
                MirTerminator::Jump { target, arguments } => {
                    incoming = arguments
                        .iter()
                        .map(|argument| local(&locals, *argument))
                        .collect::<Result<Vec<_>, _>>()?;
                    block = *target;
                }
                MirTerminator::Branch {
                    condition,
                    then_block,
                    else_block,
                } => {
                    block = if bool_data(local(&locals, *condition)?)? {
                        *then_block
                    } else {
                        *else_block
                    };
                    incoming.clear();
                }
                MirTerminator::Match { value, targets } => {
                    let value = data(local(&locals, *value)?)?;
                    let mut selected = None;
                    for target in targets {
                        let child = environment.child();
                        if match_pattern(self.program, &target.pattern, &value, &child)? {
                            selected = Some((target.block, child));
                            break;
                        }
                    }
                    let Some((target, child)) = selected else {
                        return Err(YinError::language("non-exhaustive MIR match"));
                    };
                    environment = child;
                    block = target;
                    incoming.clear();
                }
                MirTerminator::Unreachable => {
                    return Err(YinError::language("reached unterminated MIR block"));
                }
            }
        }
    }

    fn instruction(
        &mut self,
        instruction: &MirInstruction,
        locals: &IndexMap<LocalId, RuntimeValue>,
        environment: &Environment,
    ) -> Result<RuntimeValue, YinError> {
        Ok(match &instruction.kind {
            MirInstructionKind::Constant(value) => runtime_data(literal_value(value)?),
            MirInstructionKind::Void => runtime_data(Value::Void),
            MirInstructionKind::ReadSymbol(symbol) => {
                if let Some(value) = environment.get(*symbol) {
                    value
                } else {
                    let definition = self
                        .program
                        .symbols
                        .get(symbol.0 as usize)
                        .ok_or_else(|| YinError::language("unknown MIR symbol"))?;
                    if definition.kind == HirSymbolKind::Builtin {
                        RuntimeValue::Builtin(definition.name.clone())
                    } else {
                        return Err(YinError::language(format!(
                            "unbound MIR symbol: {}",
                            definition.name
                        )));
                    }
                }
            }
            MirInstructionKind::BindSymbol { symbol, value } => {
                let value = local(locals, *value)?;
                environment.define(*symbol, value.clone());
                value
            }
            MirInstructionKind::Vector(values) => runtime_data(Value::Vector(
                values
                    .iter()
                    .map(|value| data(local(locals, *value)?))
                    .collect::<Result<Vec<_>, _>>()?,
            )),
            MirInstructionKind::Closure(function) => RuntimeValue::Closure(Rc::new(Closure {
                function: *function,
                environment: environment.clone(),
            })),
            MirInstructionKind::Call { callee, arguments } => {
                let callee = local(locals, *callee)?;
                let arguments = argument_values(arguments, locals)?;
                self.call(callee, arguments)?
            }
            MirInstructionKind::Construct {
                constructor,
                arguments,
            } => runtime_data(self.construct(constructor, arguments, locals)?),
            MirInstructionKind::Field { value, name } => {
                let value = data(local(locals, *value)?)?;
                runtime_data(field_value(&value, name)?)
            }
        })
    }

    fn call(
        &mut self,
        callee: RuntimeValue,
        arguments: Vec<MirArgumentValue>,
    ) -> Result<RuntimeValue, YinError> {
        match callee {
            RuntimeValue::Closure(closure) => {
                self.call_function(closure.function, closure.environment.clone(), arguments)
            }
            RuntimeValue::Builtin(name) => {
                if arguments.iter().any(|argument| argument.keyword.is_some()) {
                    return Err(YinError::language(
                        "builtin does not accept keyword arguments",
                    ));
                }
                let values = arguments
                    .into_iter()
                    .map(|argument| data(argument.value))
                    .collect::<Result<Vec<_>, _>>()?;
                Ok(runtime_data(primitive(&name, &values)?))
            }
            RuntimeValue::Data(_) => Err(YinError::language("attempted to call MIR data")),
        }
    }

    fn construct(
        &self,
        constructor: &HirConstructor,
        arguments: &[MirArgument],
        locals: &IndexMap<LocalId, RuntimeValue>,
    ) -> Result<Value, YinError> {
        match constructor {
            HirConstructor::Ok | HirConstructor::Err => {
                let value = single_argument(arguments, locals)?;
                Ok(Value::Result {
                    ok: matches!(constructor, HirConstructor::Ok),
                    value: Box::new(value),
                })
            }
            HirConstructor::Some => Ok(Value::Option(Some(Box::new(single_argument(
                arguments, locals,
            )?)))),
            HirConstructor::None => Ok(Value::Option(None)),
            HirConstructor::Record(symbol) | HirConstructor::VariantCase(symbol) => {
                self.construct_record(*symbol, arguments, locals)
            }
        }
    }

    fn construct_record(
        &self,
        symbol: SymbolId,
        arguments: &[MirArgument],
        locals: &IndexMap<LocalId, RuntimeValue>,
    ) -> Result<Value, YinError> {
        let definition = self
            .program
            .records
            .get(&symbol)
            .ok_or_else(|| YinError::language("unknown MIR record constructor"))?;
        let fields = self.record_fields(definition)?;
        let mut values = IndexMap::new();
        let has_keywords = arguments.iter().any(|argument| argument.keyword.is_some());
        if has_keywords {
            for field in &fields {
                let argument = arguments
                    .iter()
                    .find(|argument| argument.keyword.as_deref() == Some(field))
                    .ok_or_else(|| YinError::language(format!("missing field: {field}")))?;
                values.insert(field.clone(), data(local(locals, argument.value)?)?);
            }
        } else {
            if arguments.len() != fields.len() {
                return Err(YinError::language("wrong MIR record arity"));
            }
            for (field, argument) in fields.iter().zip(arguments) {
                values.insert(field.clone(), data(local(locals, argument.value)?)?);
            }
        }
        Ok(Value::Record {
            name: definition.name.clone(),
            fields: values,
            parents: definition
                .parents
                .iter()
                .filter_map(|parent| self.program.records.get(parent))
                .map(|parent| parent.name.clone())
                .collect(),
            variant: definition.variant.clone(),
        })
    }

    fn record_fields(&self, definition: &MirRecordDefinition) -> Result<Vec<String>, YinError> {
        let mut fields = definition.fields.clone();
        for parent in &definition.parents {
            let parent = self
                .program
                .records
                .get(parent)
                .ok_or_else(|| YinError::language("unknown MIR parent record"))?;
            fields.extend(self.record_fields(parent)?);
        }
        Ok(fields)
    }
}

#[derive(Clone)]
struct MirArgumentValue {
    keyword: Option<String>,
    value: RuntimeValue,
}

fn argument_values(
    arguments: &[MirArgument],
    locals: &IndexMap<LocalId, RuntimeValue>,
) -> Result<Vec<MirArgumentValue>, YinError> {
    arguments
        .iter()
        .map(|argument| {
            Ok(MirArgumentValue {
                keyword: argument.keyword.clone(),
                value: local(locals, argument.value)?,
            })
        })
        .collect()
}

fn bind_arguments(
    function: &MirFunction,
    arguments: Vec<MirArgumentValue>,
    environment: &Environment,
) -> Result<(), YinError> {
    let has_keywords = arguments.iter().any(|argument| argument.keyword.is_some());
    if has_keywords && arguments.iter().any(|argument| argument.keyword.is_none()) {
        return Err(YinError::language(
            "cannot mix positional and keyword arguments",
        ));
    }
    if has_keywords {
        for parameter in &function.parameters {
            let argument = arguments
                .iter()
                .find(|argument| argument.keyword.as_deref() == Some(&parameter.name))
                .ok_or_else(|| {
                    YinError::language(format!("missing argument: {}", parameter.name))
                })?;
            environment.define(parameter.symbol, argument.value.clone());
        }
        if arguments.iter().any(|argument| {
            !function
                .parameters
                .iter()
                .any(|parameter| Some(parameter.name.as_str()) == argument.keyword.as_deref())
        }) {
            return Err(YinError::language("unknown keyword argument"));
        }
    } else {
        let required = function
            .parameters
            .iter()
            .filter(|parameter| parameter.required)
            .count();
        if arguments.len() < required || arguments.len() > function.parameters.len() {
            return Err(YinError::language("wrong MIR function arity"));
        }
        for (parameter, argument) in function.parameters.iter().zip(arguments) {
            environment.define(parameter.symbol, argument.value);
        }
    }
    Ok(())
}

fn local(
    locals: &IndexMap<LocalId, RuntimeValue>,
    local: LocalId,
) -> Result<RuntimeValue, YinError> {
    locals
        .get(&local)
        .cloned()
        .ok_or_else(|| YinError::language(format!("undefined MIR local _{}", local.0)))
}

fn data(value: RuntimeValue) -> Result<Value, YinError> {
    match value {
        RuntimeValue::Data(value) => Ok(*value),
        RuntimeValue::Closure(_) => Err(YinError::language("expected MIR data, got function")),
        RuntimeValue::Builtin(_) => Err(YinError::language("expected MIR data, got builtin")),
    }
}

fn runtime_data(value: Value) -> RuntimeValue {
    RuntimeValue::Data(Box::new(value))
}

fn bool_data(value: RuntimeValue) -> Result<bool, YinError> {
    match data(value)? {
        Value::Bool(value) => Ok(value),
        _ => Err(YinError::language("MIR branch condition must be Bool")),
    }
}

fn literal_value(literal: &HirLiteral) -> Result<Value, YinError> {
    Ok(match literal {
        HirLiteral::Int(value) => Value::Int(parse_integer(value)?),
        HirLiteral::Float(value) => Value::Float(
            value
                .parse()
                .map_err(|_| YinError::language("invalid MIR float literal"))?,
        ),
        HirLiteral::Bool(value) => Value::Bool(*value),
        HirLiteral::String(value) => Value::String(value.clone()),
    })
}

fn parse_integer(value: &str) -> Result<BigInt, YinError> {
    if let Some(value) = value.strip_prefix("0x") {
        BigInt::parse_bytes(value.as_bytes(), 16)
    } else if let Some(value) = value.strip_prefix("0b") {
        BigInt::parse_bytes(value.as_bytes(), 2)
    } else {
        value.parse().ok()
    }
    .ok_or_else(|| YinError::language("invalid MIR integer literal"))
}

fn single_argument(
    arguments: &[MirArgument],
    locals: &IndexMap<LocalId, RuntimeValue>,
) -> Result<Value, YinError> {
    if arguments.len() != 1 {
        return Err(YinError::language("MIR constructor expects one argument"));
    }
    data(local(locals, arguments[0].value)?)
}

fn field_value(value: &Value, field: &str) -> Result<Value, YinError> {
    match value {
        Value::Record { fields, .. } => fields
            .get(field)
            .cloned()
            .ok_or_else(|| YinError::language(format!("record has no field: {field}"))),
        _ => Err(YinError::language("field access requires a record")),
    }
}

fn match_pattern(
    program: &MirProgram,
    pattern: &HirPattern,
    value: &Value,
    environment: &Environment,
) -> Result<bool, YinError> {
    Ok(match &pattern.kind {
        HirPatternKind::Wildcard => true,
        HirPatternKind::Binding(symbol) => {
            environment.define(*symbol, runtime_data(value.clone()));
            true
        }
        HirPatternKind::Literal(literal) => literal_value(literal)? == *value,
        HirPatternKind::Vector(patterns) => {
            let Value::Vector(values) = value else {
                return Ok(false);
            };
            if patterns.len() != values.len() {
                false
            } else {
                let mut matched = true;
                for (pattern, value) in patterns.iter().zip(values) {
                    matched &= match_pattern(program, pattern, value, environment)?;
                }
                matched
            }
        }
        HirPatternKind::Constructor {
            constructor,
            payloads,
        } => {
            let values = match constructor {
                HirPatternConstructor::Ok => match value {
                    Value::Result { ok: true, value } => vec![(**value).clone()],
                    _ => return Ok(false),
                },
                HirPatternConstructor::Err => match value {
                    Value::Result { ok: false, value } => vec![(**value).clone()],
                    _ => return Ok(false),
                },
                HirPatternConstructor::Some => match value {
                    Value::Option(Some(value)) => vec![(**value).clone()],
                    _ => return Ok(false),
                },
                HirPatternConstructor::None => match value {
                    Value::Option(None) => Vec::new(),
                    _ => return Ok(false),
                },
                HirPatternConstructor::Int => match value {
                    Value::Int(_) => vec![value.clone()],
                    _ => return Ok(false),
                },
                HirPatternConstructor::Float => match value {
                    Value::Float(_) => vec![value.clone()],
                    _ => return Ok(false),
                },
                HirPatternConstructor::Bool => match value {
                    Value::Bool(_) => vec![value.clone()],
                    _ => return Ok(false),
                },
                HirPatternConstructor::String => match value {
                    Value::String(_) => vec![value.clone()],
                    _ => return Ok(false),
                },
                HirPatternConstructor::Record(symbol)
                | HirPatternConstructor::VariantCase(symbol) => {
                    let Value::Record { name, fields, .. } = value else {
                        return Ok(false);
                    };
                    let Some(definition) = program.records.get(symbol) else {
                        return Err(YinError::language("unknown MIR pattern constructor"));
                    };
                    if name != &definition.name {
                        return Ok(false);
                    }
                    record_pattern_fields(program, definition)?
                        .iter()
                        .map(|field| {
                            fields.get(field).cloned().ok_or_else(|| {
                                YinError::language(format!("record has no field: {field}"))
                            })
                        })
                        .collect::<Result<Vec<_>, _>>()?
                }
            };
            if values.len() != payloads.len() {
                false
            } else {
                let mut matched = true;
                for (pattern, value) in payloads.iter().zip(&values) {
                    matched &= match_pattern(program, pattern, value, environment)?;
                }
                matched
            }
        }
    })
}

fn record_pattern_fields(
    program: &MirProgram,
    definition: &MirRecordDefinition,
) -> Result<Vec<String>, YinError> {
    let mut fields = definition.fields.clone();
    for parent in &definition.parents {
        let parent = program
            .records
            .get(parent)
            .ok_or_else(|| YinError::language("unknown MIR parent record"))?;
        fields.extend(record_pattern_fields(program, parent)?);
    }
    Ok(fields)
}

fn primitive(name: &str, arguments: &[Value]) -> Result<Value, YinError> {
    match name {
        "+" | "-" | "*" | "/" => numeric(name, arguments),
        "<" | "<=" | ">" | ">=" => compare(name, arguments),
        "=" => {
            arity(arguments, 2, name)?;
            Ok(Value::Bool(arguments[0] == arguments[1]))
        }
        "and" | "or" => {
            arity(arguments, 2, name)?;
            let (left, right) = (bool_value(&arguments[0])?, bool_value(&arguments[1])?);
            Ok(Value::Bool(if name == "and" {
                left && right
            } else {
                left || right
            }))
        }
        "not" => {
            arity(arguments, 1, name)?;
            Ok(Value::Bool(!bool_value(&arguments[0])?))
        }
        "length" => {
            arity(arguments, 1, name)?;
            let Value::Vector(values) = &arguments[0] else {
                return Err(YinError::language("length expects a vector"));
            };
            Ok(Value::Int(BigInt::from(values.len())))
        }
        "at" => {
            arity(arguments, 2, name)?;
            let Value::Vector(values) = &arguments[0] else {
                return Err(YinError::language("at expects a vector"));
            };
            let Value::Int(index) = &arguments[1] else {
                return Err(YinError::language("at expects an integer index"));
            };
            values
                .get(
                    index
                        .to_usize()
                        .ok_or_else(|| YinError::language("invalid vector index"))?,
                )
                .cloned()
                .ok_or_else(|| YinError::language("vector index out of bounds"))
        }
        "append" => {
            arity(arguments, 2, name)?;
            let (Value::Vector(left), Value::Vector(right)) = (&arguments[0], &arguments[1]) else {
                return Err(YinError::language("append expects vectors"));
            };
            Ok(Value::Vector(left.iter().chain(right).cloned().collect()))
        }
        _ => Err(YinError::language(format!(
            "builtin {name} is outside MIR phase 1"
        ))),
    }
}

fn numeric(name: &str, arguments: &[Value]) -> Result<Value, YinError> {
    arity(arguments, 2, name)?;
    match (&arguments[0], &arguments[1]) {
        (Value::Int(left), Value::Int(right)) => Ok(Value::Int(match name {
            "+" => left + right,
            "-" => left - right,
            "*" => left * right,
            "/" if right.is_zero() => return Err(YinError::language("division by zero")),
            "/" => left / right,
            _ => unreachable!(),
        })),
        (left, right) => {
            let left = float_value(left)?;
            let right = float_value(right)?;
            if name == "/" && right == 0.0 {
                return Err(YinError::language("division by zero"));
            }
            Ok(Value::Float(match name {
                "+" => left + right,
                "-" => left - right,
                "*" => left * right,
                "/" => left / right,
                _ => unreachable!(),
            }))
        }
    }
}

fn compare(name: &str, arguments: &[Value]) -> Result<Value, YinError> {
    arity(arguments, 2, name)?;
    let left = float_value(&arguments[0])?;
    let right = float_value(&arguments[1])?;
    Ok(Value::Bool(match name {
        "<" => left < right,
        "<=" => left <= right,
        ">" => left > right,
        ">=" => left >= right,
        _ => unreachable!(),
    }))
}

fn float_value(value: &Value) -> Result<f64, YinError> {
    match value {
        Value::Int(value) => value
            .to_f64()
            .ok_or_else(|| YinError::language("integer is outside MIR float range")),
        Value::Float(value) => Ok(*value),
        _ => Err(YinError::language("numeric argument required")),
    }
}

fn bool_value(value: &Value) -> Result<bool, YinError> {
    match value {
        Value::Bool(value) => Ok(*value),
        _ => Err(YinError::language("boolean argument required")),
    }
}

fn arity(arguments: &[Value], expected: usize, name: &str) -> Result<(), YinError> {
    if arguments.len() == expected {
        Ok(())
    } else {
        Err(YinError::language(format!(
            "{name} expects {expected} arguments"
        )))
    }
}

fn mir_error(expression: &HirExpr, message: impl Into<String>) -> YinError {
    YinError::new(ErrorCode::Language, message, Some(expression.span.clone()))
}

fn synthetic_span() -> SourceSpan {
    SourceSpan {
        file: "<mir>".into(),
        start: 0,
        end: 0,
        line: 1,
        column: 1,
    }
}

pub fn render_mir(program: &MirProgram) -> String {
    let mut output = format!(
        "mir program entry=@{} -> {:?}\n",
        program.entry.0, program.result_type
    );
    for function in &program.functions {
        output.push_str(&format!("function @{}(", function.id.0));
        for (index, parameter) in function.parameters.iter().enumerate() {
            if index > 0 {
                output.push_str(", ");
            }
            output.push_str(&format!("%{} {:?}", parameter.symbol.0, parameter.ty));
        }
        output.push_str(&format!(") -> {:?}\n", function.return_type));
        for block in &function.blocks {
            output.push_str(&format!("  bb{}", block.id.0));
            if !block.parameters.is_empty() {
                output.push('(');
                for (index, parameter) in block.parameters.iter().enumerate() {
                    if index > 0 {
                        output.push_str(", ");
                    }
                    output.push_str(&format!("_{} {:?}", parameter.local.0, parameter.ty));
                }
                output.push(')');
            }
            output.push_str(":\n");
            for instruction in &block.instructions {
                output.push_str(&format!(
                    "    _{} = {} -> {:?}\n",
                    instruction.output.0,
                    render_instruction(&instruction.kind),
                    instruction.ty
                ));
            }
            output.push_str(&format!("    {}\n", render_terminator(&block.terminator)));
        }
    }
    output
}

fn render_instruction(kind: &MirInstructionKind) -> String {
    match kind {
        MirInstructionKind::Constant(value) => format!("constant {value:?}"),
        MirInstructionKind::Void => "void".into(),
        MirInstructionKind::ReadSymbol(symbol) => format!("read %{}", symbol.0),
        MirInstructionKind::BindSymbol { symbol, value } => {
            format!("bind %{} _{}", symbol.0, value.0)
        }
        MirInstructionKind::Vector(values) => format!(
            "vector [{}]",
            values
                .iter()
                .map(|value| format!("_{}", value.0))
                .collect::<Vec<_>>()
                .join(", ")
        ),
        MirInstructionKind::Closure(function) => format!("closure @{}", function.0),
        MirInstructionKind::Call { callee, arguments } => {
            format!("call _{}({})", callee.0, render_arguments(arguments))
        }
        MirInstructionKind::Construct {
            constructor,
            arguments,
        } => format!("construct {constructor:?}({})", render_arguments(arguments)),
        MirInstructionKind::Field { value, name } => format!("field _{} :{name}", value.0),
    }
}

fn render_arguments(arguments: &[MirArgument]) -> String {
    arguments
        .iter()
        .map(|argument| {
            format!(
                "{}_{}",
                argument
                    .keyword
                    .as_ref()
                    .map(|keyword| format!(":{keyword}="))
                    .unwrap_or_default(),
                argument.value.0
            )
        })
        .collect::<Vec<_>>()
        .join(", ")
}

fn render_terminator(terminator: &MirTerminator) -> String {
    match terminator {
        MirTerminator::Return(value) => format!("return _{}", value.0),
        MirTerminator::Jump { target, arguments } => format!(
            "jump bb{}({})",
            target.0,
            arguments
                .iter()
                .map(|value| format!("_{}", value.0))
                .collect::<Vec<_>>()
                .join(", ")
        ),
        MirTerminator::Branch {
            condition,
            then_block,
            else_block,
        } => format!(
            "branch _{} bb{} bb{}",
            condition.0, then_block.0, else_block.0
        ),
        MirTerminator::Match { value, targets } => format!(
            "match _{} [{}]",
            value.0,
            targets
                .iter()
                .map(|target| format!("{:?} => bb{}", target.pattern.kind, target.block.0))
                .collect::<Vec<_>>()
                .join(", ")
        ),
        MirTerminator::Unreachable => "unreachable".into(),
    }
}
