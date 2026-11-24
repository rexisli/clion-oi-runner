package oi.runner;

import com.intellij.openapi.components.PersistentStateComponentWithModificationTracker;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Project-level store, written to .idea/oi-runner.xml: for every source file, its list of test
 * cases, plus the judge limits. Nothing is hand-written; the panel edits this state directly.
 */
@State(name = "OiRunnerCases", storages = @Storage("oi-runner.xml"))
public final class OiCaseStore implements PersistentStateComponentWithModificationTracker<OiCaseStore.State> {

    /** The cases of one source file. */
    public static final class CaseSet {
        /** Empty in old projects and whenever ordinary comparison is selected. */
        public String checkerPath = "";
        public List<OiCase> cases = new ArrayList<>();
    }

    /** Persisted payload. */
    public static final class State {
        public Map<String, CaseSet> sets = new LinkedHashMap<>();
        public int timeLimitMillis = 1000;
        public int memoryLimitMb = 256;
    }

    private State state = new State();

    /** Bumped on every edit so the platform saves the project file while the user types. */
    private final AtomicLong modifications = new AtomicLong();

    /** Marks the case set as changed; also used by the panel after editing a case. */
    public void touch() {
        modifications.incrementAndGet();
    }

    @Override
    public long getStateModificationCount() {
        return modifications.get();
    }

    public static OiCaseStore getInstance(@NotNull Project project) {
        return project.getService(OiCaseStore.class);
    }

    @Override
    public @Nullable State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state == null ? new State() : state;
        if (this.state.sets == null) this.state.sets = new LinkedHashMap<>();
        if (this.state.timeLimitMillis <= 0) this.state.timeLimitMillis = 1000;
        if (this.state.memoryLimitMb <= 0) this.state.memoryLimitMb = 256;
    }

    public CaseSet setFor(String path) {
        String key = normalize(path);
        CaseSet set = state.sets.get(key);
        if (set == null) {
            set = new CaseSet();
            state.sets.put(key, set);
        }
        if (set.cases == null) set.cases = new ArrayList<>();
        if (set.checkerPath == null) set.checkerPath = "";
        return set;
    }

    public int getTimeLimitMillis() {
        return state.timeLimitMillis;
    }

    public void setTimeLimitMillis(int value) {
        state.timeLimitMillis = Math.max(50, value);
        touch();
    }

    public int getMemoryLimitMb() {
        return state.memoryLimitMb;
    }

    public void setMemoryLimitMb(int value) {
        state.memoryLimitMb = Math.max(8, value);
        touch();
    }

    static String normalize(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }
}
