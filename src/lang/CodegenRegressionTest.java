package lang;

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;

/** Compiles emitted Java and executes its runtime contracts. */
public final class CodegenRegressionTest {
    private static int checks;
    private static Exp p(String text) throws ParseException { return Interpreter.parseString(text); }
    private static void require(boolean ok, String name) {
        checks++;
        if (!ok) throw new AssertionError(name);
    }
    private static void rejects(Runnable action, Class<? extends Throwable> type, String name) {
        try { action.run(); } catch (Throwable error) {
            require(type.isInstance(error), name + ": " + error); return;
        }
        throw new AssertionError(name + ": accepted invalid input");
    }

    public static void main(String[] args) throws Exception {
        Set<String> nonzero = Set.of("x");
        Exp one = p("1");
        for (String name : List.of("class", "bad-name", "Math", "Double"))
            rejects(() -> CodeGenerator.toJavaMethod(name, one, List.of()), IllegalArgumentException.class, "Java identifier");
        rejects(() -> CodeGenerator.toJavaMethod("f", one, List.of("x", "x")), IllegalArgumentException.class, "duplicate params");
        rejects(() -> CodeGenerator.toJavaMethod("f", one, List.of(), nonzero), IllegalArgumentException.class, "lost Java premise");
        rejects(() -> CodeGenerator.toJavaMethod("f", new Symbol("x"), List.of()), IllegalArgumentException.class, "missing Java param");
        rejects(() -> CodeGenerator.toJavaMethod("f", new FunctionCall("unknown", new Const(1)), List.of()),
                UnsupportedOperationException.class, "unsupported Java function");
        rejects(() -> new CodegenStmt(one, "python", "f", List.of()).execute(new Context()),
                IllegalArgumentException.class, "unsupported target");

        Context context = new Context(); context.assumeNonzero("x");
        Exp ratio = context.simplify(p("x/x"), null);
        String javaCode = "public class GeneratedChecks {\n"
                + CodeGenerator.toJavaMethod("ratio", ratio, List.of("x"), nonzero)
                + CodeGenerator.toJavaMethod("identity", p("x"), List.of("x"))
                + CodeGenerator.toJavaMethod("formula", p("sin(x)+cos(x)+ln(exp(x))+x^2+1/2"), List.of("x"))
                + CodeGenerator.toJavaMethod("constant", p("2/3"), List.of()) + "}\n";
        Path directory = Files.createTempDirectory("symsat-codegen-");
        try {
            Path javaSource = directory.resolve("GeneratedChecks.java");
            Files.writeString(javaSource, javaCode);
            require(ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "-d", directory.toString(), javaSource.toString()) == 0, "generated Java compiles");
            try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {directory.toUri().toURL()})) {
                Class<?> generated = loader.loadClass("GeneratedChecks");
                java.lang.reflect.Method method = generated.getMethod("ratio", double.class);
                for (double value : new double[] {2.0, -2.0, Double.MIN_VALUE})
                    require((Double) method.invoke(null, value) == 1.0, "Java accepts finite nonzero " + value);
                for (double value : new double[] {0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                    try { method.invoke(null, value); throw new AssertionError("Java accepted " + value); }
                    catch (InvocationTargetException error) {
                        require(error.getCause() instanceof IllegalArgumentException, "Java rejects " + value);
                    }
                }
                require((Double) generated.getMethod("identity", double.class).invoke(null, 0.0) == 0.0,
                        "zero allowed without nonzero premise");
                require(Math.abs((Double) generated.getMethod("formula", double.class).invoke(null, 1.0)
                        - (Math.sin(1.0) + Math.cos(1.0) + 2.5)) < 1e-12, "Java numeric formula");
                require(Math.abs((Double) generated.getMethod("constant").invoke(null) - 2.0/3.0) < 1e-15,
                        "Java constant function");
            }
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
        System.out.println("PASS: " + checks + " code generation checks (compiled Java)");
    }

}
