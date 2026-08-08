# Yin language specification

This document defines the normative Yin 0.6 language. Behavior not described
here is unsupported even if a historical file or implementation class suggests
otherwise.

## Lexical grammar

- Source text is UTF-8.
- Whitespace separates tokens.
- A line comment begins with `--` and ends at the next newline.
- Strings are enclosed in `"`. They cannot span lines. A backslash causes the
  lexer to consume the following character without treating it as a delimiter;
  escape sequences are otherwise retained as source content.
- Integers are decimal, binary with `0b`, or hexadecimal with `0x`, with an
  optional leading sign.
- Floats use the syntax accepted by Java `Double.parseDouble` after tokenization.
- `(`, `)`, `[`, and `]` are delimiters. Braces, dots, and `#` subscripts are
  not part of the language.
- A keyword token begins with `:`. All other accepted identifier characters
  form names.

## Grammar

The grammar below uses `{x}` for repetition, `[x]` for an optional grammar
element, and quoted characters for literal syntax.

```ebnf
program        = { expression } ;

expression     = atom
               | vector
               | sequence
               | conditional
               | definition
               | assignment
               | function
               | record-definition
               | field-access
               | call ;

atom           = integer | float | string | name ;
vector         = "[" { expression } "]" ;
sequence       = "(" "seq" { expression } ")" ;
conditional    = "(" "if" expression expression expression ")" ;
definition     = "(" "define" pattern expression ")" ;
assignment     = "(" "set!" pattern expression ")" ;

pattern        = name | vector-pattern ;
vector-pattern = "[" { pattern } "]" ;

function       = "(" "fun" parameter-list expression { expression } ")" ;
parameter-list = "(" { name } ")"
               | "(" { parameter-descriptor } [ return-descriptor ] ")" ;
parameter-descriptor
               = "[" name type-expression [ ":default" expression ] "]" ;
return-descriptor
               = "[" "->" type-expression "]" ;

record-definition
               = "(" "record" name [ parent-list ] { field-descriptor } ")" ;
parent-list    = "(" { name } ")" ;
field-descriptor
               = "[" name type-expression [ ":default" expression ] "]" ;

field-access   = "(" "field" expression keyword ")" ;

type-expression
               = name | "(" "U" type-expression { type-expression } ")" ;

call           = "(" expression { expression } ")"
               | "(" expression { keyword expression } ")" ;
```

Bare and descriptor parameters cannot be mixed. Positional and keyword
arguments cannot be mixed. Keyword names and descriptor properties cannot be
duplicated. The only supported descriptor property is `:default`.

An empty program evaluates to `void`. A source file containing multiple
expressions is an implicit `seq`.

## Evaluation order

Runtime evaluation is deterministic:

1. A sequence evaluates expressions from left to right and returns the last
   result, or `void` when empty.
2. `define` and `set!` evaluate the right-hand side before binding the pattern.
3. `if` evaluates its condition, then exactly one selected branch.
4. A call evaluates its operator first.
5. Positional arguments are evaluated from left to right.
6. Keyword argument values are evaluated in their source order, independent of
   parameter or record-field declaration order.
7. Primitive operations receive their already evaluated arguments.
8. Function bodies evaluate as sequences in a new lexical scope.

Function and record default expressions are evaluated once when their
definition is evaluated, in that definition's lexical environment. Defaults
may be omitted only by a keyword call; positional calls require exact arity.

The type checker visits both branches of an `if`, in source order, and forms
their union even though runtime evaluation selects only one branch.

## Bindings and closures

Definitions create a binding in the current lexical scope. Redefinition in the
same scope is an error. Inner scopes may shadow outer bindings. Assignment
updates the nearest enclosing binding and cannot create a new one.

Functions are lexical closures. Their body resolves free names through the
environment captured when the function was evaluated.

Vector patterns destructure fixed-length vectors recursively. A size mismatch,
duplicate pattern name, or incompatible value is an error.

## Records

A record definition creates a callable runtime constructor and a nominal static
record type. Construction uses keyword arguments. Every required field must be
provided exactly once; defaults fill omitted fields. Actual arguments evaluate
in source order, while the resulting record prints fields in declaration order.

A record may inherit from multiple earlier records. Inherited fields are
appended after local fields in parent-list order. Any field-name conflict among
the child and its parents is an error; overriding is not supported.

Record inheritance defines nominal subtyping: an instance of a child record is
a subtype of every transitive parent record. Records with identical fields but
unrelated names are not subtypes. Anonymous record patterns are structural
internally, but no anonymous record literal syntax is currently supported.

`(field value :name)` evaluates `value` exactly once and reads the named field.
Local and inherited fields are readable. Accessing a missing field or applying
`field` to a non-record value is an error.

Records are immutable after construction. Field mutation and generic
subscripting are not in the grammar.

## Static types

The built-in types are `Int`, `Float`, `Bool`, `String`, `Any`, and `void`.
`void` is produced by effects and is not a source-level annotation name.

Type rules:

- Literal types are their corresponding built-in types.
- `Any` is the top type: every type is a subtype of `Any`, including in return
  positions.
- A vector has a fixed structural type containing one type per element.
- Two vector types are equivalent when they have the same length and equivalent
  element types.
- `(U T1 ... Tn)` is a non-empty, flattened union. Repeated equivalent members
  collapse. A value is a subtype of a union when it is a subtype of any member;
  a union is a subtype of another type when every member is.
- Record types are nominal and follow declared inheritance.
- Field access on a record value has the declared type of that field, including
  inherited fields. Access across a union is valid only when every union member
  is a record with the field; the result is the normalized union of the member
  field types. Access through `Any` has type `Any` and remains runtime-checked.
- Primitive arithmetic accepts `Int` and `Float`; a mixed arithmetic result is
  `Float`.
- An `if` expression has the union of its branch types.

Annotated arguments and return values are checked by subtyping. Unannotated
function result types are inferred from the body at each checked call. A
recursive function must declare a return type. Yin performs no Hindley-Milner
generalization, flow-sensitive refinement, overload resolution, or implicit
conversion beyond mixed numeric primitive results.

## Errors

Syntax, I/O, runtime, and type failures are language diagnostics rather than JVM
termination inside the core. Diagnostics expose a stable code, message, and an
optional source span. See `docs/implementation.md` for the diagnostic codes.
