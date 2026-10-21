package lang;

import java.math.BigInteger;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;

public final class JavaEmissionRegressionTest {
    private static int checks;
    private static void require(boolean ok,String name) {checks++;if(!ok)throw new AssertionError(name);}
    private static Rational ratio(BigInteger n,BigInteger d) {return new Rational(n,d);}
    private static void rejects(Rational value,String name) {
        try {CodeGenerator.toJavaMethod("f",new Const(value),List.of());throw new AssertionError(name);}
        catch(IllegalArgumentException expected){checks++;}
    }
    public static void main(String[] args) throws Exception {
        BigInteger one=BigInteger.ONE;
        Map<String,Rational> constants=new LinkedHashMap<>();
        constants.put("third",Rational.of("1/3"));
        constants.put("negative",Rational.of("-7/3"));
        constants.put("tieEven",ratio(one.shiftLeft(53).add(one),one.shiftLeft(53)));
        constants.put("aboveTie",ratio(one.shiftLeft(100).add(one.shiftLeft(47)).add(one),one.shiftLeft(100)));
        constants.put("tieOdd",ratio(one.shiftLeft(53).add(BigInteger.valueOf(3)),one.shiftLeft(53)));
        constants.put("smallest",ratio(one,one.shiftLeft(1074)));
        constants.put("minNormal",ratio(one,one.shiftLeft(1022)));
        constants.put("largest",ratio(one.shiftLeft(1024).subtract(one.shiftLeft(971)),one));
        BigInteger huge=BigInteger.TEN.pow(400);
        constants.put("largeRatio",ratio(huge.add(one),huge.subtract(one)));
        double[] expected={1.0/3.0,-7.0/3.0,1.0,Math.nextUp(1.0),Math.nextUp(Math.nextUp(1.0)),
                Double.MIN_VALUE,Double.MIN_NORMAL,Double.MAX_VALUE,1.0};
        rejects(ratio(one.shiftLeft(1024),one),"overflow rejection");
        rejects(ratio(one,one.shiftLeft(1075)),"underflow-to-zero rejection");
        rejects(ratio(one.shiftLeft(1024).subtract(one.shiftLeft(970)),one),"rounded overflow rejection");

        Exp shared=Interpreter.parseString("(sin(x)+x^2)*(sin(x)+x^2)+(sin(x)+x^2)");
        String repeated=CodeGenerator.toJavaMethod("shared",shared,List.of("x"));
        require(repeated.contains("double _symsat"),"temporary emitted");
        require(repeated.split("Math.sin",-1).length==2,"sin evaluated once");
        require(repeated.split("Math.pow",-1).length==2,"power evaluated once");
        require(!CodeGenerator.toJavaMethod("plain",Interpreter.parseString("x+1"),List.of("x")).contains("double _symsat"),
                "no needless temporary");
        require(repeated.equals(CodeGenerator.toJavaMethod("shared",shared,List.of("x"))),"deterministic CSE source");
        StringBuilder source=new StringBuilder("public class EmittedMath {\n").append(repeated);
        for(Map.Entry<String,Rational> entry:constants.entrySet())
            source.append(CodeGenerator.toJavaMethod(entry.getKey(),new Const(entry.getValue()),List.of()));
        source.append("}\n");
        Path directory=Files.createTempDirectory("symsat-java-emission-");
        try {
            Path file=directory.resolve("EmittedMath.java");Files.writeString(file,source);
            require(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",directory.toString(),file.toString())==0,
                    "CSE and boundary constants compile");
            try(URLClassLoader loader=new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()})) {
                Class<?> generated=loader.loadClass("EmittedMath");
                int i=0;
                for(String name:constants.keySet()) {
                    double value=(Double)generated.getMethod(name).invoke(null);
                    require(Double.doubleToLongBits(value)==Double.doubleToLongBits(expected[i++]),"exact rounded constant "+name);
                }
                for(double x:new double[]{-3,-1,-0.0,0,0.25,1,3}) {
                    double term=Math.sin(x)+Math.pow(x,2);
                    double value=(Double)generated.getMethod("shared",double.class).invoke(null,x);
                    require(Double.doubleToLongBits(value)==Double.doubleToLongBits(term*term+term),"CSE value at "+x);
                }
            }
        } finally {
            try(java.util.stream.Stream<Path> files=Files.walk(directory)) {
                for(Path file:(Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator)Files.delete(file);
            }
        }
        System.out.println("PASS: "+checks+" Java CSE and constant conversion checks");
    }
}
