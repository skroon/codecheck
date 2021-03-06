package com.horstmann.codecheck;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AccessControlException;
import java.security.Permission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.runner.notification.Failure;

public class JavaLanguage implements Language {
    /*
     * (non-Javadoc)
     * 
     * @see com.horstmann.codecheck.Language#isSource(java.nio.file.Path)
     */
    private static String appClassPath = System.getProperty("java.class.path");
    
    public String getExtension() { return "java"; };
    
    @Override
    public boolean isUnitTest(Path fileName) {
        return fileName != null && fileName.toString().matches(".*Test[0-9]*.java");
    }

    private static Pattern mainPattern = Pattern
            .compile("public\\s+static\\s+void\\s+main\\s*\\(\\s*String(\\s*\\[\\s*\\]\\s*\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*|\\s+\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*\\s*\\[\\s*\\])\\s*\\)");

    /*
     * (non-Javadoc)
     * 
     * @see com.horstmann.codecheck.Language#isMain(java.nio.file.Path,
     * java.nio.file.Path)
     */
    @Override
    public boolean isMain(Path p) {
        if (!isSource(p))
            return false;
        String contents = Util.read(p);
        return contents != null && mainPattern.matcher(contents).find();
    }

    public String moduleOf(Path path) {
        String name = Util.removeExtension(path); // drop .java
        return name.replace(FileSystems.getDefault().getSeparator(), ".");
    }

    public Path pathOf(String moduleName) {
        Path p = FileSystems.getDefault().getPath("", moduleName.split("[.]"));
        Path parent = p.getParent();
        if (parent == null)
            return FileSystems.getDefault().getPath(moduleName + ".java");
        else
            return parent.resolve(p.getFileName().toString() + ".java");
    }

    @Override
    public String compile(List<Path> sourceFiles, Path dir) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        OutputStream outStream = new ByteArrayOutputStream();
        OutputStream errStream = new ByteArrayOutputStream();
        final String classPath = buildClasspath(dir);

        int result;
        Path mainFile = sourceFiles.get(0);
        if (classPath.length() == 0) {
            result = compiler.run(null, outStream, errStream, "-sourcepath",
                    dir.toString(), "-d", dir.toString(),
                    dir.resolve(mainFile).toString());
        } else {
            result = compiler.run(null, outStream, errStream, "-sourcepath",
                    dir.toString(), "-d", dir.toString(),
                    dir.resolve(mainFile).toString(), "-cp",
                    classPath.toString());
        }
        
        if (result != 0) {
            return errStream.toString();
        }
        else return null;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.horstmann.codecheck.Language#run(java.lang.String,
     * java.nio.file.Path, java.lang.String, java.lang.String, int)
     */
    @Override
    @SuppressWarnings("deprecation")
    public String run(final Path mainFile, Set<Path> dependentFiles, final Path classpathDir,
            String args, String input, int timeoutMillis, int maxOutputLen, boolean interleaveio) throws IOException,
            ReflectiveOperationException {
        InputStream oldIn = System.in;
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        if (input == null)
            input = "";
        final ByteArrayOutputStream newOut = new ByteArrayOutputStream();
        final PrintStream newOutPrint = new PrintStream(newOut);
        System.setIn(new ByteArrayInputStream(input.getBytes("UTF-8")) {
            private boolean firstInLine = true;
            
            public int available() {
                return 0;
            }
            
            public void echo(int c) {
                if (firstInLine) {
                    for (byte b : "〈".getBytes(StandardCharsets.UTF_8))
                        newOut.write(b);
                    firstInLine = false;
                }
                if (c == '\n' || c == -1) { 
                    for (byte b : "〉\n".getBytes(StandardCharsets.UTF_8))
                        newOut.write(b);                    
                    firstInLine = true;
                }
                else
                    newOut.write((char) c);
            }

            public int read() {
                int c = super.read();
                echo(c);
                return c;
            }

            public int read(byte[] b) {
                return read(b, 0, b.length);
            }

            public int read(byte[] b, int off, int len) {
                // int r = super.read(b, off, len);
                if (len == 0 || off >= b.length)
                    return 0;
                int r = 0;
                int c = /*super.*/read();
                if (c == -1)
                    return -1;
                boolean done = false;
                while (!done) {
                    b[off + r] = (byte) c;
                    r++;
                    if (c == '\n')
                        done = true;
                    else {
                        c = /*super.*/read();
                        if (c == -1)
                            done = true;
                    }
                }
                /*
                if (r != -1) {
                    newOut.write(b, off, r);
                }
                */
                return r;
            }
        });

        String result = "";
        System.setOut(newOutPrint);
        System.setErr(newOutPrint);
        final URLClassLoader loader = buildClassLoader(classpathDir);
        try {

            final AtomicBoolean done = new AtomicBoolean(false);

            final String[] argsArray;
            if (args == null || args.trim().equals(""))
                argsArray = new String[0];
            else
                argsArray = args.trim().split("\\s+");
            loader.setDefaultAssertionStatus(true);

            final Thread mainmethodThread = new Thread() {
                public void run() {
                    try {
                        Class<?> klass = loader.loadClass(classNameOfFile(mainFile));
                        final Method mainmethod = klass.getMethod("main",
                                String[].class);
                        mainmethod.invoke(null, (Object) argsArray);
                    } catch (InvocationTargetException ex) {
                        Throwable cause = ex.getCause();
                        if (cause instanceof AccessControlException
                                && cause.getMessage()
                                        .startsWith("access denied (\"java.lang.RuntimePermission\" \"exitVM.")) {
                            // do nothing
                        } else if (cause == null)
                            ex.printStackTrace(newOutPrint);
                        else
                            cause.printStackTrace(newOutPrint);
                    } catch (Throwable t) {
                        t.printStackTrace(newOutPrint);
                    }
                    done.set(true);
                }
            };

            mainmethodThread.start();

            try {
                mainmethodThread.join(timeoutMillis);
            } catch (InterruptedException e) {
            }
            result = newOut.toString("UTF-8");
            if (result.length() > maxOutputLen) result = result.substring(0, maxOutputLen) + "\n...\nRemainder truncated\n";
            if (!done.get()) {
                if (!result.endsWith("\n"))
                    result += "\n";
                result += "Timed out after "
                        + (timeoutMillis >= 2000 ? timeoutMillis / 1000
                                + " seconds" : timeoutMillis + " milliseconds");
                mainmethodThread.stop();
            }
        } finally {
            System.setIn(oldIn);
            System.setOut(oldOut);
            System.setErr(oldErr);
            // System.setSecurityManager(null);
            loader.close();
        }
        return result;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.horstmann.codecheck.Language#writeTester(java.nio.file.Path,
     * java.nio.file.Path, java.nio.file.Path, java.util.List, java.lang.String,
     * java.util.List)
     */
    @Override
    public List<Path> writeTester(Path solutionDir, Path workDir, Path file,
            List<Calls.Call> calls)
            throws IOException {
        String className = moduleOf(file);
        List<String> lines = Util.readLines(solutionDir.resolve(file));
        int i = 0;
        while (i < lines.size() && !lines.get(i).contains(className))
            i++;
        if (i == lines.size())
            throw new CodeCheckException("Can't find class " + className
                    + " for inserting CALL in " + file);
        lines.set(
                i,
                lines.get(i).replace("class " + className,
                        "class " + className + "CodeCheck"));
        i = lines.size() - 1;
        while (i >= 0 && !lines.get(i).trim().equals("}"))
            i--;
        if (i == -1)
            throw new CodeCheckException("Can't find } for inserting CALL in "
                    + file);
        // Insert main here
        lines.add(i++, "    public static void main(String[] args) throws Exception");
        lines.add(i++, "    {");
        lines.add(i++, "        " + className + " obj1 = new " + className
                + "();");
        lines.add(i++, "        " + className + "CodeCheck obj2 = new "
                + className + "CodeCheck();");
        for (int k = 0; k < calls.size(); k++) {
            Calls.Call call = calls.get(k);
            boolean isStatic = call.modifiers.contains("static");
            lines.add(i++, "        if (args[0].equals(\"" + (k + 1) + "\"))");
            lines.add(i++, "        {");
            lines.add(i++, "            Object expected = "
                    + (isStatic ? className + "CodeCheck." : "obj2.") + call.name + "(" + call.args
                    + ");");
            lines.add(i++,
                    "            System.out.println(_toString(expected));");
            lines.add(i++, "            Object actual = "
                    + (isStatic ? className : "obj1") + "." + call.name + "("
                    + call.args + ");");
            lines.add(i++, "            System.out.println(_toString(actual));");
            lines.add(
                    i++,
                    "            System.out.println(java.util.Objects.deepEquals(actual, expected));");
            lines.add(i++, "        }");
        }
        lines.add(i++, "    }");
        lines.add(i++, "    private static String _toString(Object obj)");
        lines.add(i++, "    {");
        lines.add(i++, "      if (obj == null) return \"null\";");
        lines.add(i++, "      if (obj instanceof Object[])");
        lines.add(i++,
                "         return java.util.Arrays.deepToString((Object[]) obj);");
        lines.add(i++, "      if (obj.getClass().isArray())");
        lines.add(
                i++,
                "         try { return (String) java.util.Arrays.class.getMethod(\"toString\", obj.getClass()).invoke(null, obj); }");
        lines.add(i++, "         catch (Exception ex) {}");
        lines.add(i++, "      return obj.toString();");
        lines.add(i++, "    }");

        // expected == null ? null : expected instanceof Object[] ?
        // java.util.Arrays.deepToString((Object[]) expected) :
        // expected.getClass().isArray() ? java.util.Arrays.toString(expected) :
        // expected
        Path p = pathOf(className + "CodeCheck");
        Files.write(workDir.resolve(p), lines,
                StandardCharsets.UTF_8);
        List<Path> testFiles = new ArrayList<>();
        testFiles.add(p);
        return testFiles;
    }
    
    private static String patternString = ".*\\S\\s+(?<name>\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)\\s*=\\s*(?<rhs>[^;]+);.*";
    private static Pattern pattern = Pattern.compile(patternString);

    /*
     * Used to filter for jar files.
     */
    private static FilenameFilter jarFilter = new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
            return name.endsWith(".jar");
        }
    };

    /*
     * (non-Javadoc)
     * 
     * @see com.horstmann.codecheck.Language#variablePattern()
     */
    @Override
    public Pattern variablePattern() {
        return pattern;
    }

    /**
     * Builds the classpath argument using the jars found in dir.
     * 
     * @param dir
     *            Path in which to search for jars.
     * @return The appropriate classpath argument.
     * @throws URISyntaxException 
     * @throws MalformedURLException 
     */
    private String buildClasspath(Path dir) {
        StringBuilder classPath = new StringBuilder();
        boolean isFirst = true;
        for (File currentFile : dir.toFile().listFiles(jarFilter)) {
            if (isFirst) {
                isFirst = false;
            } else {
                classPath.append(File.pathSeparatorChar);
            }
            classPath.append(currentFile.getAbsolutePath());
        }
        if (appClassPath.length() > 0) {
            classPath.append(File.pathSeparatorChar);
            classPath.append(appClassPath);
        }
            
        // Add the JAR files on the class path with which the checker was
        // launched (JUnit etc.)
        /*
        for (URL url : ((URLClassLoader) getClass().getClassLoader()).getURLs()) {
            String urlString = url.toString();
            if (urlString.startsWith("file:") && urlString.endsWith(".jar")) {
                try {
                    Path p = Paths.get(new URL(urlString).toURI());
                    // This works with file:///C:/... URLs in Windows
    
                    if (isFirst) {
                        isFirst = false;
                    } else {
                        classPath.append(File.pathSeparatorChar);
                    }
                    classPath.append(dir.resolve(p).toString());
                } catch (MalformedURLException | URISyntaxException e) {
                    // We tried...
                }
            }
        }
        */

        return classPath.toString();
    }

    /**
     * Builds a class loader with all JAR files on a given directory, as well as
     * the directory itself
     * 
     * @param dir
     *            a directory with class and JAR files
     * @return a class loader that can load all the classes, using the JARs
     * @throws MalformedURLException
     */
    private URLClassLoader buildClassLoader(Path dir)
            throws MalformedURLException {
        // Adds all of the user jars to URLClassLoader.
        final List<URL> jars = getJarFilePaths(dir);
        jars.add(dir.toFile().toURI().toURL());
        return new URLClassLoader(jars.toArray(new URL[jars.size()]));
    }

    /**
     * Gets a collection of the absolute file paths for each jar.
     * 
     * @param dir
     *            Path in which to search for jars.
     * @return Iterable collection of absolute file paths for user jars.
     */
    private List<URL> getJarFilePaths(Path dir) {
        List<URL> returnValue = new ArrayList<URL>();
        for (File currentFile : dir.toFile().listFiles(jarFilter)) {
            try {
                returnValue.add(currentFile.getAbsoluteFile().toURI().toURL());
            } catch (MalformedURLException e) {
                // Save to ignore given that we are reading from the file
                // system.
            }
        }

        return returnValue;
    }

    @Override
    public boolean accept(Path file, Path dir, 
            Report report, Score score) throws IOException {
        if (file.getFileName().toString().equals("checkstyle.xml")) {
            report.header("checkStyle", "CheckStyle");
            for (Path p : Util.getDescendantFiles(dir)) {
                if (isSource(p)) {
                    String result = runCheckStyle(dir.resolve(p));
                    report.output(p.getFileName().toString() + 
                            (result.length() == 0 ? ": Ok" : ":\n" + result));
                    score.pass(result.length() == 0, report);
                }
            }
            return true;
        }
        return false;
    }

    private String classNameOfFile(Path fileName) {
        String className = fileName.toString();
        className = className.substring(0, className.lastIndexOf("."));
        className = className.replace('/', '.');
        return className;
    }
    
    @Override
    @SuppressWarnings("deprecation")
    public void runUnitTest(Path mainFile, Set<Path> dependentFiles, Path dir, Report report,
            Score score,  int timeoutMillis, int maxOutputLen) {
        report.run(mainFile.toString());
        String errorReport = compile(Collections.singletonList(mainFile), dir); 
        if (errorReport == null) {
            try {
                PrintStream oldOut = System.out;
                PrintStream oldErr = System.err;
                try (URLClassLoader loader = buildClassLoader(dir)) {
                    loader.setDefaultAssertionStatus(true);
                    Class<?> c = loader.loadClass(classNameOfFile(mainFile));
                    final AtomicBoolean done = new AtomicBoolean(false);                    
                    org.junit.runner.Result resultHolder[] = new org.junit.runner.Result[1];
                    final ByteArrayOutputStream newOut = new ByteArrayOutputStream();
                    final PrintStream newOutPrint = new PrintStream(newOut);
                    System.setOut(newOutPrint);
                    System.setErr(newOutPrint);
                    
                    final Thread junitThread = new Thread() {
                        public void run() {
                            try {
                                resultHolder[0] = org.junit.runner.JUnitCore.runClasses(c);
                            } catch (Throwable t) {
                                if (t instanceof AccessControlException
                                        && t.getMessage()
                                                .equals("access denied (\"java.lang.RuntimePermission\" \"exitVM.0\")")) {
                                    // do nothing
                                } else 
                                    t.printStackTrace(newOutPrint);
                            }
                            done.set(true);
                        }
                    };

                    junitThread.start();
                    try {
                        junitThread.join(timeoutMillis);
                    } catch (InterruptedException e) {
                    }
                    if (!done.get()) {
                        newOutPrint.append("Timed out after "
                                + (timeoutMillis >= 2000 ? timeoutMillis / 1000
                                        + " seconds" : timeoutMillis + " milliseconds"));
                        junitThread.stop();
                        String output = newOut.toString("UTF-8");
                        if (output.length() > maxOutputLen)
                            output = output.substring(0, maxOutputLen) + "\n...\nRemainder truncated\n";
                        report.output(output);
                    } else {                    
                        org.junit.runner.Result result = resultHolder[0];
                        int pass = result.getRunCount() - result.getFailureCount();
                        String output = newOut.toString("UTF-8");
                        if (output.length() > 0 && !output.endsWith("\n")) output += "\n";
                        report.output(output + "Pass: " + pass + "\nFail: "
                            + result.getFailureCount());
                        for (Failure failure : result.getFailures()) {
                            report.output("Failed: " + failure.getDescription().getDisplayName().trim());
                            report.output(failure.getMessage());
                            if (failure.getException() != null) report.output(failure.getTrace());
                        }
                        score.add(pass, result.getRunCount(), report);
                    }
                } finally {
                    System.setOut(oldOut);
                    System.setErr(oldErr);                
                }
            } catch (Throwable t) {
                report.systemError(t);
            }
        }
        else {
            if (errorReport.trim().equals(""))
                report.error("Error compiling " + mainFile);
            else
                report.error(errorReport);
            score.setInvalid();
        }

    }

    static class ExitException extends SecurityException {
    }

    public static String runCheckStyle(final Path javaFile) {
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        final ByteArrayOutputStream newOut = new ByteArrayOutputStream();
        final PrintStream newOutPrint = new PrintStream(newOut);
        System.setOut(newOutPrint);
        System.setErr(newOutPrint);

        // Annoyingly, checkstyle calls System.exit

        final SecurityManager oldManager = System.getSecurityManager();
        System.setSecurityManager(new SecurityManager() {
            @Override
            public void checkExit(int status) {
                throw new ExitException();
            }

            @Override
            public void checkPermission(Permission perm) {
                // oldManager.checkPermission(perm);
            }

            @Override
            public void checkPermission(Permission perm, Object context) {
                // oldManager.checkPermission(perm, context);
            }
        });
        String exceptionMessage = null;
        try {
            String[] args = new String[3];
            args[0] = "-c";
            args[1] = "checkstyle.xml"; // CheckStyle file
            args[2] = javaFile.toAbsolutePath().toString(); // Java file will be
                                                            // checked
            com.puppycrawl.tools.checkstyle.Main.main(args);
        } catch (ExitException e) {
        } catch (Exception e) {
            exceptionMessage = e.getMessage();
        } finally {
            System.setSecurityManager(oldManager);
            newOutPrint.close();
            System.setOut(oldOut);
            System.setOut(oldErr);
        }

        String result;
        try {
            result = newOut.toString("UTF-8");
        } catch (UnsupportedEncodingException e) {
            result = e.getMessage();
        }
        String header = "Starting audit...\n";
        if (result.startsWith(header))
            result = result.substring(header.length());
        String footer = "Audit done.\n";
        if (result.endsWith(footer))
            result = result.substring(0, result.length() - footer.length());
        if (exceptionMessage != null) result = exceptionMessage + "\n" + result;
        return result;
    }
    
    @Override
    public List<String> modifiers(String declaration) {
        // TODO: Use regexp--what if it's '\tstatic'?
        if (declaration.contains(" static ")) return Collections.singletonList("static"); else return Collections.emptyList();
    }
    
    public Interleave echoesStdin() { return Interleave.ALWAYS; }

    private static Pattern ERROR_PATTERN = Pattern.compile(".+/(?<file>[^/]+\\.java):(?<line>[0-9]+): error: (?<msg>.+)");
    @Override public Pattern errorPattern() { return ERROR_PATTERN; }        
}
