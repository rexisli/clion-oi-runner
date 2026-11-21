package oi.runner;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionListener;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.Executor;
import com.intellij.execution.ExecutorRegistry;
import com.intellij.execution.InputRedirectAware;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.CheckedDisposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Launches CLion's single-file debugger with the selected case as stdin. */
final class OiCaseDebug {
    private static final Logger LOG = Logger.getInstance(OiCaseDebug.class);
    private static final String FAILURE = "Could not debug this case";

    private OiCaseDebug() {
    }

    /** Called on the EDT after the source document has been saved. */
    static void start(Project project, VirtualFile file, String input) {
        if (project.isDisposed()) return;
        if (!Oi.isCppSource(file) || !file.isValid() || !file.isInLocalFileSystem()) {
            Oi.notify(project, FAILURE, "Open a local C or C++ source file to debug the case.");
            return;
        }
        // Imported inputs can be large. Write the full snapshot off the EDT, preserving EOF.
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (project.isDisposed()) return;
            Path stdin = null;
            try {
                stdin = Files.createTempFile("oi-case-debug-", ".in");
                Files.writeString(stdin, input == null ? "" : input, StandardCharsets.UTF_8);
            } catch (IOException e) {
                deleteInput(stdin);
                LOG.warn("Could not write OI case debug input", e);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!project.isDisposed()) Oi.notify(project, FAILURE, "Could not write the case input file.");
                });
                return;
            }
            Path ready = stdin;
            // Let the callback run even if the project closes in the meantime so it can clean up.
            ApplicationManager.getApplication().invokeLater(() -> launch(project, file, ready));
        });
    }

    private static void launch(Project project, VirtualFile file, Path stdin) {
        if (project.isDisposed()) {
            deleteInput(stdin);
            return;
        }
        CheckedDisposable launchLifetime = Disposer.newCheckedDisposable(project, "OI case debug");
        Disposer.register(launchLifetime, () -> deleteInput(stdin));
        RunManager manager = null;
        RunnerAndConfigurationSettings settings = null;
        boolean submitted = false;
        try {
            if (!file.isValid()) throw new ExecutionException("The source file is no longer available.");
            ConfigurationType type = ConfigurationTypeUtil.findConfigurationType(Oi.TYPE_ID);
            ConfigurationFactory[] factories = type == null ? null : type.getConfigurationFactories();
            if (factories == null || factories.length == 0) {
                throw new ExecutionException("CLion's built-in C++ file configuration is unavailable.");
            }
            Executor executor = ExecutorRegistry.getInstance().getExecutorById(DefaultDebugExecutor.EXECUTOR_ID);
            if (executor == null) throw new ExecutionException("CLion's Debug executor is unavailable.");

            manager = RunManager.getInstance(project);
            String name = manager.suggestUniqueName(file.getName() + " [case debug]", type);
            settings = manager.createConfiguration(name, factories[0]);
            RunConfiguration configuration = settings.getConfiguration();
            if (!Oi.setSourceFile(configuration, file.getPath())
                    || !Oi.applyCompilerOptions(configuration, Oi.optionsOf(Oi.Build.DEBUG))) {
                throw new ExecutionException("Could not set the source file or debug compiler options.");
            }
            // CidrRunConfiguration implements this platform interface directly.
            if (!(configuration instanceof InputRedirectAware.InputRedirectOptions redirect)) {
                throw new ExecutionException("This C++ file configuration does not support stdin redirection.");
            }
            redirect.setRedirectInputPath(stdin.toString());
            redirect.setRedirectInput(true);
            settings.storeInLocalWorkspace();
            settings.setTemporary(true);
            settings.setEditBeforeRun(false);

            ExecutionEnvironment env = ExecutionEnvironmentBuilder.create(executor, settings).activeTarget().build();
            // CLion's file-target service listens for RunManager registration. Leave the IDE's
            // selected configuration alone, and remove only this launch's settings once it ends.
            manager.addConfiguration(settings);
            watchLaunch(project, launchLifetime, manager, settings);
            OiStats.hook(project);
            ProgramRunnerUtil.executeConfiguration(env, true, true);
            submitted = true;
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (ExecutionException | RuntimeException e) {
            LOG.warn("Could not start OI case debug", e);
            Oi.notify(project, FAILURE, e instanceof ExecutionException ? e.getMessage() : "See the IDE log for details.");
        } finally {
            if (!submitted) finishLaunch(project, launchLifetime, manager, settings);
        }
    }

    private static void watchLaunch(Project project, CheckedDisposable lifetime, RunManager manager,
                                    RunnerAndConfigurationSettings settings) {
        project.getMessageBus().connect(lifetime).subscribe(ExecutionManager.EXECUTION_TOPIC, new ExecutionListener() {
            @Override
            public void processNotStarted(String executorId, ExecutionEnvironment env) {
                finished(env);
            }

            @Override
            public void processTerminated(String executorId, ExecutionEnvironment env, ProcessHandler handler, int exitCode) {
                finished(env);
            }

            private void finished(ExecutionEnvironment env) {
                if (env.getRunnerAndConfigurationSettings() == settings) {
                    ApplicationManager.getApplication().invokeLater(() -> finishLaunch(project, lifetime, manager, settings));
                }
            }
        });
    }

    private static void finishLaunch(Project project, CheckedDisposable lifetime, RunManager manager,
                                     RunnerAndConfigurationSettings settings) {
        if (project.isDisposed() || lifetime.isDisposed()) return;
        try {
            if (manager != null && settings != null && manager.hasSettings(settings)) {
                manager.removeConfiguration(settings);
            }
        } finally {
            Disposer.dispose(lifetime);
        }
    }

    private static void deleteInput(Path stdin) {
        if (stdin != null) FileUtil.asyncDelete(stdin.toFile());
    }
}
