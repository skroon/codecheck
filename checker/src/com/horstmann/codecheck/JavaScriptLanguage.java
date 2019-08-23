package com.horstmann.codecheck;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.horstmann.codecheck.Calls.Call;

/*
 * If we want to use Nashorn instead of running jjs, 
 * 1. need to set scripting flag: https://wiki.openjdk.java.net/display/Nashorn/Nashorn+jsr223+engine+notes
 * 2. runs by default in sandbox: https://wiki.openjdk.java.net/display/Nashorn/Nashorn+script+security+permissions
 * 3. Use ScriptContext to set stdin, stdout, stderr
 */

public class JavaScriptLanguage implements Language {

    @Override
    public String getExtension() {
        return "js";
    }
        
    @Override
    public List<Path> writeTester(Path sourceDir, Path targetDir, Path file,
            List<Call> calls) throws IOException {
        
        String moduleName = moduleOf(file);
        Set<String> functionNames = new TreeSet<>();
        for (Calls.Call call : calls) functionNames.add(call.name);
        List<String> lines = Util.readLines(sourceDir.resolve(file));
        lines.add(0, "var solutionFunctions = function(){");
        lines.add("return {");
        for (String functionName : functionNames)
            lines.add(functionName + ": " + functionName);
        lines.add("}}()");
        for (int k = 0; k < calls.size(); k++) {
            Calls.Call call = calls.get(k);
            lines.add("if (arguments[0] === '" + (k + 1) + "') {");
            lines.add("var actual = " + call.name + "(" + call.args + ")");
            lines.add("var expected = solutionFunctions." + call.name + "(" + call.args + ")");
            lines.add("print(JSON.stringify(expected))");
            lines.add("print(JSON.stringify(actual))");
            lines.add("print(codecheck.deepEquals(actual, expected))");
            lines.add("}");            
        }
        Files.write(targetDir.resolve(pathOf(moduleName + "CodeCheck")), lines,
                StandardCharsets.UTF_8);
        Files.copy(getClass().getResourceAsStream("codecheck.js"), targetDir.resolve("codecheck.js"));
        List<Path> paths = new ArrayList<>();
        paths.add(pathOf(moduleName + "CodeCheck"));
        paths.add(Paths.get("codecheck.js"));
        return paths;
    }

    private static String patternString = "var\\s+(?<name>[A-Za-z][A-Za-z0-9]*)\\s*=(?<rhs>[^;]+);?";
    private static Pattern pattern = Pattern.compile(patternString);

    @Override
    public Pattern variablePattern() {
        return pattern;
    }
    
    
    @Override
    public String compile(List<Path> modules, Path dir) {
        return null;
    }
        
    @Override public List<String> runCommand(Path dir, Path sourceFile, Set<Path> dependentFiles, String args)
    {
        List<String> cmd = new ArrayList<>();
        for (Path p : dependentFiles)  
            cmd.add(dir.resolve(p).toString());
        String programName = dir.resolve(sourceFile).toString();
        cmd.add(programName);        
        if (args != null) { cmd.add("--"); }
        return cmd;
    }

    
    
    
    
    
    
    
    
    
}
