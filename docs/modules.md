# Yin modules

Yin 0.18 added file-backed modules with explicit exports, selective imports, and
whole-dependency-graph type checking.

## Declaring a module

One module file contains exactly one `module` form:

```yin
(module math [Point double]
  (record Point [x Int] [y Int])
  (define double
    (fun ([value Int] [-> Int])
      (* value 2))))
```

The module name is diagnostic identity. The square-bracketed export list is the
complete public surface. Every exported name must be defined by the completed
module body; definitions omitted from the list remain private.

## Importing bindings

An ordinary entry program or another module selects public names explicitly:

```yin
(import "./math.yin" [Point double])
(define point (Point :x 21 :y (double 21)))
[point.x point.y]
```

Import paths must be relative, must end in `.yin`, and resolve from the file
containing the import. `..` segments are allowed. The loader canonicalizes the
target, so different relative paths and symbolic links to one file share one
module instance.

## Scope and evaluation

Each module evaluates and type-checks in an isolated lexical scope containing
the standard Yin bindings and its own imports. Imported bindings do not expose
the rest of the module scope. A module is loaded once per program execution, so
two transitive imports share the same exported closures and initialized state.

Imports execute in source order and conflict with an existing local binding.
Circular imports are rejected with the dependency chain. Two different files
cannot declare the same module name in one dependency graph.

Record and variant identity includes the defining source file. Consequently,
same-named nominal types declared by different modules remain distinct even
when values cross module boundaries.

## Type checking and editor behavior

Running the normal type checker on an entry file checks every reachable module:

```bash
java -cp target/yin-0.19.0.jar \
  org.yinwang.yin.TypeChecker examples/modules/main.yin
```

Dependency diagnostics retain the dependency file's source span. The LSP uses
the unsaved source snapshot for the open document and reads imported modules
from their saved filesystem contents.

The browser Playground and REPL do not provide a file-backed module host.
Digest-bound execution modes (`--contract-check`, `--contract-run`,
`--contract-compile`, `--guard`, `--gateway`, and `--approval-request`) reject
modules until the complete dependency graph is included in their program hash.
This prevents an approval or trace from covering only the entry file while an
unbound dependency changes.
