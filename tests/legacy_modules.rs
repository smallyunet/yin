use std::path::{Path, PathBuf};
use yin::{Engine, Host};

struct Project {
    root: tempfile::TempDir,
}

impl Project {
    fn new() -> Self {
        Self {
            root: tempfile::tempdir().unwrap(),
        }
    }

    fn write(&self, relative: &str, source: &str) -> PathBuf {
        let path = self.root.path().join(relative);
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(&path, source).unwrap();
        path
    }

    fn run(&self, relative: &str) -> Result<String, yin::YinError> {
        Engine::new(Host::default())
            .run_file(self.root.path().join(relative))
            .map(|result| result.value.to_string())
    }
}

fn assert_error(result: Result<String, yin::YinError>, fragments: &[&str]) {
    let actual = result.unwrap_err().to_string();
    assert!(
        fragments.iter().any(|fragment| actual.contains(fragment)),
        "expected one of {fragments:?}, got: {actual}"
    );
}

#[test]
fn selectively_imports_functions_records_and_values() {
    let project = Project::new();
    project.write(
        "math.yin",
        "(module math [Point double origin]\n(record Point [x Int] [y Int])\n(define double (fun ([value Int] [-> Int]) (* value 2)))\n(define origin (Point :x 0 :y 0)))",
    );
    project.write(
        "main.yin",
        "(import \"./math.yin\" [Point double origin])\n(define point (Point :x 2 :y 3))\n[(double 21) origin.x point.y]",
    );
    assert_eq!(project.run("main.yin").unwrap(), "[42 0 3]");
}

#[test]
fn resolves_transitive_relative_imports() {
    let project = Project::new();
    project.write(
        "shared/base.yin",
        "(module base [increment] (define increment (fun ([value Int] [-> Int]) (+ value 1))))",
    );
    project.write(
        "feature/value.yin",
        "(module value [answer] (import \"../shared/base.yin\" [increment]) (define answer (increment 41)))",
    );
    project.write(
        "main.yin",
        "(import \"./feature/value.yin\" [answer])\nanswer",
    );
    assert_eq!(project.run("main.yin").unwrap(), "42");
}

#[test]
fn evaluates_each_module_once_per_program() {
    let project = Project::new();
    project.write(
        "counter.yin",
        "(module counter [next] (define count 0) (define next (fun ([-> Int]) (set! count (+ count 1)) count)))",
    );
    project.write(
        "left.yin",
        "(module left [left] (import \"./counter.yin\" [next]) (define left (fun ([-> Int]) (next))))",
    );
    project.write(
        "right.yin",
        "(module right [right] (import \"./counter.yin\" [next]) (define right (fun ([-> Int]) (next))))",
    );
    project.write(
        "main.yin",
        "(import \"./left.yin\" [left])\n(import \"./right.yin\" [right])\n[(left) (right)]",
    );
    assert_eq!(project.run("main.yin").unwrap(), "[1 2]");
}

#[test]
fn rejects_private_and_conflicting_imports() {
    let project = Project::new();
    project.write(
        "library.yin",
        "(module library [public] (define public 1) (define private 2))",
    );
    project.write(
        "private.yin",
        "(import \"./library.yin\" [private])\nprivate",
    );
    assert_error(project.run("private.yin"), &["export", "private"]);
    project.write(
        "conflict.yin",
        "(define public 0)\n(import \"./library.yin\" [public])\npublic",
    );
    assert_error(project.run("conflict.yin"), &["duplicate", "conflict"]);
}

#[test]
fn rejects_undefined_exports_and_non_module_imports() {
    let project = Project::new();
    project.write("broken.yin", "(module broken [missing] (define present 1))");
    project.write("main.yin", "(import \"./broken.yin\" [missing])");
    assert_error(project.run("main.yin"), &["undefined export", "missing"]);
    project.write("plain.yin", "(define value 1)");
    project.write("plain-main.yin", "(import \"./plain.yin\" [value])");
    assert_error(project.run("plain-main.yin"), &["module file", "module"]);
}

#[test]
fn reports_circular_imports() {
    let project = Project::new();
    project.write(
        "a.yin",
        "(module a [a] (import \"./b.yin\" [b]) (define a b))",
    );
    project.write(
        "b.yin",
        "(module b [b] (import \"./a.yin\" [a]) (define b a))",
    );
    project.write("main.yin", "(import \"./a.yin\" [a])\na");
    assert_error(project.run("main.yin"), &["circular module import"]);
}

#[test]
fn dependency_type_errors_are_checked_before_evaluation() {
    let project = Project::new();
    let dependency = project.write("bad.yin", "(module bad [value] (define value (+ 1 true)))");
    project.write("main.yin", "(import \"./bad.yin\" [value])\nvalue");
    let error = project.run("main.yin").unwrap_err();
    assert!(error.to_string().contains("numeric argument"));
    assert_eq!(
        dependency.file_name(),
        Some(std::ffi::OsStr::new("bad.yin"))
    );
}

#[test]
fn invalid_module_contracts_are_rejected() {
    let project = Project::new();
    let absolute_target = Path::new("/tmp/value.yin");
    project.write(
        "absolute.yin",
        &format!("(import \"{}\" [value])", absolute_target.display()),
    );
    assert_error(project.run("absolute.yin"), &["relative", "import"]);
    project.write("entry.yin", "(module entry [value] (define value 1))");
    assert_error(project.run("entry.yin"), &["module", "import"]);
}
