package oi.runner;

import com.intellij.execution.executors.DefaultDebugExecutor;

/**
 * Starts a debug session for the current file using the debug compiler options. Statistics are
 * limited to wall time and the exit code: the process CLion reports here is the debugger, not the
 * program under debug.
 */
public final class OiDebugAction extends OiFileAction {
    @Override
    protected String executorId() {
        return DefaultDebugExecutor.EXECUTOR_ID;
    }

    @Override
    protected Oi.Build build() {
        return Oi.Build.DEBUG;
    }
}
