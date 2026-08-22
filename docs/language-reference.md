# Yin language reference

This is a concise guide to the behavior covered by the current automated test
suite. The normative definition is the
[Yin 0.18 language specification](language-specification.md). Files in
`experiments/` may use older syntax and are not normative; see the
[historical-program classification](historical-programs.md).

## Programs and comments

A file is an implicit sequence of expressions. Its result is the value of the
last expression, or `void` for an empty file. Line comments start with `--`.

## Modules and imports

A module file declares one isolated body and its complete public surface:

```yin
(module math [double]
  (define double (fun ([value Int] [-> Int]) (* value 2))))
```

An entry file or another module imports selected public bindings:

```yin
(import "./math.yin" [double])
(double 21)
```

Import paths are relative to the importing file and must end in `.yin`. Every
reachable module is type-checked. Modules initialize once per execution,
private bindings remain inaccessible, cycles and binding conflicts are errors,
and same-named records or variants from different modules remain nominally
distinct. See the [module guide](modules.md).

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

## Explicit results

Expected failure is represented with an immutable, typed `Result` rather than
an exception or sentinel value:

```yin
(define lookup
  (fun ([available Bool] [-> (Result Int String)])
    (if available (ok 42) (err "unavailable"))))

(match (lookup true)
  [(Ok value) value]
  [(Err _) 0])
```

`(ok value)` has precise type `(Ok T)` and `(err error)` has `(Err E)`.
Both are accepted by a compatible `(Result T E)` annotation. Matching a Result
must cover both variants, and each pattern binds only its typed payload.

## Structured contracts

Closed variants model agent decisions without stringly typed tags:

```yin
(variant Decision
  [Approve [reason String]]
  [Reject [reason String]]
  [NeedsInput [question String]])
```

`(Option T)` uses `(some value)` and `none`, with exhaustive `(Some value)` and
`(None)` patterns. Typed boundaries use `(decode-json Request text)`,
`(encode-json value)`, and `(json-schema Request)`. Decode and encode return
`Result`; boundary errors expose `code`, `path`, and `message` fields.

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

## Ordered policies

Use `policy` for a typed decision function that should read in evaluation order:

```yin
(policy review
  ([request ReviewRequest] [-> Decision])
  (when (= request.risk "blocked")
    (Reject :reason "blocked by policy"))
  (when (> request.amount 10000)
    (NeedsApproval :reason "amount requires approval"))
  (otherwise
    (Approve :reason "within policy")))
```

The first matching `when` wins. Every policy requires one or more rules and one
final `otherwise`; conditions must be `Bool`, and every outcome is checked
against the declared return type. Policies lower to ordinary typed functions,
so calls, lexical scope, and evaluation behavior remain unchanged. See the
[policy guide](policies.md).

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
as `(U Int Float)`. `(Vector Int)` describes an arbitrary-length immutable
vector of integers, while `(Fn [Int] String)` describes a positional function
from `Int` to `String`. `(Result Int String)` describes an explicit success or
failure, and `(Option String)` describes present or absent text. `Any` is the top type. A return descriptor, when
present, must be the final descriptor in the parameter list.

## Pattern matching

`match` evaluates one target and selects the first matching clause:

```yin
(match (parse-int "42")
  [(Int value) (+ value 1)]
  [(Bool _) 0])
```

Patterns support literals, `_`, bindings, fixed vectors, built-in type
narrowing, and positional record destructuring. Matches must be exhaustive;
record and built-in type patterns can cover the members of a union, while
`Ok` and `Err` patterns cover the variants of a Result.
`Some` and `None` cover an Option, while every named case covers a variant.

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
transitive subtyping.

Read immutable local or inherited fields with `field`:

```yin
(define point (Point :x 10))
(field point :x)
point.x
```

`point.x` is concise syntax for the same immutable access. Dotted chains such as
`request.account.owner` are evaluated from left to right.

The target is evaluated once. The type checker returns the field's precise
type. A union target is accepted only when every member exposes the field;
access through `Any` remains `Any` and is checked at runtime.

## Primitive operations

- arithmetic: `+`, `-`, `*`, `/`
- numeric comparison: `<`, `<=`, `>`, `>=`, `=`
- boolean operations: `and`, `or`, `not`
- explicit result constructors: `ok`, `err`
- optional values: `some`, `none`
- structured JSON: `decode-json`, `encode-json`, `json-schema`
- immutable vectors: `length`, `at`, `append`
- vector processing: `map`, `filter`, `fold`, `range`, `slice`, `reverse`,
  `contains`
- strings: `string-length`, `concat`, `substring`, `split`, `join`, `trim`,
  `to-string`, `parse-int`, `parse-float`
- host input: `args`, `read-all`, `read-text`
- output: `print`
- union type constructor used by the type checker: `U`
- result type constructor used by the type checker: `Result`

`print` accepts zero or more positional arguments and returns `void`.

Vector operations retain fixed structural types:

```yin
(define values (append [1 "two"] [true]))
(length values) -- 3 : Int
(at values 1)   -- "two" : String
```

A dynamic `at` index produces the union of all possible element types. Vector
indices are zero-based and checked both statically when possible and at
runtime. `append` constructs a new vector and does not modify either input.

Higher-order operations accept annotated source functions:

```yin
(define doubled
  (map [1 2 3]
    (fun ([value Int] [-> Int]) (* value 2))))
(fold doubled 0
  (fun ([total Int] [value Int] [-> Int]) (+ total value)))
```

String escapes include `\\n`, `\\r`, `\\t`, `\\"`, `\\\\`, and `\\u` followed
by four hexadecimal digits. Numeric parsing returns either the requested number
or `false`, making failure explicit through a union and `match`.

The CLI exposes arguments after the source filename through `args`:

```bash
java -jar yin-0.18.0.jar examples/cli/parse-values.yin 10 bad 32
```

`read-all` reads standard input. `read-text` reads a UTF-8 file in the CLI but
is deliberately unavailable in the browser runtime.

For structured pipelines, `--json` reserves stdout for the final raw JSON:

```bash
java -jar yin-0.18.0.jar --json program.yin < request.json
```

The program must return `String` or `(Result String E)`. `Ok String` is
unwrapped, program `print` output is routed to stderr, and an `Err` payload is
JSON-encoded with a non-zero process status.

## Typed tools and capabilities

Declare a host tool with source-owned contracts and authority metadata:

```yin
(tool assess-risk RiskRequest RiskAssessment RiskFailure
  :capability "risk.read"
  :effect :read
  :approval false
  :idempotent true
  :open-world false)
(invoke assess-risk (RiskRequest :amount 42))
```

`invoke` returns `(Result Output (U Error ToolError))`. The host must install
the implementation; missing authority, denied approval, transport failures,
and contract-invalid output are ordinary `ToolError` values. Destructive tools
must require approval. Inspect declarations without executing source using:

```bash
java -jar yin-0.18.0.jar --capabilities program.yin
```

Run a program against the root-confined reference host and record a trace with
`--guard`; verify and reproduce the final result without invoking tools with
`--replay`. The complete command contract and host JSON format are documented
in the [reference policy runtime guide](policy-runtime.md).

## Known language gaps

- record mutation and generic subscript syntax are unsupported
- the type system is experimental and is not a formal soundness guarantee
- there is no package manager or general-purpose native compiler
- `portable-bytecode-v1` compiles a deliberately restricted decision subset to
  `.ybc` for the fuel-metered Rust VM; it is not a hostile-process sandbox
- the LSP currently provides diagnostics and formatting, but not semantic
  navigation or completion
- several files under `experiments/` represent abandoned syntax designs
