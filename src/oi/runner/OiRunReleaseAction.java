package oi.runner;

import com.intellij.execution.executors.DefaultRunExecutor;

/** Builds the current file with the release compiler options, runs it, prints the statistics block. */
public final class OiRunReleaseAction extends OiFileAction {
    @Override
    protected String executorId() {
        return DefaultRunExecutor.EXECUTOR_ID;
    }

    @Override
    protected Oi.Build build() {
        return Oi.Build.RELEASE;
    }
}
