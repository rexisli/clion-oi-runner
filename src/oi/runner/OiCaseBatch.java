package oi.runner;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;

/** Compiles once per batch, then reports each case as it finishes. Callbacks run off the EDT. */
final class OiCaseBatch {
    private static final int CPUS = Runtime.getRuntime().availableProcessors();
    private static final Executor COMPILERS = AppExecutorUtil.createBoundedApplicationPoolExecutor(
            "OI Runner compilers", Math.min(2, CPUS));
    private static final Executor CASES = AppExecutorUtil.createBoundedApplicationPoolExecutor(
            "OI Runner cases", Math.max(1, Math.min(4, CPUS / 2)));

    record Input(String stdin, String expected) {}

    private OiCaseBatch() {}

    static void start(Project project, VirtualFile file, List<Input> inputs, int timeLimit, int memoryLimit,
                      BiConsumer<Integer, OiJudge.CaseResult> onCase,
                      BiConsumer<Double, String> onFinish) {
        String source = file.getPath();
        String stem = file.getNameWithoutExtension();
        String compiler = OiJudge.compilerPath();
        OiSettings settings = OiSettings.getInstance();
        String options = settings == null ? OiSettings.DEFAULT_JUDGE : settings.getJudgeOptions();

        CompletableFuture.supplyAsync(() -> {
            if (project.isDisposed()) return null;
            return OiJudge.compile(source, stem, compiler, options);
        }, COMPILERS).whenComplete((build, error) -> {
            if (error != null) {
                onFinish.accept(-1.0, error.toString());
            } else if (build != null && build.error != null) {
                onFinish.accept(-1.0, build.error);
            } else if (build != null) {
                runCases(project, build, inputs, timeLimit, memoryLimit, onCase, onFinish);
            }
        });
    }

    private static void runCases(Project project, OiJudge.Build build, List<Input> inputs,
                                 int timeLimit, int memoryLimit,
                                 BiConsumer<Integer, OiJudge.CaseResult> onCase,
                                 BiConsumer<Double, String> onFinish) {
        CompletableFuture<?>[] jobs = IntStream.range(0, inputs.size()).mapToObj(index ->
                CompletableFuture.runAsync(() -> {
                    if (project.isDisposed()) return;
                    Input input = inputs.get(index);
                    OiJudge.CaseResult result = OiJudge.runCase(build, input.stdin(), input.expected(), timeLimit, memoryLimit);
                    if (index == 0 && result.verdict == OiJudge.Verdict.TLE && !project.isDisposed()) {
                        // A real-time antivirus scan of a fresh executable can cause a false TLE.
                        result = OiJudge.runCase(build, input.stdin(), input.expected(), timeLimit, memoryLimit);
                    }
                    onCase.accept(index, result);
                }, CASES)).toArray(CompletableFuture<?>[]::new);
        CompletableFuture.allOf(jobs).whenComplete((ignored, error) -> {
            OiJudge.cleanup(build);
            onFinish.accept(build.seconds, error == null ? null : error.toString());
        });
    }
}
