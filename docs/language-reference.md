# Yin language reference

This is a concise guide to the behavior covered by the current automated test
suite. The normative definition is the
[Yin 0.3 language specification](language-specification.md). Files in
`experiments/` may use older syntax and are not normative; see the
[historical-program classification](historical-programs.md).

## Programs and comments

A file is an implicit sequence of expressions. Its result is the value of the
last expression, or `void` for an empty file. Line comments start with `--`.

## Values

```yin
42
1.5
true
false
"hello"
[1 2 3]
```

The maintained built-in types are `Int`, `Float`, `Bool`, `String`, and `Any`.

## Definitions and assignment

```yin
(define answer 42)
(set! answer 43)
```

Definitions are lexical and redefining a name in the same scope is an error.
Assignment updates the nearest enclosing definition. Assigning an incompatible
type is rejected by the type checker, and assigning an undefined name is an
error in both execution modes.

## Conditionals

```yin
(if (= answer 42) "yes" "no")
```

The condition must have type `Bool`.

## Functions

An unannotated function:

```yin
(define subtract (fun (x y) (- x y)))
(subtract 3 2)
```

An annotated function, including its return type:

```yin
(define factorial
  (fun ([x Int] [-> Int])
    (if (= x 0) 1 (* x (factorial (- x 1))))))
```

Calls use either positional or keyword arguments, never a mixture:

```yin
(subtract 3 2)
(subtract :x 3 :y 2)
```

Every required parameter must be supplied exactly once. Unknown and duplicated
keywords are errors. A parameter may declare `:default`; its expression is
evaluated once, in lexical scope, when the function definition is evaluated.
The call operator and positional arguments evaluate from left to right. Keyword
values also evaluate in source order, independently of parameter order.

Annotations may name a built-in or record type, or use a non-empty union such
as `(U Int Float)`. `Any` is the top type. A return descriptor, when present,
must be the final descriptor in the parameter list.

## Records

```yin
(record Point
  [x Int]
  [y Int :default 0])

(define point (Point :x 10))
```

Record construction uses keyword arguments. Required fields must be supplied;
fields with `:default` may be omitted. Like function defaults, record defaults
are evaluated once when the record definition is evaluated.

Records may inherit fields from one or more previously defined records:

```yin
(record Position [x Int :default 0])
(record NamedPosition (Position) [name String])
```

Inherited fields are appended after locally declared fields. Conflicts between
local fields or multiple parents are rejected. Inheritance establishes nominal,
transitive subtyping. Attribute access, subscripting, and record mutation are
not part of the grammar.

## Primitive operations

- arithmetic: `+`, `-`, `*`, `/`
- numeric comparison: `<`, `<=`, `>`, `>=`, `=`
- boolean operations: `and`, `or`, `not`
- output: `print`
- union type constructor used by the type checker: `U`

`print` accepts zero or more positional arguments and returns `void`.

## Known language gaps

- attribute access, subscripting, and collection mutation are unsupported
- the type system is experimental and is not a formal soundness guarantee
- there is no module system, package manager, REPL, compiler, or runtime system
- several files under `experiments/` represent abandoned syntax designs
