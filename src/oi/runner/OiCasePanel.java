package oi.runner;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.ScrollPaneConstants;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Element;
import javax.swing.text.Highlighter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.FlowLayout;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
    private final JPanel headerPanel = new JPanel(new BorderLayout());
    private JTabbedPane tabs;
    private final CardLayout centerLayout = new CardLayout();
    private final JPanel centerPanel = new JPanel(centerLayout);
    private final JBLabel indexingLabel = new JBLabel("正在建立索引，建好后自动载入案例", JBLabel.CENTER);
    private final JBLabel fileLabel = new JBLabel("未选择 C/C++ 文件");
    private final JBLabel statusLabel = new JBLabel(" ");
    private final JSpinner timeSpinner;
    private final JSpinner memorySpinner;
    // Small lettered buttons for case management and execution.
    private final JButton addButton = smallButton("+", "添加案例");
    private final JButton removeButton = smallButton("-", "删除当前案例");
    private final JButton clearButton = smallButton("C", "清空全部案例");
    private final JButton importButton = smallButton("I", "导入案例，也可以直接拖进来");
    private final JButton runAllButton = smallButton("A", "运行全部案例");
    private final JButton runOneButton = smallButton("T", "运行当前案例");
    private final JButton debugButton = toolbarButton("Debug", "用当前案例的输入启动调试", 54);
    private java.awt.dnd.DropTarget dropTarget;
    private final MessageBusConnection bus;
    private final Map<OiCase, CaseTab> tabOf = new IdentityHashMap<>();
    private Disposable tabDisposable = Disposer.newDisposable("OI Cases tabs");

    private VirtualFile file;
    private final Map<VirtualFile, RunState> activeRuns = new HashMap<>();
    private final Map<VirtualFile, String> runMessages = new HashMap<>();
    private final Map<OiCase, OiJudge.CaseResult> results = new WeakHashMap<>();
    private boolean disposed;
    private boolean importing;
    /** True once a smart-mode rebuild is queued, so indexing completion does not stack rebuilds. */
    private boolean waitingForSmart;
    /** While the indexer runs the panel refuses to build editors, so the buttons stay disabled. */
    private boolean indexing;

    OiCasePanel(@NotNull Project project) {
        this.project = project;
        OiCaseStore store = OiCaseStore.getInstance(project);
        timeSpinner = new JSpinner(new SpinnerNumberModel(store.getTimeLimitMillis(), 50, 60000, 100));
        memorySpinner = new JSpinner(new SpinnerNumberModel(store.getMemoryLimitMb(), 8, 8192, 64));

        headerPanel.add(buildHeader(), BorderLayout.NORTH);
        root.add(headerPanel, BorderLayout.NORTH);
        JBLabel empty = new JBLabel("当前文件还没有案例，点 + 添加或点 I 导入", JBLabel.CENTER);
        centerPanel.add(empty, "empty");
        installDropTarget(empty);
        resetTabStrip();
        indexingLabel.setForeground(JBColor.GRAY);
        centerPanel.add(indexingLabel, "indexing");
        root.add(centerPanel, BorderLayout.CENTER);
        statusLabel.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 8, 3, 8));
        root.add(statusLabel, BorderLayout.SOUTH);

        addButton.addActionListener(e -> addCase());
        removeButton.addActionListener(e -> removeCase());
        clearButton.addActionListener(e -> clearCases());
        runAllButton.addActionListener(e -> run(false));
        runOneButton.addActionListener(e -> run(true));
        debugButton.addActionListener(e -> debugCase());
        importButton.addActionListener(e -> importCases());
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
        installDropTarget(headerPanel);
        installDropTarget(statusLabel);
    }

    JComponent getComponent() {
        return root;
    }

    /** Give buttons and limits their own rows so both fit in a narrow tool window. */
    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        fileLabel.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 6, 0, 4));
        header.add(fileLabel, BorderLayout.NORTH);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 1));
        for (JButton button : new JButton[]{addButton, removeButton, clearButton, importButton, runAllButton, runOneButton, debugButton}) {
            buttons.add(button);
        }
        header.add(buttons, BorderLayout.CENTER);

        JPanel limits = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 1));
        timeSpinner.setFocusable(false);
        memorySpinner.setFocusable(false);
        Dimension spinnerSize = new Dimension(JBUI.scale(74), JBUI.scale(22));
        timeSpinner.setPreferredSize(spinnerSize);
        memorySpinner.setPreferredSize(spinnerSize);
        timeSpinner.setToolTipText("每个案例的时限，毫秒");
        memorySpinner.setToolTipText("每个案例的内存上限，MB");
        limits.add(new JBLabel("时限"));
        limits.add(timeSpinner);
        limits.add(new JBLabel("ms"));
        limits.add(new JBLabel("内存"));
        limits.add(memorySpinner);
        limits.add(new JBLabel("MB"));
        header.add(limits, BorderLayout.SOUTH);
        return header;
    }

    private static JButton smallButton(String text, String tooltip) {
        return toolbarButton(text, tooltip, 24);
    }

    private static JButton toolbarButton(String text, String tooltip, int width) {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.setMargin(new Insets(0, 0, 0, 0));
        Dimension size = new Dimension(JBUI.scale(width), JBUI.scale(22));
        button.setPreferredSize(size);
        button.setMinimumSize(size);
        button.setToolTipText(tooltip);
        return button;
    }

    // ------------------------------------------------------------- file tabs

    private void showFile(@NotNull VirtualFile selected) {
        if (file != null && file.getPath().equals(selected.getPath())) return;
        file = selected;
        fileLabel.setText("  " + selected.getName());
        statusLabel.setText(runMessages.getOrDefault(selected, " "));
        rebuildTabs();
    }

    /** A new scroll viewport starts at the leading edge for each file. */
    private void resetTabStrip() {
        if (tabs != null) {
            centerPanel.remove(tabs);
            tabs.setDropTarget(null);
        }
        tabOf.clear();
        tabs = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.addChangeListener(e -> {
            if (e.getSource() == tabs) activate(tabs.getSelectedIndex());
        });
        centerPanel.add(tabs, "tabs");
        installDropTarget(tabs);
    }

    private void showCasesOrEmpty() {
        centerLayout.show(centerPanel, tabs.getTabCount() == 0 ? "empty" : "tabs");
        centerPanel.revalidate();
        centerPanel.repaint();
    }

    private void rebuildTabs() {
        Disposer.dispose(tabDisposable);
        tabDisposable = Disposer.newDisposable("OI Cases tabs");
        resetTabStrip();
        if (file == null) {
            showCasesOrEmpty();
            updateButtons();
            return;
        }
        if (deferWhileIndexing()) return;
        OiCaseStore.CaseSet set = OiCaseStore.getInstance(project).setFor(file.getPath());
        for (int i = 0; i < set.cases.size(); i++) addTab(set.cases.get(i), i);
        showCasesOrEmpty();
        activate(tabs.getSelectedIndex());
        updateButtons();
    }

    /**
     * Only the selected case keeps live editors: switching tabs builds the new one and disposes the
     * old one, so a 200-case set costs three editors instead of six hundred.
     */
    private void activate(int index) {
        if (index < 0 || index >= tabs.getTabCount()) return;
        Component shown = tabs.getComponentAt(index);
        for (CaseTab tab : tabOf.values()) {
            if (tab.panel == shown) tab.ensureBuilt();
            else if (tab.built) tab.release();
        }
    }

    /**
     * Building three editors per case is expensive and competes with the indexer, so while indexing
     * runs the panel keeps the tab strip empty and shows a hint instead; one rebuild is queued for
     * the moment the project becomes smart again.
     */
    private boolean deferWhileIndexing() {
        DumbService dumb = DumbService.getInstance(project);
        if (!dumb.isDumb()) {
            indexing = false;
            return false;
        }
        indexing = true;
        centerLayout.show(centerPanel, "indexing");
        updateButtons();
        if (waitingForSmart) return true;
        waitingForSmart = true;
        dumb.runWhenSmart(() -> {
            waitingForSmart = false;
            indexing = false;
            if (project.isDisposed()) return;
            rebuildTabs();
        });
        return true;
    }

    private void addTab(@NotNull OiCase c, int index) {
        // A tab costs one empty panel here; its three editors are built on first selection.
        // Register it before adding: inserting the first tab selects it right away, and the
        // selection listener has to find the case in order to build its panes.
        CaseTab tab = new CaseTab(c);
        tabOf.put(c, tab);
        tabs.addTab(titleFor(c, index), UNKNOWN, tab.panel);
        OiJudge.CaseResult previous = results.get(c);
        if (previous != null) showResult(tab, previous);
    }

    /**
     * A plain swing text area instead of an IntelliJ editor: one editor per pane cost a full
     * editor setup per case and painted stale glyphs when the text changed during a run, while a
     * text area is cheap, never ghosts and still supports selection, copy and per-line highlighting.
     */
    private static JTextArea textArea(String text, boolean editable) {
        JTextArea area = new JTextArea(normalizeSeparators(text));
        EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12)));
        area.setLineWrap(false);
        area.setWrapStyleWord(false);
        area.setTabSize(4);
        area.setEditable(editable);
        area.setBorder(JBUI.Borders.empty(3, 6));
        area.setBackground(scheme.getDefaultBackground());
        area.setForeground(scheme.getDefaultForeground());
        area.setCaretPosition(0);
        return area;
    }

    /** Fires on every edit, so the model always mirrors what the panes show. */
    private static void onEdit(JTextArea area, Runnable action) {
        area.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent event) {
                action.run();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent event) {
                action.run();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent event) {
                action.run();
            }
        });
    }

    private void addCase() {
        if (file == null || isRunning() || indexing || !Oi.saveSource(project, file)) return;
        OiCaseStore.CaseSet set = OiCaseStore.getInstance(project).setFor(file.getPath());
        OiCase c = new OiCase();
        c.name = "案例 " + (set.cases.size() + 1);
        set.cases.add(c);
        OiCaseStore.getInstance(project).touch();
        addTab(c, set.cases.size() - 1);
        showCasesOrEmpty();
        tabs.setSelectedIndex(set.cases.size() - 1);
        // Selecting an already selected index fires no event, so build the tab explicitly.
        activate(tabs.getSelectedIndex());
        statusLabel.setText("已添加 " + c.name);
        updateButtons();
    }

    /**
     * Removes just the selected tab. Disposing that tab's own scope tears down its three editors,
     * which is what made deleting slow before: rebuilding the strip re-created every other case.
     */
    private void removeCase() {
        if (file == null || isRunning() || indexing || !Oi.saveSource(project, file)) return;
        int index = tabs.getSelectedIndex();
        if (index < 0) return;
        OiCaseStore store = OiCaseStore.getInstance(project);
        OiCaseStore.CaseSet set = store.setFor(file.getPath());
        if (index >= set.cases.size()) return;
        OiCase c = set.cases.remove(index);
        CaseTab tab = tabOf.remove(c);
        if (tab != null) {
            tabs.remove(tab.panel);
            tab.release();
        }
        store.touch();
        showCasesOrEmpty();
        statusLabel.setText("已删除 " + (c.name == null || c.name.isEmpty() ? "该案例" : c.name));
        updateButtons();
    }

    /** Drops every case of the current file after one confirmation, in one step. */
    private void clearCases() {
        if (file == null || isRunning() || indexing || !Oi.saveSource(project, file)) return;
        OiCaseStore store = OiCaseStore.getInstance(project);
        OiCaseStore.CaseSet set = store.setFor(file.getPath());
        int count = set.cases.size();
        if (count == 0) return;
        String name = file.getName();
        String message = count == 1
                ? "清空 " + name + " 的这 1 个案例？删除后不能撤销。"
                : "清空 " + name + " 的全部 " + count + " 个案例？删除后不能撤销。";
        if (Messages.showYesNoDialog(project, message, "清空案例", Messages.getWarningIcon()) != Messages.YES) {
            return;
        }
        set.cases.clear();
        store.touch();
        rebuildTabs();
        statusLabel.setText("已清空 " + count + " 个案例");
    }

    /**
     * Imports cases like CPH-NG does: pick a folder of 1.in/1.out pairs, a .zip of them, or any
     * number of .in files inside the IDE file chooser. Each pair becomes one case named after its
     * base name. The same entry point serves drag and drop.
     */
    private void importCases() {
        if (file == null) {
            statusLabel.setText("先打开一个 C/C++ 文件，再导入案例");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("导入案例");
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setMultiSelectionEnabled(true);
        VirtualFile parent = file.getParent();
        if (parent != null) chooser.setCurrentDirectory(new File(parent.getPath()));

        if (chooser.showOpenDialog(root) != JFileChooser.APPROVE_OPTION) return;
        File[] picked = chooser.getSelectedFiles();
        if (picked.length == 0 && chooser.getSelectedFile() != null) {
            picked = new File[]{chooser.getSelectedFile()};
        }
        startImport(picked);
    }

    /** Accepts folders, .zip archives and .in files dropped on the panel, from any file manager. */
    private void installDropTarget(JComponent component) {
        try {
            dropTarget = new DropTarget(component, DnDConstants.ACTION_COPY_OR_MOVE, new DropTargetAdapter() {
                @Override
                public void dragEnter(DropTargetDragEvent event) {
                    if (event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        event.acceptDrag(DnDConstants.ACTION_COPY);
                        statusLabel.setText("松开鼠标导入案例…");
                    } else {
                        event.rejectDrag();
                    }
                }

                @Override
                public void dragExit(DropTargetEvent event) {
                    if (!isRunning()) statusLabel.setText(runMessages.getOrDefault(file, " "));
                }

                @Override
                public void drop(DropTargetDropEvent event) {
                    if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        event.rejectDrop();
                        return;
                    }
                    try {
                        event.acceptDrop(DnDConstants.ACTION_COPY);
                        Object data = event.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                        List<File> dropped = new ArrayList<>();
                        if (data instanceof List<?>) {
                            for (Object item : (List<?>) data) {
                                if (item instanceof File) dropped.add((File) item);
                            }
                        }
                        event.dropComplete(!dropped.isEmpty());
                        startImport(dropped.toArray(new File[0]));
                    } catch (Exception e) {
                        event.dropComplete(false);
                    }
                }
            }, true);
        } catch (Exception ignored) {
            // Headless environments (the tests) simply have no drag and drop.
        }
    }

    /**
     * Reads the picked folders, archives and .in files as a background task, so the work shows up in
     * the IDE's background-task list with a progress bar and a cancel button instead of silently
     * holding the status bar on "导入中…" while a big folder or archive is read.
     */
    private void startImport(File[] picked) {
        if (picked == null || picked.length == 0 || importing) return;
        final VirtualFile target = file;
        if (target == null) {
            statusLabel.setText("先打开一个 C/C++ 文件，再导入案例");
            return;
        }
        if (isRunning() || indexing || !Oi.saveSource(project, target)) return;
        final File[] choices = picked.clone();
        final List<OiCase> imported = new ArrayList<>();
        importing = true;
        updateButtons();
        new Task.Backgroundable(project, "导入 OI 案例", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);
                for (int i = 0; i < choices.length; i++) {
                    if (indicator.isCanceled()) return;
                    File choice = choices[i];
                    indicator.setText("读取 " + choice.getName());
                    indicator.setFraction((double) i / Math.max(1, choices.length));
                    String lower = choice.getName().toLowerCase(Locale.ROOT);
                    if (choice.isDirectory()) {
                        imported.addAll(casesFromFolder(choice.toPath()));
                    } else if (lower.endsWith(".zip")) {
                        imported.addAll(casesFromZip(choice.toPath()));
                    } else if (lower.endsWith(".in")) {
                        OiCase c = caseFor(choice.toPath());
                        if (c != null) imported.add(c);
                    }
                    indicator.setText2("已读 " + imported.size() + " 个案例");
                    // A folder can overshoot the cap in one shot; keep the first 200 in natural order.
                    if (imported.size() >= 200) break;
                    // Yielding lets the cancel button react on a slow folder.
                    ProgressManager.checkCanceled();
                }
                while (imported.size() > 200) imported.remove(imported.size() - 1);
                indicator.setText("整理案例…");
                indicator.setFraction(1.0);
            }

            @Override
            public void onSuccess() {
                try {
                    finishImport(target, imported);
                } catch (Throwable t) {
                    statusLabel.setText("导入失败：" + t);
                }
            }

            @Override
            public void onCancel() {
                statusLabel.setText("已取消导入");
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                statusLabel.setText("导入失败：" + error);
            }

            @Override
            public void onFinished() {
                importing = false;
                updateButtons();
            }
        }.queue();
    }

    private void finishImport(VirtualFile target, List<OiCase> imported) {
        if (file == null || !file.getPath().equals(target.getPath())) return;
        if (imported.isEmpty()) {
            statusLabel.setText("没有找到配对的 .in / .out");
            return;
        }
        OiCaseStore.CaseSet set = OiCaseStore.getInstance(project).setFor(target.getPath());
        set.cases.addAll(imported);
        OiCaseStore.getInstance(project).touch();
        rebuildTabs();
        tabs.setSelectedIndex(set.cases.size() - imported.size());
        activate(tabs.getSelectedIndex());
        int emptyInput = 0;
        for (OiCase c : imported) {
            if (c.input == null || c.input.isEmpty()) emptyInput++;
        }
        statusLabel.setText("已导入 " + imported.size() + " 个案例"
                + (emptyInput > 0 ? "，" + emptyInput + " 个输入为空" : ""));
    }

    /**
     * Every {@code *.in} under one folder, paired with the {@code *.out} or {@code *.ans} of the same
     * relative path. Sub folders are walked too, because downloaded test data often keeps one folder
     * per point, and the choice made by the file chooser may be any level above them.
     */
    private static List<OiCase> casesFromFolder(Path dir) {
        List<OiCase> result = new ArrayList<>();
        Map<String, Path> inputs = new TreeMap<>(NATURAL);
        Map<String, Path> outputs = new TreeMap<>(NATURAL);
        try (java.util.stream.Stream<Path> walk = Files.walk(dir, 4)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                String relative = dir.relativize(path).toString().replace('\\', '/');
                int dot = relative.lastIndexOf('.');
                if (dot < 0) return;
                String extension = relative.substring(dot).toLowerCase(Locale.ROOT);
                if (!extension.equals(".in") && !extension.equals(".out") && !extension.equals(".ans")) return;
                String stem = relative.substring(0, dot);
                if (extension.equals(".in")) inputs.putIfAbsent(stem, path);
                else outputs.putIfAbsent(stem, path);
            });
        } catch (Exception ignored) {
            // An unreadable folder behaves like an empty one; the panel then reports no pairs.
        }
        for (Map.Entry<String, Path> entry : inputs.entrySet()) {
            OiCase c = new OiCase();
            String stem = entry.getKey();
            c.name = stem.substring(stem.lastIndexOf('/') + 1);
            c.input = readText(entry.getValue());
            Path out = outputs.get(stem);
            c.expected = out == null ? "" : readText(out);
            result.add(c);
            if (result.size() >= 200) break;
        }
        return result;
    }

    /** One .in file and its sibling .out/.ans, used when the user selects files directly. */
    private static OiCase caseFor(Path in) {
        String name = in.getFileName().toString();
        String stem = name.substring(0, name.length() - 3);
        OiCase c = new OiCase();
        c.name = stem;
        c.input = readText(in);
        Path dir = in.getParent();
        for (String extension : new String[]{".out", ".ans"}) {
            Path out = dir.resolve(stem + extension);
            if (Files.isRegularFile(out)) {
                c.expected = readText(out);
                break;
            }
        }
        return c;
    }

    /**
     * Cases inside a .zip: every {@code X.in} pairs with {@code X.out} or {@code X.ans} in the same
     * folder, at any depth. Entry names fall back to GBK because archives made on Chinese Windows
     * rarely set the UTF-8 flag.
     */
    private static List<OiCase> casesFromZip(Path zip) {
        List<OiCase> result = new ArrayList<>();
        try (ZipFile archive = new ZipFile(zip.toFile(), Charset.forName("GBK"))) {
            Map<String, byte[]> inputs = new TreeMap<>(NATURAL);
            Map<String, byte[]> outputs = new TreeMap<>(NATURAL);
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements() && inputs.size() < 200) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                // Finder stores AppleDouble metadata beside the real .in/.ans entries.
                if (("/" + name).contains("/__MACOSX/") || name.substring(name.lastIndexOf('/') + 1).startsWith("._")) continue;
                int dot = name.lastIndexOf('.');
                if (dot < 0) continue;
                String extension = name.substring(dot).toLowerCase(Locale.ROOT);
                if (!extension.equals(".in") && !extension.equals(".out") && !extension.equals(".ans")) continue;
                if (entry.getSize() > MAX_READ_BYTES) continue;
                byte[] bytes;
                try (InputStream in = archive.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                String stem = name.substring(0, dot);
                if (extension.equals(".in")) inputs.putIfAbsent(stem, bytes);
                else outputs.putIfAbsent(stem, bytes);
            }
            for (Map.Entry<String, byte[]> entry : inputs.entrySet()) {
                OiCase c = new OiCase();
                String stem = entry.getKey();
                c.name = stem.substring(stem.lastIndexOf('/') + 1);
                c.input = decodeText(entry.getValue());
                byte[] out = outputs.get(stem);
                c.expected = out == null ? "" : decodeText(out);
                result.add(c);
                if (result.size() >= 200) break;
            }
        } catch (Exception ignored) {
            // An unreadable archive behaves like an empty one; the panel then reports no pairs.
        }
        return result;
    }

    /** Big inputs are legitimate (Luogu's late points run to megabytes); only absurd files are skipped. */
    private static final long MAX_READ_BYTES = 64L * 1024 * 1024;

    /** UTF-8 when valid, otherwise GBK: Chinese test data is often saved in GBK. */
    private static String readText(Path path) {
        try {
            if (Files.size(path) > MAX_READ_BYTES) return "";
            return decodeText(Files.readAllBytes(path));
        } catch (Exception e) {
            return "";
        }
    }

    private static String decodeText(byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            text = new String(bytes, Charset.forName("GBK"));
        }
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        return normalizeSeparators(text);
    }

    /**
     * IntelliJ documents accept only {@code '\n'}; a CR or CRLF reaching {@code createDocument} trips
     * the platform assertion "Wrong line separators" and kills the whole import. Test data from
     * Windows judges is usually CRLF, so fold it here once.
     */
    /** Editor panes only ever hold this much text; the model keeps the full data for judging. */
    private static final int MAX_DISPLAY_CHARS = 120_000;

    private static int textLength(String text) {
        return text == null ? 0 : text.length();
    }

    /** The first {@code MAX_DISPLAY_CHARS} characters of {@code text}, with a note when it was cut. */
    private static String display(String text) {
        if (text == null) return "";
        if (text.length() <= MAX_DISPLAY_CHARS) return text;
        int cut = text.lastIndexOf('\n', MAX_DISPLAY_CHARS);
        if (cut <= 0) cut = MAX_DISPLAY_CHARS;
        return text.substring(0, cut) + "\n…… 其余 " + (text.length() - cut) + " 个字符未显示";
    }

    private static String normalizeSeparators(String text) {
        if (text == null || text.isEmpty()) return "";
        if (text.indexOf('\r') < 0) return text;
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** 1.in, 2.in, 10.in order instead of 1, 10, 2. */
    private static int naturalCompare(String left, String right) {
        int i = 0;
        int j = 0;
        while (i < left.length() && j < right.length()) {
            char a = left.charAt(i);
            char b = right.charAt(j);
            if (Character.isDigit(a) && Character.isDigit(b)) {
                int startA = i;
                int startB = j;
                while (i < left.length() && Character.isDigit(left.charAt(i))) i++;
                while (j < right.length() && Character.isDigit(right.charAt(j))) j++;
                String numberA = left.substring(startA, i).replaceFirst("^0+(?=.)", "");
                String numberB = right.substring(startB, j).replaceFirst("^0+(?=.)", "");
                if (numberA.length() != numberB.length()) return numberA.length() - numberB.length();
                int cmp = numberA.compareTo(numberB);
                if (cmp != 0) return cmp;
            } else {
                int cmp = Character.compare(Character.toLowerCase(a), Character.toLowerCase(b));
                if (cmp != 0) return cmp;
                i++;
                j++;
            }
        }
        return (left.length() - i) - (right.length() - j);
    }

    private static final Comparator<String> NATURAL = OiCasePanel::naturalCompare;

    // --------------------------------------------------------------- running

    private boolean isRunning() {
        return activeRuns.containsKey(file);
    }

    private OiCase selectedCase() {
        Component selected = tabs.getSelectedComponent();
        for (CaseTab tab : tabOf.values()) {
            if (tab.panel == selected) return tab.c;
        }
        return null;
    }

    private void debugCase() {
        VirtualFile target = file;
        if (target == null || isRunning() || indexing) return;
        OiCase c = selectedCase();
        if (c == null || !Oi.saveSource(project, target)) return;
        OiCaseDebug.start(project, target, c.input);
    }

    private void run(boolean onlyCurrent) {
        VirtualFile target = file;
        if (target == null || isRunning() || indexing || importing) return;
        List<OiCase> chosen;
        if (onlyCurrent) {
            OiCase c = selectedCase();
            if (c == null) return;
            chosen = List.of(c);
        } else {
            chosen = List.copyOf(OiCaseStore.getInstance(project).setFor(target.getPath()).cases);
            if (chosen.isEmpty()) {
                statusLabel.setText("还没有案例");
                return;
            }
        }
        if (!Oi.saveSource(project, target)) return;
        int timeLimit = (Integer) timeSpinner.getValue();
        int memoryLimit = (Integer) memorySpinner.getValue();
        List<OiCaseBatch.Input> inputs = chosen.stream()
                .map(c -> new OiCaseBatch.Input(c.input, c.expected)).toList();

        RunState run = new RunState(target, chosen);
        activeRuns.put(target, run);
        updateButtons();
        setRunMessage(target, "编译中…");
        OiCaseBatch.start(project, target, inputs, timeLimit, memoryLimit,
                (index, result) -> onEdt(() -> run.accept(index, result)),
                (seconds, failure) -> onEdt(() -> run.finish(seconds, failure)));
    }

    private final class RunState {
        private final VirtualFile target;
        private final List<OiCase> chosen;
        private final Map<OiJudge.Verdict, Integer> tally = new EnumMap<>(OiJudge.Verdict.class);
        private int done;

        private RunState(VirtualFile target, List<OiCase> chosen) {
            this.target = target;
            this.chosen = chosen;
        }

        private void accept(int index, OiJudge.CaseResult result) {
            OiCase c = chosen.get(index);
            c.actual = normalizeSeparators(result.actual);
            result.actual = ""; // Only keep the verdict and statistics in the transient result cache.
            OiCaseStore.getInstance(project).touch();
            results.put(c, result);
            CaseTab tab = tabOf.get(c);
            if (tab != null) showResult(tab, result);
            tally.merge(result.verdict, 1, Integer::sum);
            setRunMessage(target, "运行中… " + (++done) + "/" + chosen.size() + " · " + summary(tally));
        }

        private void finish(double seconds, String failure) {
            activeRuns.remove(target);
            updateButtons();
            if (failure != null) {
                if (seconds < 0) {
                    for (OiCase c : chosen) {
                        OiJudge.CaseResult result = new OiJudge.CaseResult();
                        result.verdict = OiJudge.Verdict.CE;
                        results.put(c, result);
                        CaseTab tab = tabOf.get(c);
                        if (tab != null) showResult(tab, result);
                    }
                }
                setRunMessage(target, (seconds < 0 ? "编译失败" : "运行失败") + " · " + failure.lines().findFirst().orElse(""));
                if (target.equals(file)) {
                    Messages.showMessageDialog(project, failure, "OI Runner", Messages.getErrorIcon());
                }
                return;
            }
            OiCase single = chosen.size() == 1 ? chosen.getFirst() : null;
            int badLine = single == null ? 0 : mismatchLine(single, results.get(single));
            setRunMessage(target, summary(tally) + " · 共 " + chosen.size() + " 个案例 · 编译 "
                    + Oi.fixed(seconds, 2) + " s" + (badLine > 0 ? " · 第 " + badLine + " 行不匹配" : ""));
        }
    }

    private void setRunMessage(VirtualFile target, String message) {
        runMessages.put(target, message);
        if (target.equals(file)) statusLabel.setText(message);
    }

    private static int mismatchLine(OiCase c, OiJudge.CaseResult result) {
        return result != null && result.verdict == OiJudge.Verdict.WA
                ? OiJudge.firstMismatchLine(c.expected, c.actual) : 0;
    }

    private void showResult(CaseTab tab, OiJudge.CaseResult result) {
        tab.verdict = result.verdict;
        int badLine = mismatchLine(tab.c, result);
        int index = tabs.indexOfComponent(tab.panel);
        if (index >= 0) {
            tabs.setIconAt(index, iconFor(result.verdict));
            tabs.setToolTipTextAt(index, tooltip(result, badLine));
        }
        if (tab.built && result.verdict != OiJudge.Verdict.CE) tab.setActual(tab.c.actual);
    }

    private void updateButtons() {
        boolean ready = file != null && !isRunning() && !indexing;
        boolean hasCase = tabs.getSelectedIndex() >= 0;
        addButton.setEnabled(ready);
        removeButton.setEnabled(ready && hasCase);
        clearButton.setEnabled(ready && hasCase);
        runAllButton.setEnabled(ready && !importing);
        runOneButton.setEnabled(ready && hasCase && !importing);
        debugButton.setEnabled(ready && hasCase);
        importButton.setEnabled(ready && !importing);
    }

    // ---------------------------------------------------------------- diffing

    /** Painted rows are capped: thousands of highlights cost more than they explain. */
    private static final int MAX_DIFF_LINES = 2000;

    /**
     * Marks the rows that differ, line by line: row i of the expected pane is painted when it differs
     * from row i of the actual pane, and vice versa. Rows are paired by their number, never by
     * searching for an equal line later in the other text — that search is what let a shifted answer
     * look like a pass.
     */
    private static void applyDiff(@Nullable JTextArea expectedArea, @Nullable JTextArea actualArea,
                                  String expectedText, String actualText) {
        List<String> expectedLines = OiJudge.lines(expectedText);
        List<String> actualLines = OiJudge.lines(actualText);
        Highlighter.HighlightPainter painter = new DefaultHighlighter.DefaultHighlightPainter(
                new JBColor(new Color(0x33E74C3C, true), new Color(0x55E74C3C, true)));
        paintMismatchedRows(expectedArea, expectedLines, actualLines, painter);
        paintMismatchedRows(actualArea, actualLines, expectedLines, painter);
    }

    private static void paintMismatchedRows(@Nullable JTextArea area, List<String> own, List<String> other,
                                            Highlighter.HighlightPainter painter) {
        if (area == null) return;
        Highlighter highlighter = area.getHighlighter();
        highlighter.removeAllHighlights();
        Element root = area.getDocument().getDefaultRootElement();
        int limit = Math.min(own.size(), root.getElementCount());
        int painted = 0;
        try {
            for (int row = 0; row < limit && painted < MAX_DIFF_LINES; row++) {
                String mine = own.get(row);
                String theirs = row < other.size() ? other.get(row) : null;
                if (mine.equals(theirs)) continue;
                Element element = root.getElement(row);
                highlighter.addHighlight(element.getStartOffset(), element.getEndOffset(), painter);
                painted++;
            }
        } catch (BadLocationException ignored) {
            // The document shrank while painting; the next run repaints anyway.
        }
        // Remove and repaint happen in separate passes here, and swing only damages the ranges it
        // knows about; without this the previous run's red rows could stay painted until a scroll.
        area.repaint();
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Verdict icon: the coloured dot plus the short verdict, painted into one image. Text inside
     * the icon survives the tab strip's caption clipping, which is what ate "AC" before.
     */
    private static Icon iconFor(@Nullable OiJudge.Verdict verdict) {
        if (verdict == null) return UNKNOWN;
        boolean passed = verdict == OiJudge.Verdict.AC;
        Color color = passed ? PASS_COLOR : FAIL_COLOR;
        String text = shortLabel(verdict);
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, JBUI.scale(10));
        int dot = JBUI.scale(8);
        int gap = JBUI.scale(3);
        int pad = JBUI.scale(1);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D measure = probe.createGraphics();
        measure.setFont(font);
        FontMetrics metrics = measure.getFontMetrics();
        measure.dispose();
        int width = pad + dot + gap + metrics.stringWidth(text) + pad;
        int height = Math.max(JBUI.scale(14), metrics.getHeight());
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(color);
        g.fillOval(pad, (height - dot) / 2, dot, dot);
        g.setFont(font);
        g.drawString(text, pad + dot + gap, (height - metrics.getHeight()) / 2 + metrics.getAscent());
        g.dispose();
        return new ImageIcon(image);
    }

    private static String tooltip(OiJudge.CaseResult result, int badLine) {
        StringBuilder sb = new StringBuilder(label(result.verdict));
        if (badLine > 0) sb.append(" · 第 ").append(badLine).append(" 行不匹配");
        if (result.wallSeconds >= 0) sb.append(" · ").append(String.format(Locale.ROOT, "%.3f s", result.wallSeconds));
        if (result.cpuMillis >= 0) sb.append(" · CPU ").append(String.format(Locale.ROOT, "%.0f ms", result.cpuMillis));
        if (result.peakBytes >= 0) sb.append(" · 内存 ").append(Oi.humanBytes(result.peakBytes));
        return sb.toString();
    }

    /**
     * Tab caption is only the case name: the verdict rides on the icon, because a caption long
     * enough to hold both got clipped to "C1 …" while the tab kept the width of the full text.
     */
    private static String titleFor(OiCase c, int index) {
        String base = c.name == null ? "" : c.name.trim();
        if (base.isEmpty()) base = "案例 " + (index + 1);
        return base.length() > 16 ? base.substring(0, 15) + "…" : base;
    }

    private static final Color PASS_COLOR = new Color(0x27AE60);
    private static final Color FAIL_COLOR = new Color(0xE74C3C);

    private static String shortLabel(OiJudge.Verdict verdict) {
        switch (verdict) {
            case AC: return "AC";
            case WA: return "WA";
            case TLE: return "TLE";
            case MLE: return "MLE";
            case CE: return "CE";
            default: return "RE";
        }
    }

    /** "AC 3 · WA 1" style roll-up for the status bar. */
    private static String summary(Map<OiJudge.Verdict, Integer> tally) {
        int total = 0;
        for (int count : tally.values()) total += count;
        if (total == 0) return "还没有结果";
        int passed = tally.getOrDefault(OiJudge.Verdict.AC, 0);
        StringBuilder sb = new StringBuilder("AC ").append(passed);
        for (OiJudge.Verdict verdict : new OiJudge.Verdict[]{
                OiJudge.Verdict.WA, OiJudge.Verdict.TLE, OiJudge.Verdict.MLE,
                OiJudge.Verdict.RE, OiJudge.Verdict.CE}) {
            int count = tally.getOrDefault(verdict, 0);
            if (count > 0) sb.append(" · ").append(shortLabel(verdict)).append(' ').append(count);
        }
        if (passed == total) sb.append(" · 全部通过");
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

    private void onEdt(Runnable action) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!disposed && !project.isDisposed()) action.run();
        });
    }

    @Override
    public void dispose() {
        disposed = true;
        bus.disconnect();
        Disposer.dispose(tabDisposable);
    }

    /** One case: an empty body until selected, then three scrolling text areas with a caption each. */
    private final class CaseTab {
        final OiCase c;
        final JPanel panel = new JPanel(new BorderLayout());
        final JPanel body = new JPanel(new BorderLayout());
        JTextArea input;
        JTextArea expected;
        JTextArea actual;
        OiJudge.Verdict verdict;
        boolean built;
        String expectedShown = "";
        String actualShown = "";

        CaseTab(OiCase c) {
            this.c = c;
            panel.add(body, BorderLayout.CENTER);
        }

        /** Builds the three panes on demand: 200 cases cost one case of widgets, not six hundred. */
        void ensureBuilt() {
            if (built) return;
            built = true;
            String inText = display(c.input);
            boolean inCut = inText.length() < textLength(c.input);
            String expText = display(c.expected);
            boolean expCut = expText.length() < textLength(c.expected);
            expectedShown = expText;
            actualShown = display(c.actual);
            input = textArea(inText, !inCut);
            expected = textArea(expText, !expCut);
            actual = textArea(actualShown, false);
            onEdit(input, () -> {
                c.input = input.getText();
                OiCaseStore.getInstance(project).touch();
            });
            onEdit(expected, () -> {
                c.expected = expected.getText();
                expectedShown = expected.getText();
                OiCaseStore.getInstance(project).touch();
                // Re-diff against the text as it is now: comparing with the copy taken when the tab
                // was built kept the old red lines on screen even after the case passed. Deferred,
                // because this runs inside the expected document's own change notification and the
                // diff paints that very document.
                if (verdict != null) {
                    SwingUtilities.invokeLater(() -> applyDiff(expected, actual, expectedShown, actualShown));
                }
            });
            JSplitPane upper = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                    pane("输入" + (inCut ? " · 已截断" : ""), scrolled(input)),
                    pane("期望输出" + (expCut ? " · 已截断" : ""), scrolled(expected)));
            upper.setResizeWeight(0.5);
            JSplitPane all = new JSplitPane(JSplitPane.VERTICAL_SPLIT, upper,
                    pane("实际输出" + (actualShown.length() < textLength(c.actual) ? " · 已截断" : ""),
                            scrolled(actual)));
            all.setResizeWeight(0.5);
            all.setBorder(null);
            body.removeAll();
            body.add(all, BorderLayout.CENTER);
            body.revalidate();
            body.repaint();
            if (verdict != null) applyDiff(expected, actual, expectedShown, actualShown);
        }

        /** Refreshes the read-only actual pane; large output is cut so the pane stays cheap. */
        void setActual(String text) {
            actualShown = display(text);
            if (actual == null) return;
            if (expected != null) expectedShown = expected.getText();
            actual.getHighlighter().removeAllHighlights();
            actual.setText(actualShown);
            actual.setCaretPosition(0);
            applyDiff(expected, actual, expectedShown, actualShown);
        }

        /** Frees the three panes of a tab the user left, keeping its model data. */
        void release() {
            if (!built) return;
            built = false;
            body.removeAll();
            body.revalidate();
            body.repaint();
            input = null;
            expected = null;
            actual = null;
            expectedShown = "";
            actualShown = "";
        }
    }

    /** Each pane scrolls on its own once its case text is taller than the split share. */
    private static JBScrollPane scrolled(JComponent content) {
        JBScrollPane scroll = new JBScrollPane(content);
        scroll.setBorder(null);
        // Without a modest preferred size a split pane inherits the editor's full text height and
        // collapses its neighbours, which is what made the panes look broken after a run.
        scroll.setPreferredSize(new Dimension(JBUI.scale(320), JBUI.scale(140)));
        scroll.setMinimumSize(new Dimension(0, 0));
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getHorizontalScrollBar().setUnitIncrement(16);
        return scroll;
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
