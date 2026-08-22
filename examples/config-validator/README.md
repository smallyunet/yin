# Typed configuration validator

This is a small, real multi-file CLI program rather than an Agent policy demo.
It reads a JSON object from standard input, validates required configuration
keys, supplies a deterministic default for `mode`, and writes one closed JSON
result to standard output.

Build Yin and run a valid configuration:

```bash
printf '%s' '{"host":"localhost","port":"8080"}' | \
  java -jar target/yin-0.19.0.jar --json examples/config-validator/main.yin
```

Expected output:

```json
{"tag":"Valid","config":{"host":"localhost","port":"8080","mode":"development"}}
```

An incomplete configuration returns a typed `Invalid` value with stable key
ordering:

```bash
printf '%s' '{"host":"localhost"}' | \
  java -jar target/yin-0.19.0.jar --json examples/config-validator/main.yin
```

```json
{"tag":"Invalid","missing":["port"],"message":null}
```

The program exercises isolated modules, multi-file type checking, immutable
`Dict` and `Set` values, safe optional lookup, `Result` handling, variants, and
strict JSON boundaries without adding any Java host code.
