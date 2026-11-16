package oi.runner;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
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
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.Font;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
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
    private final JTabbedPane tabs = new JTabbedPane();
    private final JBLabel fileLabel = new JBLabel("未选择 C/C++ 文件");
    private final JBLabel dropHint = new JBLabel("可把文件夹 / .zip 拖到这里导入");
    private final JBLabel statusLabel = new JBLabel(" ");
    private final JSpinner timeSpinner;
    private final JSpinner memorySpinner;
    private final JButton addButton = new JButton("＋ 案例");
    private final JButton removeButton = new JButton("－ 案例");
    private final JButton runAllButton = new JButton("▶ 全部运行");
    private final JButton runOneButton = new JButton("▶ 运行当前");
    private final JButton importButton = new JButton("导入案例…");
    private java.awt.dnd.DropTarget dropTarget;
    private final MessageBusConnection bus;
    private final Map<OiCase, CaseTab> tabOf = new IdentityHashMap<>();
    private Disposable tabDisposable = Disposer.newDisposable("OI Cases tabs");

    private VirtualFile file;
    private boolean running;

    OiCasePanel(@NotNull Project project) {
        this.project = project;
        OiCaseStore store = OiCaseStore.getInstance(project);
        timeSpinner = new JSpinner(new SpinnerNumberModel(store.getTimeLimitMillis(), 50, 60000, 100));
        memorySpinner = new JSpinner(new SpinnerNumberModel(store.getMemoryLimitMb(), 8, 8192, 64));

        headerPanel.add(buildHeader(), BorderLayout.NORTH);
        root.add(headerPanel, BorderLayout.NORTH);
        root.add(tabs, BorderLayout.CENTER);
        statusLabel.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 8, 3, 8));
        root.add(statusLabel, BorderLayout.SOUTH);

        addButton.addActionListener(e -> addCase());
        removeButton.addActionListener(e -> removeCase());
        runAllButton.addActionListener(e -> run(false));
        runOneButton.addActionListener(e -> run(true));
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
        installDropTarget(tabs);
        installDropTarget(statusLabel);
    }

    JComponent getComponent() {
        return root;
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        JPanel top = new JPanel(new BorderLayout());
        top.add(fileLabel, BorderLayout.WEST);
        dropHint.setForeground(JBColor.GRAY);
        dropHint.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 8));
        top.add(dropHint, BorderLayout.EAST);
        header.add(top, BorderLayout.NORTH);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        // Import sits right next to add/remove: it is the button people look for first.
        for (JButton button : new JButton[]{addButton, removeButton, importButton, runAllButton, runOneButton}) {
            button.setFocusable(false);
            buttons.add(button);
        }
        importButton.setToolTipText("导入文件夹里的 *.in / *.out 配对，或一个 .zip；也可以直接把文件夹 / .zip 拖到页签栏");
        addButton.setToolTipText("添加一个空案例，自己填输入和期望输出");
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
        Disposer.dispose(tabDisposable);
        tabDisposable = Disposer.newDisposable("OI Cases tabs");
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
        input.setDisposedWith(tabDisposable);
        expected.setDisposedWith(tabDisposable);
        actual.setDisposedWith(tabDisposable);
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
        tabs.addTab(titleFor(c, null, index), UNKNOWN, tab.panel,
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
        chooser.setDialogTitle("导入案例：选文件夹或 .zip（扫描 *.in / *.out），或 .in 文件（可多选）");
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
                    if (!running) statusLabel.setText(" ");
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

    /** Reads the picked folders, archives and .in files off the EDT, then adds the cases. */
    private void startImport(File[] picked) {
        if (picked == null || picked.length == 0) return;
        VirtualFile target = file;
        if (target == null) {
            statusLabel.setText("先打开一个 C/C++ 文件，再导入案例");
            return;
        }
        statusLabel.setText("导入中…");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<OiCase> imported = new ArrayList<>();
            for (File choice : picked) {
                if (imported.size() >= 200) break;
                String lower = choice.getName().toLowerCase(Locale.ROOT);
                if (choice.isDirectory()) {
                    imported.addAll(casesFromFolder(choice.toPath()));
                } else if (lower.endsWith(".zip")) {
                    imported.addAll(casesFromZip(choice.toPath()));
                } else if (lower.endsWith(".in")) {
                    OiCase c = caseFor(choice.toPath());
                    if (c != null) imported.add(c);
                }
            }
            // A folder can overshoot the cap in one shot; keep the first 200 in natural order.
            while (imported.size() > 200) imported.remove(imported.size() - 1);
            onEdt(() -> finishImport(target, imported));
        });
    }

    private void finishImport(VirtualFile target, List<OiCase> imported) {
        if (file == null || !file.getPath().equals(target.getPath())) return;
        if (imported.isEmpty()) {
            statusLabel.setText("没有找到 .in / .out 配对（文件夹或 .zip 里需要有 1.in 配 1.out）");
            return;
        }
        OiCaseStore.CaseSet set = OiCaseStore.getInstance(project).setFor(target.getPath());
        set.cases.addAll(imported);
        OiCaseStore.getInstance(project).touch();
        rebuildTabs();
        tabs.setSelectedIndex(set.cases.size() - imported.size());
        statusLabel.setText("已导入 " + imported.size() + " 个案例");
    }

    /** All {@code *.in} of one folder, paired with the sibling {@code *.out} or {@code *.ans}. */
    private static List<OiCase> casesFromFolder(Path dir) {
        List<OiCase> result = new ArrayList<>();
        File[] children = dir.toFile().listFiles(File::isFile);
        if (children == null) return result;
        Map<String, Path> inputs = new TreeMap<>(NATURAL);
        Map<String, Path> outputs = new TreeMap<>(NATURAL);
        for (File child : children) {
            String name = child.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".in")) {
                inputs.putIfAbsent(name.substring(0, name.length() - 3), child.toPath());
            } else if (lower.endsWith(".out") || lower.endsWith(".ans")) {
                outputs.putIfAbsent(name.substring(0, name.lastIndexOf('.')), child.toPath());
            }
        }
        for (Map.Entry<String, Path> entry : inputs.entrySet()) {
            OiCase c = new OiCase();
            c.name = entry.getKey();
            c.input = readText(entry.getValue());
            Path out = outputs.get(entry.getKey());
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
                int dot = name.lastIndexOf('.');
                if (dot < 0) continue;
                String extension = name.substring(dot).toLowerCase(Locale.ROOT);
                if (!extension.equals(".in") && !extension.equals(".out") && !extension.equals(".ans")) continue;
                if (entry.getSize() > 4L * 1024 * 1024) continue;
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

    /** UTF-8 when valid, otherwise GBK: Chinese test data is often saved in GBK. */
    private static String readText(Path path) {
        try {
            if (Files.size(path) > 4L * 1024 * 1024) return "";
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
        return text;
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
            Map<OiJudge.Verdict, Integer> tally = new EnumMap<>(OiJudge.Verdict.class);
            for (int i = 0; i < chosen.size(); i++) {
                OiCase c = chosen.get(i);
                OiJudge.CaseResult first = OiJudge.runCase(build, c, timeLimit, memoryLimit);
                if (i == 0 && first.verdict == OiJudge.Verdict.TLE) {
                    // The first launch of a freshly built exe can be delayed by Defender's real-time
                    // scan (+0.15~0.35 s), which shows up as a false TLE. Confirm once before judging.
                    first = OiJudge.runCase(build, c, timeLimit, memoryLimit);
                }
                final OiJudge.CaseResult result = first;
                int done = i + 1;
                tally.merge(result.verdict, 1, Integer::sum);
                onEdt(() -> {
                    apply(c, result);
                    statusLabel.setText("运行中… " + done + "/" + chosen.size() + " · " + summary(tally));
                });
            }
            onEdt(() -> {
                running = false;
                updateButtons();
                statusLabel.setText(summary(tally) + " · 共 " + chosen.size() + " 个案例 · 编译 "
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
            tabs.setTitleAt(index, titleFor(c, result.verdict, index));
            tabs.setToolTipTextAt(index, tooltip(result));
        }
        scheduleHighlight(tab);
    }

    private void markAll(OiJudge.Verdict verdict) {
        for (Map.Entry<OiCase, CaseTab> entry : tabOf.entrySet()) {
            int index = tabs.indexOfComponent(entry.getValue().panel);
            if (index < 0) continue;
            tabs.setIconAt(index, iconFor(verdict));
            tabs.setTitleAt(index, titleFor(entry.getKey(), verdict, index));
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

    /** Tab caption: the case name plus the short verdict once the case has run. */
    private static String titleFor(OiCase c, OiJudge.Verdict verdict, int index) {
        String base = c.name == null ? "" : c.name.trim();
        if (base.isEmpty()) base = "案例 " + (index + 1);
        if (base.length() > 16) base = base.substring(0, 15) + "…";
        return verdict == null ? base : base + "   " + shortLabel(verdict);
    }

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

    private static void onEdt(Runnable action) {
        ApplicationManager.getApplication().invokeLater(action);
    }

    @Override
    public void dispose() {
        bus.disconnect();
        Disposer.dispose(tabDisposable);
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
