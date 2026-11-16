package oi.runner;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;

import java.util.List;
import java.util.Locale;

/**
 * Kernel-level facts about one child process: creation/exit timestamps, CPU time and peak working
 * set. Everything here is asked from Windows, never estimated from the IDE's event stream, so no
 * build or console start-up time can leak into a measurement.
 */
final class OiKernel {

    private static final int PROCESS_QUERY_INFORMATION = 0x0400;
    private static final int PROCESS_VM_READ = 0x0010;
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

    static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

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
            return null;
        }
    }

    private static Psapi psapi() {
        try {
            return PsapiHolder.INSTANCE;
        } catch (Throwable t) {
            return null;
        }
    }

    /** A held process handle; measurements stay valid after the process has exited. */
    static final class Probe {
        private final Pointer handle;
        private final Kernel32 kernel;
        private final Psapi psapi;

        private Probe(Pointer handle) {
            this.handle = handle;
            this.kernel = kernel32();
            this.psapi = psapi();
        }

        /** Wall time between the process' own creation and exit, in seconds, or -1. */
        double wallSeconds() {
            FILETIME[] times = times();
            if (times == null) return -1;
            long started = times[0].quad();
            long ended = times[1].quad();
            return (started == 0 || ended == 0 || ended < started) ? -1 : (ended - started) / 1e7;
        }

        /** Kernel + user time in milliseconds, or -1. */
        double cpuMillis() {
            FILETIME[] times = times();
            if (times == null) return -1;
            return (times[2].quad() + times[3].quad()) / 1e4;
        }

        /** Peak working set in bytes, or -1. */
        long peakBytes() {
            if (handle == null || psapi == null) return -1;
            try {
                PROCESS_MEMORY_COUNTERS counters = new PROCESS_MEMORY_COUNTERS();
                counters.cb = counters.size();
                counters.write();
                if (!psapi.GetProcessMemoryInfo(handle, counters, counters.size())) return -1;
                counters.read();
                return counters.PeakWorkingSetSize;
            } catch (Throwable t) {
                return -1;
            }
        }

        private FILETIME[] times() {
            if (handle == null || kernel == null) return null;
            try {
                FILETIME creation = new FILETIME();
                FILETIME exit = new FILETIME();
                FILETIME kernelTime = new FILETIME();
                FILETIME userTime = new FILETIME();
                if (!kernel.GetProcessTimes(handle, creation, exit, kernelTime, userTime)) return null;
                return new FILETIME[]{creation, exit, kernelTime, userTime};
            } catch (Throwable t) {
                return null;
            }
        }

        void close() {
            if (handle != null && kernel != null) {
                try {
                    kernel.CloseHandle(handle);
                } catch (Throwable ignored) {
                    // the handle is gone either way
                }
            }
        }
    }

    static Probe open(long pid) {
        if (!WINDOWS || pid <= 0) return new Probe(null);
        Kernel32 k = kernel32();
        if (k == null) return new Probe(null);
        try {
            Pointer handle = k.OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, false, (int) pid);
            if (handle == null) {
                handle = k.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, (int) pid);
            }
            return new Probe(handle);
        } catch (Throwable t) {
            return new Probe(null);
        }
    }
}
