package oi.runner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Runs a precompiled testlib-compatible checker: input, contestant output, answer. */
final class OiChecker {
    private OiChecker() {}

    static void check(String checkerPath, Path input, Path actual, String expected, OiJudge.CaseResult result) {
        Process p = null;
        result.verdict = OiJudge.Verdict.JE;
        try {
            // The caller owns and removes this directory once judging completes.
            Path dir = input.getParent();
            Path answer = Files.createTempFile(dir, "answer-", ".out");
            Path log = Files.createTempFile(dir, "checker-", ".log");
            Files.writeString(answer, expected == null ? "" : expected, StandardCharsets.UTF_8);
            ProcessBuilder pb = new ProcessBuilder(Path.of(checkerPath).toAbsolutePath().toString(),
                    input.toString(), actual.toString(), answer.toString());
            pb.directory(dir.toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(log.toFile());
            p = pb.start();
            p.getOutputStream().close();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                OiJudge.stop(p);
                result.checkerMessage = diagnostic("checker 超时（5 s）", log);
                return;
            }
            int code = p.exitValue();
            result.checkerMessage = diagnostic("checker exit " + code, log);
            result.verdict = switch (code) {
                case 0 -> OiJudge.Verdict.AC;
                case 1, 2 -> OiJudge.Verdict.WA;
                default -> OiJudge.Verdict.JE;
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.checkerMessage = "checker 被中断";
        } catch (IOException | RuntimeException e) {
            result.checkerMessage = "无法运行 checker：" + e;
        } finally {
            OiJudge.stop(p);
        }
    }

    private static String diagnostic(String status, Path log) throws IOException {
        try (var stream = Files.newInputStream(log)) {
            String text = OiJudge.decode(stream.readNBytes(8192)).strip();
            if (Files.size(log) > 8192) text += "…";
            return text.isEmpty() ? status : status + "\n" + text;
        }
    }
}
