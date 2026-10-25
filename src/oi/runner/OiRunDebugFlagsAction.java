package oi.runner;

import com.intellij.execution.executors.DefaultRunExecutor;

/**
 * Builds the current file with the debug compiler options and runs it without the debugger, so an
 * unoptimised run can be timed against the release one.
 */
public final class OiRunDebugFlagsAction extends OiFileAction {
    @Override
    protected String executorId() {
        return DefaultRunExecutor.EXECUTOR_ID;
    }

    @Override
    protected Oi.Build build() {
        return Oi.Build.DEBUG;
    }
}
