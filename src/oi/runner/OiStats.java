package oi.runner;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.Executor;
import com.intellij.execution.ExecutorRegistry;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Watches executions and appends wall time / CPU time / peak memory to the Run console.
 *
 * <p>Windows only. CPU time comes from {@code GetProcessTimes}, peak memory from
 * {@code GetProcessMemoryInfo(PeakWorkingSetSize)}. The process handle is opened the instant the
 * process starts, because a terminated CLion process handler no longer reports a pid.
 */
final class OiStats {

    private static final Logger LOG = Logger.getInstance("OiRunner");

    /** Process access rights: PROCESS_QUERY_INFORMATION | PROCESS_VM_READ. */
    private static final int PROCESS_QUERY_INFORMATION = 0x0400;
    private static final int PROCESS_VM_READ = 0x0010;

    private static final Set<Project> HOOKED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Project, Boolean>()));

    private OiStats() {
    }

    static void hook(Project project) {
        if (project == null || project.isDisposed() || !HOOKED.add(project)) return;
        try {
            project.getMessageBus().connect().subscribe(ExecutionManager.EXECUTION_TOPIC, new Listener());
        } catch (Throwable t) {
            LOG.warn("OI Runner: could not subscribe to execution events", t);
            HOOKED.remove(project);
        }
    }

    // ------------------------------------------------------------- win32 ABI

    public static class FILETIME extends Structure {
        public int dwLowDateTime;
        public int dwHighDateTime;

        long quad() {
            return ((long) (dwHighDateTime & 0xffffffffL) << 32) | (dwLowDateTime & 0xffffffffL);
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("dwLowDateTime", "dwHighDateTime");
        }
    }

    public static class PROCESS_MEMORY_COUNTERS extends Structure {
        public int cb;
        public int PageFaultCount;
        public long PeakWorkingSetSize;
        public long WorkingSetSize;
        public long QuotaPeakPagedPoolUsage;
        public long QuotaPagedPoolUsage;
        public long QuotaPeakNonPagedPoolUsage;
        public long QuotaNonPagedPoolUsage;
        public long PagefileUsage;
        public long PeakPagefileUsage;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("cb", "PageFaultCount", "PeakWorkingSetSize", "WorkingSetSize",
                    "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage",
                    "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage");
        }
    }

    public interface Kernel32 extends Library {
        Pointer OpenProcess(int desiredAccess, boolean inheritHandle, int processId);

        boolean GetProcessTimes(Pointer handle, FILETIME creation, FILETIME exit, FILETIME kernel, FILETIME user);

        boolean CloseHandle(Pointer handle);
    }

    public interface Psapi extends Library {
        boolean GetProcessMemoryInfo(Pointer handle, PROCESS_MEMORY_COUNTERS counters, int size);
    }

    private static final class KernelHolder {
        static final Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);
    }

    private static final class PsapiHolder {
        static final Psapi INSTANCE = Native.load("psapi", Psapi.class);
    }

    private static Kernel32 kernel32() {
        try {
            return KernelHolder.INSTANCE;
        } catch (Throwable t) {
            LOG.warn("OI Runner: kernel32 unavailable", t);
            return null;
        }
    }

    private static Psapi psapi() {
        try {
            return PsapiHolder.INSTANCE;
        } catch (Throwable t) {
            LOG.warn("OI Runner: psapi unavailable", t);
            return null;
        }
    }

    // -------------------------------------------------------------- listener

    private static final class Capture {
        final int pid;
        final Pointer handle;
        final long startNanos;
        final Project project;
        final Executor executor;
        final boolean debugSession;

        Capture(int pid, Pointer handle, long startNanos, Project project, Executor executor, boolean debugSession) {
            this.pid = pid;
            this.handle = handle;
            this.startNanos = startNanos;
            this.project = project;
            this.executor = executor;
            this.debugSession = debugSession;
        }
    }

    private static final class Listener implements ExecutionListener {
        private final Map<ProcessHandler, Capture> captures =
                Collections.synchronizedMap(new IdentityHashMap<ProcessHandler, Capture>());
        private final Map<ProcessHandler, Long> starting =
                Collections.synchronizedMap(new IdentityHashMap<ProcessHandler, Long>());

        @Override
        public void processStarting(String executorId, ExecutionEnvironment env, ProcessHandler handler) {
            starting.put(handler, System.nanoTime());
        }

        @Override
        public void processStarted(String executorId, ExecutionEnvironment env, ProcessHandler handler) {
            long now = System.nanoTime();
            Long started = starting.remove(handler);
            Executor executor = ExecutorRegistry.getInstance().getExecutorById(executorId);
            boolean debug = DefaultDebugExecutor.EXECUTOR_ID.equals(executorId);

            int pid = -1;
            Pointer handle = null;
            if (!debug) {
                // In a debug session the handler belongs to the debugger (LLDB), whose CPU and
                // memory say nothing about the program under debug: report wall time only.
                pid = pidOf(handler);
                Kernel32 k = kernel32();
                if (k != null && pid > 0) {
                    try {
                        handle = k.OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, false, pid);
                    } catch (Throwable t) {
                        LOG.warn("OI Runner: OpenProcess failed for pid " + pid, t);
                    }
                }
            }
            captures.put(handler, new Capture(pid, handle,
                    started == null ? now : started,
                    env == null ? null : env.getProject(), executor, debug));
        }

        @Override
        public void processTerminated(String executorId, ExecutionEnvironment env, ProcessHandler handler, int code) {
            long end = System.nanoTime();
            Capture c = captures.remove(handler);
            if (c == null) return;

            double wall = (end - c.startNanos) / 1e9;
            double cpuMillis = -1;
            long peakBytes = -1;

            if (c.handle != null) {
                Kernel32 k = kernel32();
                try {
                    if (k != null) {
                        FILETIME creation = new FILETIME();
                        FILETIME exit = new FILETIME();
                        FILETIME kernel = new FILETIME();
                        FILETIME user = new FILETIME();
                        if (k.GetProcessTimes(c.handle, creation, exit, kernel, user)) {
                            cpuMillis = (kernel.quad() + user.quad()) / 10000.0;
                        }
                        Psapi ps = psapi();
                        if (ps != null) {
                            PROCESS_MEMORY_COUNTERS counters = new PROCESS_MEMORY_COUNTERS();
                            counters.cb = counters.size();
                            counters.write();
                            if (ps.GetProcessMemoryInfo(c.handle, counters, counters.size())) {
                                counters.read();
                                peakBytes = counters.PeakWorkingSetSize;
                            }
                        }
                    }
                } catch (Throwable t) {
                    LOG.warn("OI Runner: process statistics unavailable", t);
                } finally {
                    try {
                        if (k != null) k.CloseHandle(c.handle);
                    } catch (Throwable ignored) {
                        // handle is gone either way
                    }
                }
            }

            String text = format(code, wall, cpuMillis, peakBytes);
            String log = "exit=" + code + " wall=" + Oi.fixed(wall, 4) + "s"
                    + (c.debugSession ? " (debug session)" : "")
                    + (cpuMillis >= 0 ? " cpu=" + Oi.fixed(cpuMillis, 4) + "ms" : "")
                    + (peakBytes >= 0 ? " peak=" + peakBytes + "B" : "");
            LOG.info("OI Runner: " + log);
            append(c, handler, text);
        }

        private static String format(int exitCode, double wall, double cpuMillis, long peakBytes) {
            StringBuilder sb = new StringBuilder();
            sb.append('\n').append("-----------------------------\n");
            sb.append("Process finished with exit code ").append(exitCode).append('\n');
            sb.append("Wall time: ").append(Oi.fixed(wall, 4)).append(" s");
            if (cpuMillis >= 0) sb.append(" | CPU time: ").append(Oi.fixed(cpuMillis, 4)).append(" ms");
            if (peakBytes >= 0) sb.append(" | Peak memory: ").append(Oi.humanBytes(peakBytes));
            sb.append('\n');
            return sb.toString();
        }

        private static void append(Capture c, ProcessHandler handler, String text) {
            Project project = c.project;
            if (project == null || project.isDisposed()) return;
            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    RunContentDescriptor descriptor =
                            RunContentManager.getInstance(project).findContentDescriptor(c.executor, handler);
                    if (descriptor == null) return;
                    ExecutionConsole console = descriptor.getExecutionConsole();
                    if (console instanceof ConsoleView) {
                        ((ConsoleView) console).print(text, ConsoleViewContentType.SYSTEM_OUTPUT);
                    }
                } catch (Throwable t) {
                    LOG.warn("OI Runner: could not append statistics to the console", t);
                }
            });
        }

        private static int pidOf(ProcessHandler handler) {
            try {
                Method m = Oi.method(handler.getClass(), "getProcess", 0);
                if (m == null) return -1;
                Object process = m.invoke(handler);
                return process instanceof Process ? (int) ((Process) process).pid() : -1;
            } catch (Throwable t) {
                return -1;
            }
        }
    }
}
