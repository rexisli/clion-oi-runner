package oi.runner;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Compiles one source file with the configured release options, then runs the executable once per
 * test case. The plugin starts the program itself and feeds stdin from a file, so the wall time is
 * only the program's own lifetime: no compile, no console, no IDE event window.
 */
final class OiJudge {

    enum Verdict { AC, WA, TLE, MLE, RE, CE }

    static final class Build {
        Path exe;
        Path dir;
        String error;
        String log = "";
        double seconds;
    }

    static final class CaseResult {
        Verdict verdict = Verdict.RE;
        String actual = "";
        double wallSeconds = -1;
        double cpuMillis = -1;
        long peakBytes = -1;
    }

    private OiJudge() {
    }

    static Build compile(@Nullable Project project, VirtualFile file) {
        Build b = new Build();
        Path dir = buildDir(file);
        String stem = file.getNameWithoutExtension();
        Path exe = dir.resolve(stem + (OiKernel.WINDOWS ? ".exe" : ""));
        String compiler = compilerPath();
        OiSettings settings = OiSettings.getInstance();
        String options = settings == null ? OiSettings.DEFAULT_JUDGE : settings.getJudgeOptions();

        List<String> command = new ArrayList<>();
        command.add(compiler);
        command.addAll(splitOptions(options));
        command.add("-o");
        command.add(exe.toString());
        command.add(file.getPath());

        long started = System.nanoTime();
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(dir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output;
            try (InputStream in = p.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!p.waitFor(300, TimeUnit.SECONDS)) {
                killTree(p);
                b.error = "编译超时（300 s）\n" + tail(output);
                return b;
            }
            b.seconds = (System.nanoTime() - started) / 1e9;
            b.log = output;
            if (p.exitValue() != 0) {
                b.error = "编译失败（exit " + p.exitValue() + "）\n\n" + tail(output);
                return b;
            }
            if (!Files.isRegularFile(exe)) {
                b.error = "编译过程没有报错，但找不到可执行文件：" + exe;
                return b;
            }
            b.exe = exe;
            b.dir = dir;
        } catch (Throwable t) {
            b.error = "无法调用编译器 " + compiler + "\n" + t;
        }
        return b;
    }

    static CaseResult runCase(Build build, OiCase c, int timeLimitMillis, int memoryLimitMb) {
        CaseResult r = new CaseResult();
        Path in = build.dir.resolve("case.in");
        Path out = build.dir.resolve("case.out");
        try {
            Files.writeString(in, c.input == null ? "" : c.input, StandardCharsets.UTF_8);
            Files.deleteIfExists(out);

            ProcessBuilder pb = new ProcessBuilder(build.exe.toString());
            pb.directory(build.dir.toFile());
            pb.redirectInput(in.toFile());
            pb.redirectOutput(out.toFile());
            pb.redirectErrorStream(true);

            long started = System.nanoTime();
            Process p = pb.start();
            OiKernel.Probe probe = OiKernel.open(p.pid());
            boolean finished = p.waitFor(timeLimitMillis + 250L, TimeUnit.MILLISECONDS);
            if (!finished) {
                killTree(p);
                p.waitFor(2, TimeUnit.SECONDS);
            }
            r.actual = readText(out);
            double wall = probe.wallSeconds();
            if (wall < 0) wall = (System.nanoTime() - started) / 1e9;
            r.wallSeconds = wall;
            r.cpuMillis = probe.cpuMillis();
            r.peakBytes = probe.peakBytes();
            probe.close();

            if (!finished) {
                r.verdict = Verdict.TLE;
                return r;
            }
            if (r.peakBytes > 0 && r.peakBytes > (long) memoryLimitMb * 1024L * 1024L) {
                r.verdict = Verdict.MLE;
                return r;
            }
            if (p.exitValue() != 0) {
                r.verdict = Verdict.RE;
                return r;
            }
            if (r.wallSeconds * 1000.0 > timeLimitMillis) {
                r.verdict = Verdict.TLE;
                return r;
            }
            r.verdict = sameOutput(c.expected, r.actual) ? Verdict.AC : Verdict.WA;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            r.verdict = Verdict.RE;
        } catch (Throwable t) {
            r.verdict = Verdict.RE;
            r.actual = "运行失败：" + t;
        }
        return r;
    }

    // --------------------------------------------------------------- helpers

    static String compilerPath() {
        OiSettings settings = OiSettings.getInstance();
        String configured = settings == null ? "" : settings.getCompilerPath();
        if (configured != null && !configured.isBlank()) return configured.trim();
        if (OiKernel.WINDOWS) {
            String[] candidates = {
                    "bin/mingw/bin/g++.exe",
                    "bin/mingw64/bin/g++.exe",
                    "bin/gcc/bin/g++.exe"
            };
            try {
                String home = PathManager.getHomePath();
                for (String candidate : candidates) {
                    Path p = Paths.get(home, candidate.split("/"));
                    if (Files.isRegularFile(p)) return p.toString();
                }
            } catch (Throwable ignored) {
                // fall through to PATH
            }
        }
        return "g++";
    }

    static List<String> splitOptions(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String token : raw.trim().split("\\s+")) {
            if (!token.isEmpty()) out.add(token);
        }
        return out;
    }

    private static Path buildDir(VirtualFile file) {
        String key = Integer.toHexString(file.getPath().hashCode());
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "oi-runner",
                file.getNameWithoutExtension() + "-" + key);
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // the compiler invocation will report the real problem
        }
        return dir;
    }

    private static void killTree(Process p) {
        try {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (Throwable ignored) {
            // best effort
        }
        p.destroyForcibly();
    }

    private static String readText(Path file) {
        try {
            if (!Files.isRegularFile(file)) return "";
            byte[] bytes = Files.readAllBytes(file);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    /** Judge comparison: whitespace is insignificant, trailing blank lines are noise. */
    static boolean sameOutput(String expected, String actual) {
        List<String> a = tokens(expected);
        List<String> b = tokens(actual);
        return a.equals(b);
    }

    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String token : text.split("\\s+")) {
            if (!token.isEmpty()) out.add(token);
        }
        return out;
    }

    private static String tail(String text) {
        if (text == null) return "";
        String trimmed = text.strip();
        return trimmed.length() <= 4000 ? trimmed : "…" + trimmed.substring(trimmed.length() - 4000);
    }
}
