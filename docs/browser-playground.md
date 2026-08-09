# Browser playground

The [Yin Playground](https://smallyunet.github.io/yin/) runs the real Java
language implementation entirely inside the browser. GitHub Pages only serves
static HTML, CSS, JavaScript, and source-map files.

## Architecture

TeaVM compiles `BrowserBridge` and the reachable Yin implementation to a
minified JavaScript runtime. The page sends source text to a dedicated Web
Worker, which calls the exported bridge methods and returns JSON containing the
runtime value, inferred type, captured `print` output, or structured diagnostic.

The UI terminates and recreates the worker when a request exceeds 1.5 seconds.
This prevents an infinite Yin computation from freezing the page. Termination
also resets the interactive session.

## Build locally

```bash
./mvnw -Pbrowser -DskipTests package
python3 -m http.server 8080 --directory site
```

Open `http://localhost:8080`. The generated `site/runtime/` directory is ignored
by Git because GitHub Actions recreates it for every deployment.

## Browser API

TeaVM exports four functions:

- `yinEvaluate(source)` evaluates a submission in a persistent session
- `yinFormat(source)` returns canonical source formatting
- `yinReset()` discards the current runtime and type environments
- `yinSetInput(text)` replaces the controlled text returned by `read-all` and
  starts a fresh session

The browser deliberately rejects `read-text`, because static pages do not have
an ambient filesystem capability. Pure collection, string, and match semantics
are identical to the JVM runtime.

Each string-returning function returns JSON. Diagnostics include the stable Yin
error code and, when available, one-based line and column plus source offsets.

## Deployment

`.github/workflows/pages.yml` compiles the JavaScript runtime, validates the
static JavaScript files, executes an evaluate/diagnostic/format smoke test
against the generated TeaVM artifact, uploads `site/` as a Pages artifact, and
deploys it to the `github-pages` environment after every push to `main`.
