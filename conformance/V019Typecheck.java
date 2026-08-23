import org.yinwang.yin.TypeChecker;

/** Test-only adapter exposing the frozen v0.19 type checker as a process exit status. */
public final class V019Typecheck {
    private V019Typecheck() { }

    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            System.err.println("usage: V019Typecheck <program.yin>");
            System.exit(2);
        }
        try {
            new TypeChecker(arguments[0]).typecheck(arguments[0]);
        } catch (RuntimeException error) {
            System.err.println(error.getMessage());
            System.exit(1);
        }
    }
}
