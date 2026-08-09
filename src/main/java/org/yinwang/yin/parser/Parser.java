package org.yinwang.yin.parser;

import org.yinwang.yin.Constants;
import org.yinwang.yin.Scope;
import org.yinwang.yin.Util;
import org.yinwang.yin.ast.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * Parser
 * parse S-expression-like structure into more structured data
 * with Classes, fields etc that can be easily accessed
 */
public class Parser {

    public static Node parse(String file) throws ParserException {
        PreParser preparser = new PreParser(file);
        Node prenode = preparser.parse();
        return parseNode(prenode);
    }


    public static Node parseSource(String sourceName, String source) throws ParserException {
        PreParser preparser = new PreParser(sourceName, source);
        Node prenode = preparser.parse();
        return parseNode(prenode);
    }


    public static Node parseNode(Node prenode) throws ParserException {

        if (!(prenode instanceof Tuple)) {
            // Case 1: node is not of form (..) or [..], return the node itself
            return prenode;
        } else {
            // Case 2: node is of form (..) or [..]
            Tuple tuple = ((Tuple) prenode);
            List<Node> elements = tuple.elements;

            if (delimType(tuple.open, Constants.SQUARE_BEGIN)) {
                // Case 2.1: node is of form [..]
                return new VectorLiteral(parseList(elements), tuple.file, tuple.start, tuple.end, tuple.line, tuple.col);
            } else {
                // Case 2.2: node is (..)
                if (elements.isEmpty()) {
                    // Case 2.2.1: node is (). This is not allowed
                    throw new ParserException("syntax error", tuple);
                } else {
                    // Case 2.2.2: node is of form (keyword ..)
                    Node keyNode = elements.get(0);

                    if (keyNode instanceof Name) {
                        switch (((Name) keyNode).id) {
                            case Constants.SEQ_KEYWORD:
                                return parseBlock(tuple);
                            case Constants.IF_KEYWORD:
                                return parseIf(tuple);
                            case Constants.DEF_KEYWORD:
                                return parseDef(tuple);
                            case Constants.ASSIGN_KEYWORD:
                                return parseAssign(tuple);
                            case Constants.DECLARE_KEYWORD:
                                throw new ParserException("standalone declare forms are unsupported", tuple);
                            case Constants.FUN_KEYWORD:
                                return parseFun(tuple);
                            case Constants.RECORD_KEYWORD:
                                return parseRecordDef(tuple);
                            case Constants.VARIANT_KEYWORD:
                                return parseVariantDef(tuple);
                            case Constants.FIELD_KEYWORD:
                                return parseFieldAccess(tuple);
                            case Constants.MATCH_KEYWORD:
                                return parseMatch(tuple);
                            case Constants.DECODE_JSON_KEYWORD:
                                return parseJsonOperation(tuple, JsonOperation.Kind.DECODE);
                            case Constants.ENCODE_JSON_KEYWORD:
                                return parseJsonOperation(tuple, JsonOperation.Kind.ENCODE);
                            case Constants.JSON_SCHEMA_KEYWORD:
                                return parseJsonOperation(tuple, JsonOperation.Kind.SCHEMA);
                            default:
                                return parseCall(tuple);
                        }
                    } else {
                        // applications whose operator is not a name
                        // e.g. ((foo 1) 2)
                        return parseCall(tuple);
                    }
                }
            }
        }
    }


    public static Block parseBlock(Tuple tuple) throws ParserException {
        List<Node> elements = tuple.elements;
        List<Node> statements = parseList(elements.subList(1, elements.size()));
        return new Block(statements, tuple.file, tuple.start, tuple.end, tuple.line, tuple.col);
    }


    public static If parseIf(Tuple tuple) throws ParserException {
        List<Node> elements = tuple.elements;
        if (elements.size() != 4) {
            throw new ParserException("incorrect format of if", tuple);
        }
        Node test = parseNode(elements.get(1));
        Node conseq = parseNode(elements.get(2));
        Node alter = parseNode(elements.get(3));
        return new If(test, conseq, alter, tuple.file, tuple.start, tuple.end, tuple.line, tuple.col);
    }


    public static Def parseDef(Tuple tuple) throws ParserException {
        List<Node> elements = tuple.elements;
        if (elements.size() != 3) {
            throw new ParserException("incorrect format of definition", tuple);
        }
        Node pattern = parseNode(elements.get(1));
        Node value = parseNode(elements.get(2));
        return new Def(pattern, value, tuple.file, tuple.start, tuple.end, tuple.line, tuple.col);

    }


    public static Assign parseAssign(Tuple tuple) throws ParserException {
        List<Node> elements = tuple.elements;
        if (elements.size() != 3) {
            throw new ParserException("incorrect format of definition", tuple);
        }
        Node pattern = parseNode(elements.get(1));
        Node value = parseNode(elements.get(2));
        return new Assign(pattern, value, tuple.file, tuple.start, tuple.end, tuple.line, tuple.col);
    }


    public static Declare parseDeclare(Tuple tuple) throws ParserException {
        List<Node> elements = tuple.elements;
        if (elements.size() < 2) {
            throw new ParserException("syntax error in record type definition", tuple);
        }
        Scope<Object> properties = parseProperties(elements.subList(1, elements.size()));
        return new Declare(properties, tuple.file, tuple.start, tuple.end, tuple.line, tuple.col);
    }


    public static Fun parseFun(Tuple tuple) throws ParserException {
        List<Node> elements = tuple.elements;

        if (elements.size() < 3) {
            throw new ParserException("syntax error in function definition", tuple);
        }

        // construct parameter list
        Node preParams = elements.get(1);
        if (!(preParams instanceof Tuple)) {
            throw new ParserException("incorrect format of parameters: " + preParams.toString(), preParams);
        }

        // parse the parameters, test whether it's all names or all tuples
        boolean hasName = false;
        boolean hasTuple = false;
        List<Name> paramNames = new ArrayList<>();
        List<Node> paramTuples = new ArrayList<>();

        List<Node> preParamElements = ((Tuple) preParams).elements;
        for (int parameterIndex = 0; parameterIndex < preParamElements.size(); parameterIndex++) {
            Node p = preParamElements.get(parameterIndex);
            if (p instanceof Name) {
                hasName = true;
                paramNames.add((Name) p);
            } else if (p instanceof Tuple) {
                hasTuple = true;
                List<Node> argElements = ((Tuple) p).elements;
                if (argElements.size() == 0) {
                    throw new ParserException("illegal argument format: " + p.toString(), p);
                }
                if (!(argElements.get(0) instanceof Name)) {
                    throw new ParserException("illegal argument name : " + argElements.get(0), p);
                }

                Name name = (Name) argElements.get(0);
                if (name.id.equals(Constants.RETURN_ARROW)) {
                    if (parameterIndex != preParamElements.size() - 1) {
                        throw new ParserException("return descriptor must be last", p);
                    }
                } else {
                    paramNames.add(name);
                }
                paramTuples.add(p);
            }
        }

        if (hasName && hasTuple) {
            throw new ParserException("parameters must be either all names or all tuples: " +
                    preParams.toString(), preParams);
        }

        Scope<Object> properties;
        if (hasTuple) {
            properties = parseProperties(paramTuples);
        } else {
            properties = null;
        }

        // construct body
        List<Node> statements = parseList(elements.subList(2, elements.size()));
        int start = statements.get(0).start;
        int end = statements.get(statements.size() - 1).end;
        Node body = new Block(statements, tuple.file, start, end, tuple.line, tuple.col);

        return new Fun(paramNames, properties, body,
                tuple.file, tuple.start, tuple.end, tuple.line, tuple.col);
    }


    public static RecordDef parseRecordDef(Tuple tuple) throws ParserException {
        List<Node> elements = tuple.elements;
        if (elements.size() < 2) {
            throw new ParserException("syntax error in record type definition", tuple);
        }

        Node name = elements.get(1);
        Node maybeParents = elements.size() > 2 ? elements.get(2) : null;

        List<Name> parents;
        List<Node> fields;

        if (!(name instanceof Name)) {
            throw new ParserException("syntax error in record name: " + name.toString(), name);
        }

        // check if there are parents (record A (B C) ...)
        if (maybeParents instanceof Tuple &&
                delimType(((Tuple) maybeParents).open, Constants.PAREN_BEGIN))
        {
            List<Node> parentNodes = ((Tuple) maybeParents).elements;
            parents = new ArrayList<>();
            for (Node p : parentNodes) {
                if (!(p instanceof Name)) {
                    throw new ParserException("parents can only be names", p);
                }
                parents.add((Name) p);
            }
            fields = elements.subList(3, elements.size());
        } else {
            parents = null;
            fields = elements.subList(2, elements.size());
        }

        Scope<Object> properties = parseProperties(fields);
        return new RecordDef((Name) name, parents, properties, tuple.file,
                tuple.start, tuple.end, tuple.line, tuple.col);
    }

    public static VariantDef parseVariantDef(Tuple tuple) throws ParserException {
        List<Node> elements = tuple.elements;
        if (elements.size() < 3 || !(elements.get(1) instanceof Name name)) {
            throw new ParserException("variant requires a name and at least one case", tuple);
        }
        Map<String, Scope<Object>> cases = new LinkedHashMap<>();
        for (Node caseNode : elements.subList(2, elements.size())) {
            if (!(caseNode instanceof Tuple caseTuple)
                    || !delimType(caseTuple.open, Constants.SQUARE_BEGIN)
                    || caseTuple.elements.isEmpty()
                    || !(caseTuple.elements.get(0) instanceof Name caseName)) {
                throw new ParserException("variant cases must be [Case [field Type] ...]", caseNode);
            }
            if (cases.containsKey(caseName.id)) {
                throw new ParserException("duplicated variant case: " + caseName.id, caseName);
            }
            cases.put(caseName.id, parseProperties(
                    caseTuple.elements.subList(1, caseTuple.elements.size())));
        }
        return new VariantDef(name, cases, tuple.file, tuple.start, tuple.end, tuple.line, tuple.col);
    }

    private static JsonOperation parseJsonOperation(Tuple tuple, JsonOperation.Kind kind)
            throws ParserException {
        int expected = kind == JsonOperation.Kind.DECODE ? 3 : 2;
        if (tuple.elements.size() != expected) {
            throw new ParserException(kind.sourceName() + " expects " + (expected - 1) + " argument(s)", tuple);
        }
        if (kind == JsonOperation.Kind.ENCODE) {
            return new JsonOperation(kind, null, parseNode(tuple.elements.get(1)),
                    tuple.file, tuple.start, tuple.end, tuple.line, tuple.col);
        }
        Node type = parseNode(tuple.elements.get(1));
        if (!isTypeExpression(type)) {
            throw new ParserException("expected a type expression", tuple.elements.get(1));
        }
        Node value = kind == JsonOperation.Kind.DECODE ? parseNode(tuple.elements.get(2)) : null;
        return new JsonOperation(kind, type, value,
                tuple.file, tuple.start, tuple.end, tuple.line, tuple.col);
    }


    public static FieldAccess parseFieldAccess(Tuple tuple) throws ParserException {
        List<Node> elements = tuple.elements;
        if (elements.size() != 3) {
            throw new ParserException("field access must have a target and one field keyword", tuple);
        }
        Node field = elements.get(2);
        if (!(field instanceof Keyword)) {
            throw new ParserException("field name must be a keyword, but got: " + field, field);
        }
        return new FieldAccess(parseNode(elements.get(1)), (Keyword) field,
                tuple.file, tuple.start, tuple.end, tuple.line, tuple.col);
    }

    public static Match parseMatch(Tuple tuple) throws ParserException {
        List<Node> elements = tuple.elements;
        if (elements.size() < 3) {
            throw new ParserException("match requires a target and at least one clause", tuple);
        }
        Node target = parseNode(elements.get(1));
        List<Match.Clause> clauses = new ArrayList<>();
        for (Node clauseNode : elements.subList(2, elements.size())) {
            if (!(clauseNode instanceof Tuple clause)
                    || !delimType(clause.open, Constants.SQUARE_BEGIN)
                    || clause.elements.size() != 2) {
                throw new ParserException("match clauses must be [pattern expression]", clauseNode);
            }
            MatchPattern pattern = parseMatchPattern(clause.elements.get(0));
            rejectDuplicatePatternBindings(pattern, new java.util.LinkedHashSet<>());
            clauses.add(new Match.Clause(pattern, parseNode(clause.elements.get(1))));
        }
        return new Match(target, clauses, tuple.file, tuple.start, tuple.end, tuple.line, tuple.col);
    }

    private static MatchPattern parseMatchPattern(Node node) throws ParserException {
        if (node instanceof Name name) {
            if (name.id.equals("_")) {
                return new MatchPattern.Wildcard(name);
            }
            if (name.id.equals("true") || name.id.equals("false")) {
                return new MatchPattern.Literal(name);
            }
            return new MatchPattern.Binding(name);
        }
        if (node instanceof IntNum || node instanceof FloatNum || node instanceof Str) {
            return new MatchPattern.Literal(node);
        }
        if (!(node instanceof Tuple tuple)) {
            throw new ParserException("unsupported match pattern: " + node, node);
        }
        if (delimType(tuple.open, Constants.SQUARE_BEGIN)) {
            List<MatchPattern> elements = new ArrayList<>();
            for (Node element : tuple.elements) {
                elements.add(parseMatchPattern(element));
            }
            return new MatchPattern.VectorPattern(elements, tuple);
        }
        if (tuple.elements.isEmpty() || !(tuple.elements.get(0) instanceof Name type)) {
            throw new ParserException("record pattern must start with a record type", tuple);
        }
        List<MatchPattern> fields = new ArrayList<>();
        for (Node field : tuple.elements.subList(1, tuple.elements.size())) {
            fields.add(parseMatchPattern(field));
        }
        if ((type.id.equals(Constants.OK_PATTERN) || type.id.equals(Constants.ERR_PATTERN))
                && fields.size() != 1) {
            throw new ParserException(type.id + " pattern expects exactly one payload", tuple);
        }
        if (type.id.equals("Some") && fields.size() != 1) {
            throw new ParserException("Some pattern expects exactly one payload", tuple);
        }
        if (type.id.equals("None") && !fields.isEmpty()) {
            throw new ParserException("None pattern expects no payload", tuple);
        }
        return new MatchPattern.RecordPattern(type, fields, tuple);
    }

    private static void rejectDuplicatePatternBindings(
            MatchPattern pattern, java.util.Set<String> bindings) throws ParserException {
        if (pattern instanceof MatchPattern.Binding binding) {
            if (!bindings.add(binding.name().id)) {
                throw new ParserException("duplicate binding in pattern: " + binding.name().id,
                        binding.name());
            }
        } else if (pattern instanceof MatchPattern.VectorPattern vector) {
            for (MatchPattern element : vector.elements()) {
                rejectDuplicatePatternBindings(element, bindings);
            }
        } else if (pattern instanceof MatchPattern.RecordPattern record) {
            for (MatchPattern field : record.fields()) {
                rejectDuplicatePatternBindings(field, bindings);
            }
        }
    }


    public static Call parseCall(Tuple tuple) throws ParserException {
        List<Node> elements = tuple.elements;
        Node func = parseNode(elements.get(0));
        List<Node> parsedArgs = parseList(elements.subList(1, elements.size()));
        Argument args = new Argument(parsedArgs);
        return new Call(func, args, tuple.file, tuple.start, tuple.end, tuple.line, tuple.col);
    }


    public static List<Node> parseList(List<Node> prenodes) throws ParserException {
        List<Node> parsed = new ArrayList<>();
        for (Node s : prenodes) {
            parsed.add(parseNode(s));
        }
        return parsed;
    }


    // treat the list of nodes as key-value pairs like (:x 1 :y 2)
    public static Map<String, Node> parseMap(List<Node> prenodes) throws ParserException {
        Map<String, Node> ret = new LinkedHashMap<>();
        if (prenodes.size() % 2 != 0) {
            throw new ParserException("must be of the form (:key1 value1 :key2 value2), but got: " +
                    prenodes.toString(), prenodes.get(0));
        }

        for (int i = 0; i < prenodes.size(); i += 2) {
            Node key = prenodes.get(i);
            Node value = prenodes.get(i + 1);
            if (!(key instanceof Keyword)) {
                throw new ParserException("key must be a keyword, but got: " + key.toString(), key);
            }
            String id = ((Keyword) key).id;
            if (ret.containsKey(id)) {
                throw new ParserException("duplicated keyword: " + key, key);
            }
            ret.put(id, value);
        }
        return ret;
    }


    public static Scope<Object> parseProperties(List<Node> fields) throws ParserException {
        Scope<Object> properties = new Scope<>();
        for (Node field : fields) {
            if (!(field instanceof Tuple &&
                    delimType(((Tuple) field).open, Constants.SQUARE_BEGIN) &&
                    ((Tuple) field).elements.size() >= 2))
            {
                throw new ParserException("incorrect form of descriptor: " + field.toString(), field);
            } else {
                List<Node> elements = parseList(((Tuple) field).elements);
                Node nameNode = elements.get(0);
                if (!(nameNode instanceof Name)) {
                    throw new ParserException("expect a name, but got: " + nameNode.toString(), nameNode);
                }
                String id = ((Name) nameNode).id;
                if (properties.containsKey(id)) {
                    throw new ParserException("duplicated name: " + nameNode.toString(), nameNode);
                }

                Node typeNode = elements.get(1);
                if (typeNode instanceof Call call
                        && call.op instanceof Name operator
                        && operator.id.equals(Constants.UNION_KEYWORD)
                        && call.args.positional.isEmpty()) {
                    throw new ParserException("union type requires at least one member", typeNode);
                }
                if (!isTypeExpression(typeNode)) {
                    throw new ParserException("unsupported type expression: " + typeNode, typeNode);
                }
                properties.put(id, "type", typeNode);

                Map<String, Node> props = parseMap(elements.subList(2, elements.size()));
                for (String property : props.keySet()) {
                    if (!property.equals("default")) {
                        throw new ParserException("unsupported descriptor property: :" + property,
                                props.get(property));
                    }
                }
                if (id.equals(Constants.RETURN_ARROW) && !props.isEmpty()) {
                    throw new ParserException("return type descriptor cannot have properties", field);
                }
                Map<String, Object> propsObj = new LinkedHashMap<>();
                for (Map.Entry<String, Node> e : props.entrySet()) {
                    propsObj.put(e.getKey(), e.getValue());
                }
                properties.putProperties(((Name) nameNode).id, propsObj);
            }
        }
        return properties;
    }


    private static boolean isTypeExpression(Node node) {
        if (node instanceof Name) {
            return true;
        }
        if (!(node instanceof Call call) || !(call.op instanceof Name operator)
                || !call.args.keywords.isEmpty()) {
            return false;
        }
        if (operator.id.equals(Constants.UNION_KEYWORD)) {
            if (call.args.positional.isEmpty()) {
                return false;
            }
            return call.args.positional.stream().allMatch(Parser::isTypeExpression);
        }
        if (operator.id.equals(Constants.VECTOR_TYPE_KEYWORD)) {
            return call.args.positional.size() == 1
                    && isTypeExpression(call.args.positional.get(0));
        }
        if (operator.id.equals(Constants.FUNCTION_TYPE_KEYWORD)
                && call.args.positional.size() == 2
                && call.args.positional.get(0) instanceof VectorLiteral parameters
                && isTypeExpression(call.args.positional.get(1))) {
            return parameters.elements.stream().allMatch(Parser::isTypeExpression);
        }
        if (operator.id.equals(Constants.RESULT_TYPE_KEYWORD)) {
            return call.args.positional.size() == 2
                    && call.args.positional.stream().allMatch(Parser::isTypeExpression);
        }
        if (operator.id.equals(Constants.OPTION_TYPE_KEYWORD)) {
            return call.args.positional.size() == 1
                    && isTypeExpression(call.args.positional.get(0));
        }
        return false;
    }


    public static boolean delimType(Node c, String d) {
        return c instanceof Delimeter && ((Delimeter) c).shape.equals(d);
    }


    public static void main(String[] args) throws ParserException {
        Node tree = Parser.parse(args[0]);
        Util.msg(tree.toString());
    }

}
