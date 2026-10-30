package com.cpplatform.service;

import com.cpplatform.entity.Language;

import java.util.List;

/**
 * Per-language settings the JudgeService needs: which runner image to use,
 * the source filename it must write, and the compile/run commands to execute
 * inside that container.
 */
public record RunnerConfig(
        String image,
        String sourceFileName,
        boolean needsCompile,
        List<String> compileCommand,
        List<String> runCommand
) {     
    public static RunnerConfig forLanguage(Language language) {
        return switch (language) {
            case CPP -> new RunnerConfig(
                    "judge-runner-cpp",
                    "solution.cpp",
                    true,
                    List.of("g++", "-O2", "-o", "sol", "solution.cpp"),
                    List.of("./sol")
            );
            // Python is interpreted, but we run a byte-compile pass first so that a
            // syntax error is reported as a COMPILE_ERROR (like C++/Java) instead of
            // surfacing as a runtime failure on the first test case.
            case PYTHON -> new RunnerConfig(
                    "judge-runner-python",
                    "solution.py",
                    true,
                    List.of("python3", "-m", "py_compile", "solution.py"),
                    List.of("python3", "solution.py")
            );
            case JAVA -> new RunnerConfig(
                    "judge-runner-java",
                    "Main.java",
                    true,
                    List.of("javac", "Main.java"),
                    List.of("java", "-cp", ".", "Main")
            );
        };
    }
}
