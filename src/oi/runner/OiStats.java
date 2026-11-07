package oi.runner;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.Executor;
import com.intellij.execution.ExecutorRegistry;
import com.intellij.execution.configurations.RunProfile;
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
import com.sun.jna.ptr.IntByReference;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watches executions and appends wall time / CPU time / peak memory to the Run console.
 *
 * <p>Windows only. The interesting number is how long the <em>program</em> lived, so this class
 * never trusts the IDE's own event timestamps: it opens the process and asks the kernel for its
 * creation and exit times ({@code GetProcessTimes}). CLion starts the program behind a ConPTY, and
 * the console/pseudo-terminal teardown happens well after the program itself has exited, which is
 * what used to inflate the reported wall time of fast programs.
 *
 * <p>In a debug session the process handler belongs to the debugger (LLDB), not to the program, so
 * the program is located among the debugger's and the IDE's descendants by image name and measured
 * the same way. Its lifetime includes time spent paused at breakpoints; when it cannot be found the
 * session length is reported instead and marked as such.
 */
final class OiStats {

    private static final Logger LOG = Logger.getInstance("OiRunner");

    /** Process access rights. */
    private static final int PROCESS_QUERY_INFORMATION = 0x0400;
    private static final int PROCESS_VM_READ = 0x0010;
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** How long to keep looking for the program a debug session runs. */
    private static final long WATCH_NANOS = 30_000_000_000L;

    /** Exit code placeholder: ask the process handle instead. */
    private static final int EXIT_UNKNOWN = -1000;

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

        boolean GetExitCodeProcess(Pointer handle, IntByReference exitCode);

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

    /** Opens a process for timing; falls back to the weaker right when the strong one is denied. */
    private static Pointer open(int pid) {
        Kernel32 k = kernel32();
        if (k == null || pid <= 0) return null;
        try {
            Pointer handle = k.OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, false, pid);
            if (handle != null) return handle;
            return k.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
        } catch (Throwable t) {
            LOG.warn("OI Runner: OpenProcess failed for pid " + pid, t);
            return null;
        }
    }

    private static void close(Pointer handle) {
        Kernel32 k = kernel32();
        if (k == null || handle == null) return;
        try {
            k.CloseHandle(handle);
        } catch (Throwable ignored) {
            // the handle is gone either way
        }
    }

    /** Creation time in 100 ns ticks, or 0 when it cannot be read. */
    private static long creationTime(Pointer handle) {
        Kernel32 k = kernel32();
        if (k == null || handle == null) return 0;
        try {
            FILETIME creation = new FILETIME();
            FILETIME exit = new FILETIME();
            FILETIME kernel = new FILETIME();
            FILETIME user = new FILETIME();
            if (k.GetProcessTimes(handle, creation, exit, kernel, user)) return creation.quad();
        } catch (Throwable t) {
            LOG.warn("OI Runner: GetProcessTimes failed", t);
        }
        return 0;
    }

    // -------------------------------------------------------------- listener

    /** Everything the plugin knows about one executed process. */
    private static final class Capture {
        final ProcessHandler handler;
        final int handlerPid;
        final long eventStartNanos;
        final Project project;
        final Executor executor;
        final boolean debugSession;
        final String exeName;
        final long sessionStartMillis;
        final AtomicBoolean printed = new AtomicBoolean();

        /** The process that is being timed; differs from {@link #handlerPid} in debug sessions. */
        volatile int pid = -1;
        volatile Pointer handle;
        volatile long creation;
        volatile boolean done;

        Capture(ProcessHandler handler, int handlerPid, long eventStartNanos, Project project, Executor executor,
                boolean debugSession, String exeName) {
            this.handler = handler;
            this.handlerPid = handlerPid;
            this.eventStartNanos = eventStartNanos;
            this.project = project;
            this.executor = executor;
            this.debugSession = debugSession;
            this.exeName = exeName;
            this.sessionStartMillis = System.currentTimeMillis();
        }
    }

    private static final class Stats {
        int exitCode = EXIT_UNKNOWN;
        double wallSeconds = -1;
        double cpuMillis = -1;
        long peakBytes = -1;
        boolean kernelTimed;
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
            Project project = env == null ? null : env.getProject();
            String exeName = WINDOWS ? baseName(exePathOf(env)) : null;

            Capture c = new Capture(handler, pidOf(handler), started == null ? now : started,
                    project, executor, debug, exeName);
            captures.put(handler, c);

            if (!WINDOWS || project == null) return;

            if (isProgramItself(c.handlerPid, exeName, debug)) {
                Pointer handle = open(c.handlerPid);
                if (handle != null) {
                    c.pid = c.handlerPid;
                    c.handle = handle;
                    c.creation = creationTime(handle);
                    return;
                }
                LOG.info("OI Runner: pid " + c.handlerPid + " is already gone, timing falls back to event window");
                return;
            }
            // Debug session, or the handler wraps the program in something else: find the real
            // program while it is starting.
            watchForProgram(c);
        }

        @Override
        public void processTerminated(String executorId, ExecutionEnvironment env, ProcessHandler handler, int code) {
            Capture c = captures.remove(handler);
            if (c == null) return;
            c.done = true;
            finish(c, code);
        }
    }

    // ------------------------------------------------------ finding the program

    private static boolean isProgramItself(int pid, String exeName, boolean debugSession) {
        if (pid <= 0 || !WINDOWS) return false;
        String image = imageNameOf(pid);
        if (image == null) return !debugSession;
        if (exeName == null) return !debugSession;
        return exeName.equalsIgnoreCase(image);
    }

    private static void watchForProgram(Capture c) {
        Thread watcher = new Thread(() -> {
            long deadline = System.nanoTime() + WATCH_NANOS;
            int round = 0;
            while (!c.done && System.nanoTime() < deadline) {
                for (ProcessHandle candidate : candidates(c, round)) {
                    if (!matches(c, candidate)) continue;
                    if (attach(c, candidate.pid())) {
                        watchExitFallback(c);
                        return;
                    }
                }
                round++;
                try {
                    // A program that is about to be debugged may live only a few milliseconds before
                    // it exits, so poll hard at first and relax later.
                    Thread.sleep(round < 250 ? 2L : round < 1500 ? 10L : 50L);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (!c.done) {
                LOG.info("OI Runner: no process named " + c.exeName + " appeared under pid "
                        + c.handlerPid + ", timing falls back to the session window");
            }
        }, "OI Runner program watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static List<ProcessHandle> candidates(Capture c, int round) {
        List<ProcessHandle> pool = new ArrayList<>(16);
        try {
            ProcessHandle.of(c.handlerPid).ifPresent(h -> pool.addAll(h.descendants().toList()));
            pool.addAll(ProcessHandle.current().descendants().toList());
            // The debugger backend may be a separate process tree; widen the search every so often.
            if (round > 20 && round % 10 == 0) pool.addAll(ProcessHandle.allProcesses().toList());
        } catch (Throwable t) {
            LOG.info("OI Runner: could not enumerate processes: " + t);
        }
        return pool;
    }

    private static boolean matches(Capture c, ProcessHandle candidate) {
        if (c.exeName == null) return false;
        String image = imageName(candidate);
        if (image == null || !c.exeName.equalsIgnoreCase(image)) return false;
        // A leftover run of the same executable must not be mistaken for this one.
        Optional<Instant> start = candidate.info().startInstant();
        return start.isEmpty() || start.get().toEpochMilli() + 1000L >= c.sessionStartMillis;
    }

    private static boolean attach(Capture c, long pid) {
        Pointer handle = open((int) pid);
        if (handle == null) return false;
        long creation = creationTime(handle);
        if (c.done) {
            close(handle);
            return true;
        }
        c.pid = (int) pid;
        c.handle = handle;
        c.creation = creation;
        LOG.info("OI Runner: timing pid " + pid + " (" + c.exeName + ")"
                + (c.debugSession ? " behind the debugger" : "") + (creation == 0 ? ", no creation time" : ""));
        return true;
    }

    /**
     * A debug session's termination event arrives when the debugger exits, which can be much later
     * than the program's own exit. If the program is gone and nothing was printed shortly after,
     * report anyway so the number is never lost.
     */
    private static void watchExitFallback(Capture c) {
        int pid = c.pid;
        if (pid <= 0 || !c.debugSession) return;
        try {
            ProcessHandle.of(pid).ifPresent(h -> h.onExit().thenRunAsync(
                    () -> finish(c, EXIT_UNKNOWN),
                    CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS)));
        } catch (Throwable t) {
            LOG.info("OI Runner: could not watch pid " + pid + ": " + t);
        }
    }

    // ------------------------------------------------------------- reporting

    private static void finish(Capture c, int exitCode) {
        if (c == null || !c.printed.compareAndSet(false, true)) return;
        Stats s;
        try {
            s = readStats(c, exitCode);
        } catch (Throwable t) {
            LOG.warn("OI Runner: process statistics unavailable", t);
            return;
        }
        LOG.info("OI Runner: exit=" + s.exitCode + " wall=" + Oi.fixed(s.wallSeconds, 4) + "s"
                + (s.kernelTimed ? " (kernel)" : " (event window)")
                + (c.pid > 0 ? " pid=" + c.pid : "")
                + (c.debugSession ? " debug-session" : "")
                + (s.cpuMillis >= 0 ? " cpu=" + Oi.fixed(s.cpuMillis, 4) + "ms" : "")
                + (s.peakBytes >= 0 ? " peak=" + s.peakBytes + "B" : ""));
        append(c, format(s, c.debugSession));
    }

    private static Stats readStats(Capture c, int exitCode) {
        Stats s = new Stats();
        s.exitCode = exitCode;

        Pointer handle;
        synchronized (c) {
            handle = c.handle;
            c.handle = null;
        }

        if (handle != null) {
            Kernel32 k = kernel32();
            try {
                FILETIME creation = new FILETIME();
                FILETIME exit = new FILETIME();
                FILETIME kernel = new FILETIME();
                FILETIME user = new FILETIME();
                if (k != null && k.GetProcessTimes(handle, creation, exit, kernel, user)) {
                    long started = creation.quad();
                    long ended = exit.quad();
                    if (started != 0 && ended != 0 && ended >= started) {
                        s.wallSeconds = (ended - started) / 1e7;
                        s.kernelTimed = true;
                    }
                    s.cpuMillis = (kernel.quad() + user.quad()) / 1e4;
                }
                if (s.exitCode == EXIT_UNKNOWN && k != null) {
                    IntByReference ref = new IntByReference();
                    if (k.GetExitCodeProcess(handle, ref)) s.exitCode = ref.getValue();
                }
                Psapi ps = psapi();
                if (ps != null) {
                    PROCESS_MEMORY_COUNTERS counters = new PROCESS_MEMORY_COUNTERS();
                    counters.cb = counters.size();
                    counters.write();
                    if (ps.GetProcessMemoryInfo(handle, counters, counters.size())) {
                        counters.read();
                        s.peakBytes = counters.PeakWorkingSetSize;
                    }
                }
            } catch (Throwable t) {
                LOG.warn("OI Runner: process statistics unavailable", t);
            } finally {
                close(handle);
            }
        }

        if (s.wallSeconds < 0) {
            // Kernel times were not available (process already gone): fall back to the window the
            // IDE reported, which also contains the console start-up/tear-down.
            s.wallSeconds = (System.nanoTime() - c.eventStartNanos) / 1e9;
            s.kernelTimed = false;
        }
        return s;
    }

    private static String format(Stats s, boolean debugSession) {
        StringBuilder sb = new StringBuilder();
        sb.append('\n').append("-----------------------------\n");
        if (s.exitCode != EXIT_UNKNOWN) {
            sb.append("Process finished with exit code ").append(s.exitCode).append('\n');
        } else {
            sb.append("Process finished\n");
        }
        sb.append("Wall time: ").append(Oi.fixed(s.wallSeconds, 4)).append(" s");
        if (s.cpuMillis >= 0) sb.append(" | CPU time: ").append(Oi.fixed(s.cpuMillis, 4)).append(" ms");
        if (s.peakBytes >= 0) sb.append(" | Peak memory: ").append(Oi.humanBytes(s.peakBytes));
        if (debugSession) sb.append(s.kernelTimed ? " [debug]" : " [debug session]");
        else if (!s.kernelTimed) sb.append(" [approximate]");
        sb.append('\n');
        return sb.toString();
    }

    /**
     * Prints into the run console once it exists. The console is created asynchronously and a fast
     * program can be over before that happens, so the lookup is retried instead of dropped.
     */
    private static void append(Capture c, String text) {
        Project project = c.project;
        if (project == null || project.isDisposed()) return;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            for (int attempt = 0; attempt < 80; attempt++) {
                if (project.isDisposed()) return;
                AtomicBoolean printed = new AtomicBoolean(false);
                try {
                    ApplicationManager.getApplication().invokeAndWait(
                            () -> printed.set(printNow(project, c.executor, c.handler, text)));
                } catch (Throwable t) {
                    LOG.warn("OI Runner: could not append statistics to the console", t);
                    return;
                }
                if (printed.get()) return;
                try {
                    Thread.sleep(attempt == 0 ? 50L : 100L);
                } catch (InterruptedException e) {
                    return;
                }
            }
            LOG.info("OI Runner: console never appeared, statistics were not printed");
        });
    }

    private static boolean printNow(Project project, Executor executor, ProcessHandler handler, String text) {
        RunContentDescriptor descriptor;
        try {
            descriptor = RunContentManager.getInstance(project).findContentDescriptor(executor, handler);
        } catch (Throwable t) {
            LOG.info("OI Runner: console lookup failed: " + t);
            return false;
        }
        if (descriptor == null) return false;
        ExecutionConsole console = descriptor.getExecutionConsole();
        if (console instanceof ConsoleView) {
            ((ConsoleView) console).print(text, ConsoleViewContentType.SYSTEM_OUTPUT);
            return true;
        }
        ConsoleView nested = nestedConsole(console);
        if (nested != null) {
            nested.print(text, ConsoleViewContentType.SYSTEM_OUTPUT);
            return true;
        }
        LOG.info("OI Runner: console is " + (console == null ? "null" : console.getClass().getName())
                + ", statistics were not printed");
        return true;
    }

    /** Some executors wrap the console in a terminal view that still exposes a ConsoleView. */
    private static ConsoleView nestedConsole(ExecutionConsole console) {
        if (console == null) return null;
        try {
            Method m = Oi.method(console.getClass(), "getConsoleView", 0);
            Object view = m == null ? null : m.invoke(console);
            return view instanceof ConsoleView ? (ConsoleView) view : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Path of the executable a run configuration will launch, if CLion can tell us. */
    private static String exePathOf(ExecutionEnvironment env) {
        try {
            RunProfile profile = env == null ? null : env.getRunProfile();
            if (profile == null) return null;
            Method m = Oi.method(profile.getClass(), "getExecutableData", 0);
            Object data = m == null ? null : m.invoke(profile);
            if (data == null) return null;
            try {
                Field f = data.getClass().getField("path");
                Object v = f.get(data);
                if (v instanceof String && !((String) v).isEmpty()) return (String) v;
            } catch (Throwable ignored) {
                // fall through to the accessor
            }
            Method getter = Oi.method(data.getClass(), "getPath", 0);
            Object v = getter == null ? null : getter.invoke(data);
            return v instanceof String && !((String) v).isEmpty() ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String baseName(String path) {
        if (path == null) return null;
        int cut = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
        return cut >= 0 ? path.substring(cut + 1) : path;
    }

    private static String imageNameOf(long pid) {
        try {
            Optional<ProcessHandle> h = ProcessHandle.of(pid);
            return h.map(OiStats::imageName).orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String imageName(ProcessHandle handle) {
        Optional<String> command = handle.info().command();
        return command.isPresent() ? baseName(command.get()) : null;
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
