package oi.runner;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.util.io.FileUtil;

import java.io.IOException;
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

    enum Verdict { AC, WA, TLE, MLE, RE, CE, JE }

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
        boolean specialJudge;
        String checkerMessage = "";
        int mismatchLine;
        double wallSeconds = -1;
        double cpuMillis = -1;
        long peakBytes = -1;
    }

    private OiJudge() {
    }

    static Build compile(String source, String stem, String compiler, String options) {
        Build b = new Build();

        List<String> command = new ArrayList<>();
        command.add(compiler);
        command.addAll(splitOptions(options));
        Process p = null;
        try {
            b.dir = Files.createTempDirectory("oi-runner-build-");
            Path exe = b.dir.resolve(stem + (OiKernel.WINDOWS ? ".exe" : ""));
            Path log = b.dir.resolve("compile.log");
            command.add("-o");
            command.add(exe.toString());
            command.add(source);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(b.dir.toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(log.toFile());
            long started = System.nanoTime();
            p = pb.start();
            if (!p.waitFor(300, TimeUnit.SECONDS)) {
                stop(p);
                b.error = "编译超时（300 s）\n" + tail(readText(log));
                return b;
            }
            b.seconds = (System.nanoTime() - started) / 1e9;
            String output = readText(log);
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            b.error = "编译被中断";
        } catch (IOException | RuntimeException e) {
            b.error = "无法调用编译器 " + compiler + "\n" + e;
        } finally {
            stop(p);
            if (b.exe == null) cleanup(b);
        }
        return b;
    }

    static CaseResult runCase(Build build, String input, String expected, int timeLimitMillis, int memoryLimitMb) {
        return runCase(build, input, expected, timeLimitMillis, memoryLimitMb, null);
    }

    static CaseResult runCase(Build build, String input, String expected, int timeLimitMillis, int memoryLimitMb,
                              String checkerPath) {
        CaseResult r = new CaseResult();
        r.specialJudge = checkerPath != null && !checkerPath.isBlank();
        Path dir = null;
        Process p = null;
        OiKernel.Probe probe = null;
        try {
            // Isolate redirected streams and any relative files opened by the program itself.
            dir = Files.createTempDirectory(build.dir, "case-");
            Path in = dir.resolve("case.in");
            Path out = dir.resolve("case.out");
            Files.writeString(in, input == null ? "" : input, StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder(build.exe.toString());
            pb.directory(dir.toFile());
            pb.redirectInput(in.toFile());
            pb.redirectOutput(out.toFile());
            // The checker must receive exactly the program's stdout, including its original bytes.
            Path err = dir.resolve("case.err");
            if (r.specialJudge) pb.redirectError(err.toFile());
            else pb.redirectErrorStream(true);

            long started = System.nanoTime();
            p = pb.start();
            probe = OiKernel.open(p.pid());
            boolean finished = p.waitFor(timeLimitMillis + 250L, TimeUnit.MILLISECONDS);
            if (!finished) stop(p);
            double wall = probe.wallSeconds();
            if (wall < 0) wall = (System.nanoTime() - started) / 1e9;
            r.wallSeconds = wall;
            r.cpuMillis = probe.cpuMillis();
            r.peakBytes = probe.peakBytes();
            r.actual = readText(out);

            if (!finished) {
                r.verdict = Verdict.TLE;
                return r;
            }
            if (r.peakBytes > 0 && r.peakBytes > (long) memoryLimitMb * 1024L * 1024L) {
                r.verdict = Verdict.MLE;
                return r;
            }
            if (p.exitValue() != 0) {
                if (r.specialJudge) r.actual += readText(err);
                r.verdict = Verdict.RE;
                return r;
            }
            if (r.wallSeconds * 1000.0 > timeLimitMillis) {
                r.verdict = Verdict.TLE;
                return r;
            }
            if (r.specialJudge) {
                OiChecker.check(checkerPath, in, out, expected, r);
                return r;
            }
            r.mismatchLine = firstMismatchLine(expected, r.actual);
            r.verdict = r.mismatchLine == 0 ? Verdict.AC : Verdict.WA;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            r.actual = "运行失败：" + e;
        } finally {
            stop(p);
            if (probe != null) probe.close();
            if (dir != null) FileUtil.delete(dir.toFile());
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

    static void cleanup(Build build) {
        if (build.dir != null) FileUtil.delete(build.dir.toFile());
    }

    static void stop(Process p) {
        if (p == null || !p.isAlive()) return;
        try {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (Throwable ignored) {
            // best effort
        }
        p.destroyForcibly();
        try {
            p.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String readText(Path file) {
        try {
            if (!Files.isRegularFile(file)) return "";
            return decode(Files.readAllBytes(file));
        } catch (Throwable t) {
            return "";
        }
    }

    /** UTF-8 first; if the bytes are not valid UTF-8 (MinGW on a Chinese Windows prints GBK), decode as GBK. */
    static String decode(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (utf8.indexOf('\uFFFD') < 0) return utf8;
        try {
            return new String(bytes, java.nio.charset.Charset.forName("GBK"));
        } catch (Throwable ignored) {
            return utf8;
        }
    }

    /**
     * Judge comparison: line by line, in order. Only trailing whitespace inside a line and trailing
     * blank lines at the end are ignored — which number sits on which line is part of the answer, so
     * comparing token by token (where "1 2" and "12" or "1\n2 3" and "1 2\n3" all matched) is wrong.
     */
    static boolean sameOutput(String expected, String actual) {
        return lines(expected).equals(lines(actual));
    }

    /**
     * Answer text as lines: CRLF/CR folded to LF, trailing whitespace trimmed per line, trailing blank
     * lines at the end dropped. Shared with the panel so judging and the on-screen diff agree.
     */
    static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        for (String line : normalized.split("\n", -1)) {
            int end = line.length();
            while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) end--;
            out.add(line.substring(0, end));
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return out;
    }

    /** 1-based number of the first line that differs, or 0 when the two answers are line-for-line equal. */
    static int firstMismatchLine(String expected, String actual) {
        return firstMismatchLine(lines(expected), lines(actual));
    }

    static int firstMismatchLine(List<String> expected, List<String> actual) {
        int total = Math.max(expected.size(), actual.size());
        for (int i = 0; i < total; i++) {
            String a = i < expected.size() ? expected.get(i) : null;
            String b = i < actual.size() ? actual.get(i) : null;
            if (a == null ? b != null : !a.equals(b)) return i + 1;
        }
        return 0;
    }

    private static String tail(String text) {
        if (text == null) return "";
        String trimmed = text.strip();
        return trimmed.length() <= 4000 ? trimmed : "…" + trimmed.substring(trimmed.length() - 4000);
    }
}
