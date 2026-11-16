package oi.runner;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentAdapter;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The test-case panel: one tab per case, three panes each (input, expected, actual), a verdict dot
 * per tab, and diff highlighting on the lines of the actual output that do not match.
 */
final class OiCasePanel implements Disposable {

    private static final Icon UNKNOWN = dot(new Color(0x9AA0A6));
    private static final Icon PASSED = dot(new Color(0x27AE60));
    private static final Icon FAILED = dot(new Color(0xE74C3C));

    private final Project project;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JTabbedPane tabs = new JTabbedPane();
    private final JBLabel fileLabel = new JBLabel("未选择 C/C++ 文件");
    private final JBLabel statusLabel = new JBLabel(" ");
    private final JSpinner timeSpinner;
    private final JSpinner memorySpinner;
    private final JButton addButton = new JButton("＋ 案例");
    private final JButton removeButton = new JButton("－ 案例");
    private final JButton runAllButton = new JButton("▶ 全部运行");
    private final JButton runOneButton = new JButton("▶ 运行当前");
    private final JButton importButton = new JButton("导入 .in/.out");
    private final MessageBusConnection bus;
    private final Map<OiCase, CaseTab> tabOf = new IdentityHashMap<>();

    private VirtualFile file;
    private boolean running;

    OiCasePanel(@NotNull Project project) {
        this.project = project;
        OiCaseStore store = OiCaseStore.getInstance(project);
        timeSpinner = new JSpinner(new SpinnerNumberModel(store.getTimeLimitMillis(), 50, 60000, 100));
        memorySpinner = new JSpinner(new SpinnerNumberModel(store.getMemoryLimitMb(), 8, 8192, 64));

        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(tabs, BorderLayout.CENTER);
        statusLabel.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 8, 3, 8));
        root.add(statusLabel, BorderLayout.SOUTH);

        addButton.addActionListener(e -> addCase());
        removeButton.addActionListener(e -> removeCase());
        runAllButton.addActionListener(e -> run(false));
        runOneButton.addActionListener(e -> run(true));
        importButton.addActionListener(e -> importInOut());
        timeSpinner.addChangeListener(e ->
                OiCaseStore.getInstance(project).setTimeLimitMillis((Integer) timeSpinner.getValue()));
        memorySpinner.addChangeListener(e ->
                OiCaseStore.getInstance(project).setMemoryLimitMb((Integer) memorySpinner.getValue()));

        bus = project.getMessageBus().connect(this);
        bus.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
            @Override
            public void selectionChanged(@NotNull FileEditorManagerEvent event) {
                VirtualFile selected = event.getNewFile();
                if (Oi.isCppSource(selected)) showFile(selected);
            }
        });

        for (VirtualFile selected : FileEditorManager.getInstance(project).getSelectedFiles()) {
            if (Oi.isCppSource(selected)) {
                showFile(selected);
                break;
            }
        }
        updateButtons();
    }

    JComponent getComponent() {
        return root;
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        top.add(fileLabel);
        header.add(top, BorderLayout.NORTH);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        for (JButton button : new JButton[]{addButton, removeButton, runAllButton, runOneButton, importButton}) {
            button.setFocusable(false);
            buttons.add(button);
        }
        buttons.add(new JBLabel("时限"));
        timeSpinner.setFocusable(false);
        buttons.add(timeSpinner);
        buttons.add(new JBLabel("ms  内存"));
        memorySpinner.setFocusable(false);
        buttons.add(memorySpinner);
        buttons.add(new JBLabel("MB"));
        header.add(buttons, BorderLayout.CENTER);
        return header;
    }

    // ------------------------------------------------------------- file tabs

    private void showFile(@NotNull VirtualFile selected) {
        if (file != null && file.getPath().equals(selected.getPath())) return;
        file = selected;
        fileLabel.setText("  " + selected.getName());
        rebuildTabs();
    }

    private void rebuildTabs() {
        tabs.removeAll();
        tabOf.clear();
        if (file == null) return;
        OiCaseStore.CaseSet set = OiCaseStore.getInstance(project).setFor(file.getPath());
        for (int i = 0; i < set.cases.size(); i++) addTab(set.cases.get(i), i);
        updateButtons();
    }

    private void addTab(@NotNull OiCase c, int index) {
        EditorTextField input = field(c.input, false);
        EditorTextField expected = field(c.expected, false);
        EditorTextField actual = field(c.actual, true);
        input.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                c.input = input.getDocument().getText();
                OiCaseStore.getInstance(project).touch();
            }
        });
        expected.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                c.expected = expected.getDocument().getText();
                OiCaseStore.getInstance(project).touch();
            }
        });
        CaseTab tab = new CaseTab(c, input, expected, actual);
        tabs.addTab("案例 " + (index + 1), UNKNOWN, tab.panel,
                "输入 / 期望输出 / 实际输出");
        tabOf.put(c, tab);
    }

    private EditorTextField field(String text, boolean viewer) {
        Document document = EditorFactory.getInstance().createDocument(text == null ? "" : text);
        return new EditorTextField(document, project, PlainTextFileType.INSTANCE, viewer, false);
    }

    private void addCase() {
        if (file == null) return;
        OiCaseStore.CaseSet set = OiCaseStore.getInstance(project).setFor(file.getPath());
        OiCase c = new OiCase();
        c.name = "案例 " + (set.cases.size() + 1);
        set.cases.add(c);
        OiCaseStore.getInstance(project).touch();
        addTab(c, set.cases.size() - 1);
        tabs.setSelectedIndex(set.cases.size() - 1);
        statusLabel.setText("已添加 " + c.name);
        updateButtons();
    }

    private void removeCase() {
        if (file == null) return;
        int index = tabs.getSelectedIndex();
        if (index < 0) return;
        OiCaseStore.CaseSet set = OiCaseStore.getInstance(project).setFor(file.getPath());
        if (index >= set.cases.size()) return;
        set.cases.remove(index);
        OiCaseStore.getInstance(project).touch();
        rebuildTabs();
        statusLabel.setText("已删除该案例");
    }

    private void importInOut() {
        if (file == null) return;
        VirtualFile parent = file.getParent();
        if (parent == null) return;
        String stem = file.getNameWithoutExtension();
        VirtualFile in = parent.findChild(stem + ".in");
        VirtualFile out = parent.findChild(stem + ".out");
        if (in == null && out == null) {
            statusLabel.setText("同目录下没找到 " + stem + ".in / " + stem + ".out");
            return;
        }
        OiCase c = new OiCase();
        c.name = stem;
        c.input = readFile(in);
        c.expected = readFile(out);
        OiCaseStore.CaseSet set = OiCaseStore.getInstance(project).setFor(file.getPath());
        set.cases.add(c);
        OiCaseStore.getInstance(project).touch();
        addTab(c, set.cases.size() - 1);
        tabs.setSelectedIndex(set.cases.size() - 1);
        statusLabel.setText("已从 " + stem + ".in / .out 导入案例");
    }

    private static String readFile(VirtualFile file) {
        if (file == null) return "";
        try {
            return VfsUtilCore.loadText(file);
        } catch (Exception e) {
            return "";
        }
    }

    // --------------------------------------------------------------- running

    private void run(boolean onlyCurrent) {
        VirtualFile target = file;
        if (target == null || running) return;
        OiCaseStore store = OiCaseStore.getInstance(project);
        OiCaseStore.CaseSet set = store.setFor(target.getPath());
        List<OiCase> chosen = new ArrayList<>();
        if (onlyCurrent) {
            int index = tabs.getSelectedIndex();
            if (index < 0 || index >= set.cases.size()) return;
            chosen.add(set.cases.get(index));
        } else {
            if (set.cases.isEmpty()) {
                statusLabel.setText("还没有案例，先点「＋ 案例」或导入 .in/.out");
                return;
            }
            chosen.addAll(set.cases);
        }
        int timeLimit = (Integer) timeSpinner.getValue();
        int memoryLimit = (Integer) memorySpinner.getValue();

        running = true;
        updateButtons();
        statusLabel.setText("编译中…");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            OiJudge.Build build = OiJudge.compile(project, target);
            if (build.error != null) {
                onEdt(() -> {
                    running = false;
                    updateButtons();
                    statusLabel.setText("编译失败");
                    markAll(OiJudge.Verdict.CE);
                    Messages.showMessageDialog(project, build.error, "OI: 编译失败", Messages.getErrorIcon());
                });
                return;
            }
            int passed = 0;
            for (int i = 0; i < chosen.size(); i++) {
                OiCase c = chosen.get(i);
                OiJudge.CaseResult result = OiJudge.runCase(build, c, timeLimit, memoryLimit);
                int done = i + 1;
                if (result.verdict == OiJudge.Verdict.AC) passed++;
                int passedNow = passed;
                onEdt(() -> {
                    apply(c, result);
                    statusLabel.setText("运行中… " + done + "/" + chosen.size() + "，通过 " + passedNow);
                });
            }
            int total = passed;
            onEdt(() -> {
                running = false;
                updateButtons();
                statusLabel.setText(total + "/" + chosen.size() + " 通过 · 编译 "
                        + String.format(Locale.ROOT, "%.2f", build.seconds) + " s");
            });
        });
    }

    private void apply(OiCase c, OiJudge.CaseResult result) {
        c.actual = result.actual == null ? "" : result.actual;
        CaseTab tab = tabOf.get(c);
        if (tab == null) return;
        tab.actual.setText(c.actual);
        int index = tabs.indexOfComponent(tab.panel);
        if (index >= 0) {
            tabs.setIconAt(index, iconFor(result.verdict));
            tabs.setToolTipTextAt(index, tooltip(result));
        }
        scheduleHighlight(tab);
    }

    private void markAll(OiJudge.Verdict verdict) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            tabs.setIconAt(i, iconFor(verdict));
        }
    }

    private void updateButtons() {
        boolean target = file != null;
        addButton.setEnabled(target && !running);
        removeButton.setEnabled(target && !running && tabs.getTabCount() > 0);
        runAllButton.setEnabled(target && !running);
        runOneButton.setEnabled(target && !running && tabs.getTabCount() > 0);
        importButton.setEnabled(target && !running);
    }

    // ---------------------------------------------------------------- diffing

    private void scheduleHighlight(CaseTab tab) {
        attemptHighlight(tab, 0);
    }

    private void attemptHighlight(CaseTab tab, int attempt) {
        onEdt(() -> {
            Editor editor = tab.actual.getEditor();
            if (editor == null) {
                if (attempt < 40) {
                    Timer timer = new Timer(80, e -> attemptHighlight(tab, attempt + 1));
                    timer.setRepeats(false);
                    timer.start();
                }
                return;
            }
            applyDiff(editor, tab.c.expected, tab.c.actual);
        });
    }

    /** Paints the lines of {@code actual} that no line of {@code expected} accounts for. */
    private static void applyDiff(Editor editor, String expected, String actual) {
        MarkupModel model = editor.getMarkupModel();
        model.removeAllHighlighters();
        List<String> left = normalizeLines(expected);
        List<String> right = normalizeLines(actual);
        boolean[] matched = lcsMatches(left, right);
        if (matched == null) return;
        TextAttributes wrong = new TextAttributes();
        wrong.setBackgroundColor(new JBColor(new Color(0x30E74C3C, true), new Color(0x40E74C3C, true)));
        int limit = Math.min(right.size(), editor.getDocument().getLineCount());
        for (int line = 0; line < limit; line++) {
            if (line < matched.length && matched[line]) continue;
            model.addLineHighlighter(line, HighlighterLayer.ADDITIONAL_SYNTAX, wrong);
        }
    }

    private static List<String> normalizeLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null) return lines;
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            lines.add(stripTrailing(line));
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) end--;
        return line.substring(0, end);
    }

    /** True for every line of {@code right} that takes part in a longest common subsequence. */
    private static boolean[] lcsMatches(List<String> left, List<String> right) {
        boolean[] matched = new boolean[right.size()];
        if (left.isEmpty() || right.isEmpty()) return matched;
        if ((long) left.size() * right.size() > 4_000_000L) return null;
        int n = left.size();
        int m = right.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = left.get(i).equals(right.get(j))
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (left.get(i).equals(right.get(j))) {
                matched[j] = true;
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        return matched;
    }

    // ---------------------------------------------------------------- helpers

    private static Icon iconFor(OiJudge.Verdict verdict) {
        if (verdict == OiJudge.Verdict.AC) return PASSED;
        if (verdict == OiJudge.Verdict.CE) return FAILED;
        return FAILED;
    }

    private static String tooltip(OiJudge.CaseResult result) {
        StringBuilder sb = new StringBuilder(label(result.verdict));
        if (result.wallSeconds >= 0) sb.append(" · ").append(String.format(Locale.ROOT, "%.3f s", result.wallSeconds));
        if (result.cpuMillis >= 0) sb.append(" · CPU ").append(String.format(Locale.ROOT, "%.0f ms", result.cpuMillis));
        if (result.peakBytes >= 0) sb.append(" · 内存 ").append(Oi.humanBytes(result.peakBytes));
        return sb.toString();
    }

    private static String label(OiJudge.Verdict verdict) {
        switch (verdict) {
            case AC: return "通过";
            case WA: return "答案错误";
            case TLE: return "超时";
            case MLE: return "超内存";
            case CE: return "编译失败";
            default: return "运行错误";
        }
    }

    private static Icon dot(Color color) {
        BufferedImage image = new BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(color);
        g.fillOval(1, 1, 10, 10);
        g.dispose();
        return new ImageIcon(image);
    }

    private static void onEdt(Runnable action) {
        ApplicationManager.getApplication().invokeLater(action);
    }

    @Override
    public void dispose() {
        bus.disconnect();
    }

    /** One case: three stacked editors with a caption each. */
    private static final class CaseTab {
        final OiCase c;
        final EditorTextField input;
        final EditorTextField expected;
        final EditorTextField actual;
        final JPanel panel;

        CaseTab(OiCase c, EditorTextField input, EditorTextField expected, EditorTextField actual) {
            this.c = c;
            this.input = input;
            this.expected = expected;
            this.actual = actual;
            JSplitPane upper = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                    pane("输入", input), pane("期望输出", expected));
            upper.setResizeWeight(0.5);
            JSplitPane all = new JSplitPane(JSplitPane.VERTICAL_SPLIT, upper, pane("实际输出", actual));
            all.setResizeWeight(0.5);
            all.setBorder(null);
            panel = new JPanel(new BorderLayout());
            panel.add(all, BorderLayout.CENTER);
        }

        private static JComponent pane(String caption, JComponent body) {
            JPanel p = new JPanel(new BorderLayout());
            JBLabel label = new JBLabel(" " + caption);
            label.setFont(label.getFont().deriveFont(Font.PLAIN));
            p.add(label, BorderLayout.NORTH);
            p.add(body, BorderLayout.CENTER);
            return p;
        }
    }
}
