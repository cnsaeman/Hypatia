package atlantis.hypatia.ui;

import com.formdev.flatlaf.FlatLaf;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import atlantis.hypatia.InstanceServer;
import atlantis.hypatia.Keymap;
import atlantis.hypatia.macro.Macro;
import atlantis.hypatia.macro.MacroFile;
import atlantis.hypatia.macro.MacroRunner;
import atlantis.hypatia.macro.TextTriggers;
import atlantis.hypatia.spell.Dictionaries;
import atlantis.hypatia.spell.SpellChecker;
import atlantis.hypatia.spell.SpellConfig;
import atlantis.hypatia.spell.SpellDictionary;
import atlantis.hypatia.style.StyleLibrary;
import atlantis.hypatia.OpenRequest;
import atlantis.hypatia.Settings;
import atlantis.hypatia.editor.EditorTab;
import atlantis.hypatia.editor.LatexIndenter;
import atlantis.hypatia.tex.CommandTemplate;
import atlantis.hypatia.tex.LogParser;
import atlantis.hypatia.tex.LogParser.Problem;
import atlantis.hypatia.tex.LogParser.Severity;
import atlantis.hypatia.tex.ProcessRunner;
import atlantis.hypatia.tex.RootResolver;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextAreaEditorKit;
import org.fife.ui.rtextarea.RTextArea;

/** The main window. */
public class MainFrame extends JFrame {

    public static final String VERSION = "0.1.0";

    private final Settings settings;
    private final InstanceServer instanceServer;

    private final JTabbedPane tabs = new JTabbedPane();
    private final FindBar findBar = new FindBar(this::currentArea);
    private final LogPanel logPanel = new LogPanel(this::openProblem, () -> showLog(false));
    private final WelcomePanel welcome;
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final JSplitPane mainSplit;
    private int logDivider = -1;

    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel rootLabel = new JLabel();
    private final JLabel positionLabel = new JLabel();
    private final JLabel encodingLabel = new JLabel();
    private final JLabel spellLabel = new JLabel();

    private final ProcessRunner runner = new ProcessRunner();
    private SwingWorker<Integer, String> build;
    private Path lastRoot;
    private ProblemsWindow problemsWindow;
    private JButton buildButton;
    private Action buildAction;
    private JCheckBoxMenuItem darkItem;
    private JCheckBoxMenuItem wrapItem;
    private JToggleButton wrapButton;
    private MacroFile macros = MacroFile.load();
    private SpellConfig spellConfig = SpellConfig.load();
    private StyleLibrary styleLibrary;
    private JCheckBoxMenuItem spellItem;
    private Keymap keymap = Keymap.load(macros.keyMacroNames());
    private final Map<String, Runnable> commands = new java.util.LinkedHashMap<>();
    private final Map<String, java.util.function.Consumer<KeyStroke>> accelerators = new HashMap<>();
    private final Map<javax.swing.AbstractButton, String[]> toolTips = new HashMap<>();
    private final ReferenceInserter references = new ReferenceInserter(this);
    private final LabelNavigator labelNavigator = new LabelNavigator(this);
    private boolean checkingDisk;
    private final atlantis.hypatia.Positions positions = atlantis.hypatia.Positions.load();
    /** Detached editors and their windows (in order of detaching). */
    private final Map<EditorTab, DetachedWindow> detached = new java.util.LinkedHashMap<>();
    /** The detached editor whose window was focused last, or null when the main window was. */
    private EditorTab activeDetached;
    private final javax.swing.Timer outlineTimer = new javax.swing.Timer(600, e -> refreshOutline(current()));

    public MainFrame(Settings settings, InstanceServer instanceServer) {
        super("Hypatia");
        this.settings = settings;
        this.instanceServer = instanceServer;
        setIconImages(Icons.appImages());
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

        welcome = new WelcomePanel(this::newFile, this::openDialog, this::open);

        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.putClientProperty("JTabbedPane.tabClosable", true);
        tabs.putClientProperty("JTabbedPane.tabCloseCallback",
                (BiConsumer<JTabbedPane, Integer>) (pane, i) -> closeTab((EditorTab) pane.getComponentAt(i)));
        tabs.putClientProperty("JTabbedPane.showContentSeparator", true);
        tabs.addChangeListener(e -> onTabChanged());

        JPanel editorPanel = new JPanel(new BorderLayout());
        editorPanel.add(tabs, BorderLayout.CENTER);
        editorPanel.add(findBar, BorderLayout.SOUTH);
        center.add(welcome, "welcome");
        center.add(editorPanel, "editor");

        mainSplit = new QuietSplitPane(JSplitPane.VERTICAL_SPLIT, center, logPanel);
        mainSplit.setResizeWeight(1.0);
        mainSplit.setBorder(null);
        mainSplit.setContinuousLayout(true);
        logPanel.setPreferredSize(new Dimension(800, 220));
        logPanel.setVisible(false);

        setJMenuBar(createMenuBar());
        add(createToolBar(), BorderLayout.NORTH);
        add(mainSplit, BorderLayout.CENTER);
        add(createStatusBar(), BorderLayout.SOUTH);

        setTransferHandler(new FileDropHandler());
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { quit(); }
            @Override public void windowActivated(WindowEvent e) { SwingUtilities.invokeLater(MainFrame.this::checkFilesOnDisk); }
            @Override public void windowDeactivated(WindowEvent e) { rememberPositions(); }
        });

        applyKeymap();
        reportConfigProblems(null);
        if (atlantis.hypatia.Platform.MAC) installMacHandlers();
        else installSuperKeySupport();
        addWindowFocusListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                if (activeDetached != null) {
                    activeDetached = null;
                    updateView();
                    refreshOutline(current());
                }
            }
        });
        outlineTimer.setRepeats(false);
        restoreBounds();
        updateView();
    }

    // =========================================================================
    // UI construction
    // =========================================================================

    private JToolBar createToolBar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        tb.add(toolButton(Icons.Glyph.NEW, "New", "file.new"));
        tb.add(toolButton(Icons.Glyph.OPEN, "Open", "file.open"));
        tb.add(toolButton(Icons.Glyph.SAVE, "Save", "file.save"));
        tb.addSeparator();
        buildButton = toolButton(Icons.Glyph.BUILD, "Build", "latex.build");
        buildButton.setText("Build");
        tb.add(buildButton);
        JButton view = toolButton(Icons.Glyph.VIEW, "Show this line in the PDF viewer", "latex.view");
        view.setText("View");
        tb.add(view);
        tb.addSeparator();
        tb.add(toolButton(Icons.Glyph.CITE, "Insert citation", "latex.cite"));
        tb.add(toolButton(Icons.Glyph.LABEL, "Insert reference", "latex.ref"));
        tb.addSeparator();
        tb.add(toolButton(Icons.Glyph.OUTLINE, "Structure sidebar", "view.outline"));
        tb.add(toolButton(Icons.Glyph.FIND, "Find", "edit.find"));
        tb.add(toolButton(Icons.Glyph.LOG, "Build log", "view.log"));
        tb.addSeparator();
        wrapButton = new JToggleButton(Icons.of(Icons.Glyph.WRAP), settings.softWrap());
        wrapButton.setFocusable(false);
        wrapButton.addActionListener(e -> setWrap(wrapButton.isSelected()));
        toolTips.put(wrapButton, new String[] {"Wrap long lines", "view.wrap"});
        tb.add(wrapButton);
        tb.add(Box.createHorizontalGlue());
        tb.add(toolButton(Icons.Glyph.SETTINGS, "Settings", "file.settings"));
        return tb;
    }

    /** A toolbar button running command {@code id}; its tooltip shows the current shortcut. */
    private JButton toolButton(Icons.Glyph glyph, String tip, String id) {
        JButton b = new JButton(Icons.of(glyph));
        b.setFocusable(false);
        b.addActionListener(e -> run(id));
        toolTips.put(b, new String[] {tip, id});
        return b;
    }

    private JComponent createStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(
                Theme.line(1, 0, 0, 0),
                BorderFactory.createEmptyBorder(3, 10, 3, 10)));
        bar.add(statusLabel, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 16, 0));
        right.setOpaque(false);
        right.add(rootLabel);
        right.add(positionLabel);
        right.add(spellLabel);
        right.add(encodingLabel);
        spellLabel.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        spellLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                JPopupMenu m = new JPopupMenu();
                fillLanguageMenu(m);
                m.show(spellLabel, 0, -m.getPreferredSize().height);
            }
        });
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private JMenuBar createMenuBar() {
        JMenuBar mb = new JMenuBar();

        JMenu file = new JMenu("File");
        file.setMnemonic('F');
        file.add(item("file.new", "New", this::newFile));
        file.add(item("file.open", "Open…", this::openDialog));
        JMenu recent = new JMenu("Open Recent");
        recent.addMenuListener(onMenuSelected(() -> {
            recent.removeAll();
            for (Path p : settings.recentFiles()) {
                if (Files.exists(p)) recent.add(plainItem(p.toString(), () -> open(p)));
            }
            if (recent.getItemCount() == 0) recent.add(disabled("No recent files"));
        }));
        file.add(recent);
        file.addSeparator();
        file.add(item("file.save", "Save", () -> save(current())));
        file.add(item("file.saveAs", "Save As…", () -> saveAs(current())));
        file.add(item("file.saveAll", "Save All", this::saveAll));
        file.add(item("file.close", "Close Tab", () -> closeTab(current())));
        file.addSeparator();
        file.add(item("file.settings", "Settings…", this::openSettings));
        file.add(item("file.keys", "Keyboard Shortcuts…", this::editKeymap));
        file.add(item("file.macros", "Macros…", this::editMacros));
        if (!atlantis.hypatia.Platform.MAC) { // on a Mac: Hypatia > Quit Hypatia
            file.addSeparator();
            file.add(item("file.quit", "Quit", this::quit));
        }
        mb.add(file);

        JMenu edit = new JMenu("Edit");
        edit.setMnemonic('E');
        edit.add(areaItem("edit.undo", "Undo", RTextArea::undoLastAction));
        edit.add(areaItem("edit.redo", "Redo", RTextArea::redoLastAction));
        edit.addSeparator();
        edit.add(areaItem("edit.cut", "Cut", RTextArea::cut));
        edit.add(areaItem("edit.copy", "Copy", RTextArea::copy));
        edit.add(areaItem("edit.paste", "Paste", RTextArea::paste));
        edit.add(areaItem("edit.selectAll", "Select All", RTextArea::selectAll));
        edit.addSeparator();
        edit.add(item("edit.find", "Find…", () -> findBar().open(false)));
        edit.add(item("edit.replace", "Replace…", () -> findBar().open(true)));
        edit.add(item("edit.refactor", "Refactor Selection…", this::refactor));
        edit.add(item("edit.findNext", "Find Next", () -> findBar().find(true)));
        edit.add(item("edit.findPrevious", "Find Previous", () -> findBar().find(false)));
        edit.add(item("edit.goToLine", "Go to Line…", this::goToLineDialog));
        edit.addSeparator();
        edit.add(areaItem("edit.reindent", "Re-indent Environments", LatexIndenter::reindent));
        edit.add(areaItem("edit.toggleComment", "Toggle Comment", a ->
                new RSyntaxTextAreaEditorKit.ToggleCommentAction()
                        .actionPerformedImpl(new ActionEvent(a, ActionEvent.ACTION_PERFORMED, "toggle"), a)));
        mb.add(edit);

        JMenu view = new JMenu("View");
        view.setMnemonic('V');
        darkItem = new JCheckBoxMenuItem("Dark Theme", settings.isDark());
        darkItem.addActionListener(e -> {
            settings.setDark(darkItem.isSelected());
            settings.save();
            applySettings(true);
        });
        register("view.darkTheme", darkItem::doClick, darkItem::setAccelerator);
        view.add(darkItem);
        wrapItem = new JCheckBoxMenuItem("Wrap Long Lines", settings.softWrap());
        wrapItem.addActionListener(e -> setWrap(wrapItem.isSelected()));
        register("view.wrap", wrapItem::doClick, wrapItem::setAccelerator);
        view.add(wrapItem);
        spellItem = new JCheckBoxMenuItem("Check Spelling as You Type", settings.spellEnabled());
        spellItem.addActionListener(e -> {
            settings.setBool("spell.enabled", spellItem.isSelected());
            settings.save();
            recheckSpelling();
            updateStatus();
        });
        register("view.spelling", spellItem::doClick, spellItem::setAccelerator);
        view.add(spellItem);
        view.addSeparator();
        view.add(item("view.fontLarger", "Larger Font", () -> zoom(+1)));
        view.add(item("view.fontSmaller", "Smaller Font", () -> zoom(-1)));
        view.add(item("view.fontReset", "Default Font Size", () -> zoom(0)));
        view.addSeparator();
        view.add(item("view.log", "Build Log", () -> showLog(!logPanel.isVisible())));
        view.add(item("view.problems", "Errors and Warnings", this::showProblems));
        view.add(item("view.outline", "Structure Sidebar", this::toggleOutline));
        view.add(item("view.detach", "Detach Editor / Put Back", () -> toggleDetach(current())));
        mb.add(view);

        JMenu latex = new JMenu("LaTeX");
        latex.setMnemonic('L');
        JMenuItem buildItem = item("latex.build", "Build", this::buildOrStop);
        buildAction = buildItem.getAction();
        latex.add(buildItem);
        latex.add(item("latex.runLatex", "Run pdfLaTeX", () -> run(Tool.LATEX)));
        latex.add(item("latex.runBibtex", "Run BibTeX", () -> run(Tool.BIBTEX)));
        latex.addSeparator();
        latex.add(item("latex.view", "Show in PDF Viewer", this::forwardSearch));
        latex.add(item("latex.viewNew", "Open PDF in New Viewer", () -> forwardSearch(settings.newViewerCommand())));
        latex.addSeparator();
        latex.add(item("latex.cite", "Insert Citation…", this::insertCitation));
        latex.add(item("latex.ref", "Insert Reference…", this::insertReference));
        latex.add(item("latex.goToLabel", "Go to Label", () -> labelNavigator.goToDefinition()));
        latex.add(item("latex.labelUses", "Show Uses of Label…", () -> labelNavigator.showUses()));
        latex.add(item("latex.switchBib", "Switch BibTeX File…", this::switchBibliography));
        latex.addSeparator();
        latex.add(item("latex.spellcheck", "Spellcheck Document…", this::spellcheckDocument));
        JMenu langMenu = new JMenu("Spelling Language");
        langMenu.addMenuListener(onMenuSelected(() -> {
            langMenu.removeAll();
            fillLanguageMenu(langMenu.getPopupMenu());
        }));
        latex.add(langMenu);
        latex.add(item("latex.downloadDictionary", "Download Dictionary…", this::downloadDictionary));
        latex.add(item("latex.userDictionary", "Edit User Dictionary…", this::editUserDictionary));
        latex.add(item("latex.spellConfig", "Edit Spell-Check Commands…", this::editSpellConfig));
        latex.addSeparator();
        latex.add(item("latex.setMain", "Set Main File…", () -> {
            EditorTab t = current();
            if (t != null && t.file() != null) {
                chooseMainFile(t);
                updateStatus();
            }
        }));
        latex.add(item("latex.forgetMain", "Forget Main File", () -> {
            EditorTab t = current();
            if (t != null && t.file() != null) {
                settings.setRootFor(t.file(), null);
                settings.save();
                updateStatus();
            }
        }));
        mb.add(latex);
        mb.add(createStyleMenu());

        JMenu help = new JMenu("Help");
        help.add(item("help.manual", "View Manual", this::showManual));
        help.add(plainItem("Command Line && PDF Viewer…", this::showCommandLineHelp));
        help.add(plainItem("Check TeX Installation…", this::showTexCheck));
        help.add(plainItem("About Hypatia", this::showAbout));
        mb.add(help);
        return mb;
    }

    private JMenu createStyleMenu() {
        JMenu style = new JMenu("Style");
        style.setMnemonic('S');
        style.add(item("style.check", "Check Style", () -> checkStyle(null)));
        JMenu with = new JMenu("Check with Style");
        with.addMenuListener(onMenuSelected(() -> {
            with.removeAll();
            StyleLibrary lib = styleLibrary();
            for (String n : lib.names()) with.add(plainItem(n, () -> checkStyle(n)));
            if (lib.names().isEmpty()) with.add(disabled("No styles in styles.cfg"));
        }));
        style.add(with);
        style.add(item("style.clear", "Clear Style Marks", () -> {
            EditorTab t = current();
            if (t != null) clearStyle(t);
        }));
        style.addSeparator();
        style.add(item("style.next", "Next Style Problem", () -> goStyleProblem(true)));
        style.add(item("style.previous", "Previous Style Problem", () -> goStyleProblem(false)));
        style.addSeparator();
        style.add(item("style.edit", "Edit Styles…", () -> {
            styleLibrary(); // creates the folder with examples
            open(StyleLibrary.configFile());
            status("Save styles.cfg to apply changes.", false);
        }));
        JMenu files = new JMenu("Edit Regular Expressions");
        files.addMenuListener(onMenuSelected(() -> {
            files.removeAll();
            styleLibrary();
            try (var st = Files.list(StyleLibrary.regexpDir())) {
                st.filter(Files::isRegularFile).sorted()
                        .forEach(f -> files.add(plainItem(f.getFileName().toString(), () -> open(f))));
            } catch (IOException ignored) {
                // no folder
            }
            if (files.getItemCount() == 0) files.add(disabled("No files in styles/regexps"));
        }));
        style.add(files);
        style.add(plainItem("Open Styles Folder", () -> {
            styleLibrary();
            try {
                atlantis.hypatia.Platform.open(StyleLibrary.dir());
            } catch (IOException e) {
                status("Styles folder: " + StyleLibrary.dir(), false);
            }
        }));
        return style;
    }

    // --- commands and shortcuts --------------------------------------------------------

    /** A menu item for command {@code id}, with the shortcut from keys.properties. */
    private JMenuItem item(String id, String text, Runnable r) {
        Action a = new AbstractAction(text.replace("&&", "&")) {
            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        };
        register(id, r, ks -> a.putValue(Action.ACCELERATOR_KEY, ks));
        return new JMenuItem(a);
    }

    private JMenuItem areaItem(String id, String text, java.util.function.Consumer<RSyntaxTextArea> c) {
        return item(id, text, () -> {
            RSyntaxTextArea a = currentArea();
            if (a != null) c.accept(a);
        });
    }

    /** A menu item without a configurable shortcut. */
    private static JMenuItem plainItem(String text, Runnable r) {
        JMenuItem i = new JMenuItem(text.replace("&&", "&"));
        i.addActionListener(e -> r.run());
        return i;
    }

    private void register(String id, Runnable r, java.util.function.Consumer<KeyStroke> accelerator) {
        commands.put(id, r);
        accelerators.put(id, accelerator);
        accelerator.accept(keymap.get(id));
    }

    private void run(String id) {
        Runnable r = commands.get(id);
        if (r != null) r.run();
    }

    /**
     * Applies the keymap: menu accelerators, toolbar tooltips, and bindings on every
     * text area (so configured keys win over the editor's built-in ones).
     */
    private void applyKeymap() {
        registerMacroCommands();
        accelerators.forEach((id, setter) -> setter.accept(keymap.get(id)));
        toolTips.forEach((button, tip) -> {
            KeyStroke ks = keymap.get(tip[1]);
            button.setToolTipText(ks == null ? tip[0] : tip[0] + " (" + Keymap.format(ks) + ")");
        });
        tabs.putClientProperty("JTabbedPane.tabCloseToolTipText",
                keymap.get("file.close") == null ? "Close" : "Close (" + Keymap.format(keymap.get("file.close")) + ")");
        for (EditorTab t : allTabs()) installKeys(t.area());
    }

    @SuppressWarnings("unchecked")
    private void installKeys(RSyntaxTextArea area) {
        javax.swing.InputMap im = area.getInputMap();
        Object old = area.getClientProperty("hypatia.keys");
        if (old instanceof List<?> list) {
            for (Object ks : list) im.remove((KeyStroke) ks);
        }
        List<KeyStroke> installed = new ArrayList<>();
        for (String id : commands.keySet()) {
            KeyStroke ks = keymap.get(id);
            if (ks == null) continue;
            // Keep the editor's own undo/cut/paste/... bindings unless the shortcut was changed.
            if (EDITOR_NATIVE.contains(id) && ks.equals(Keymap.parse(defaultKey(id)))) continue;
            im.put(ks, "hypatia." + id);
            area.getActionMap().put("hypatia." + id, new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { run(id); }
            });
            installed.add(ks);
        }
        area.putClientProperty("hypatia.keys", installed);
    }

    private boolean superDown;
    private boolean swallowTyped;

    /**
     * Java on Linux delivers the Windows/Super key as a key of its own (VK_WINDOWS), not as the
     * Meta modifier, so "Meta+V" never matches. Track the key here and run Meta shortcuts
     * ourselves.
     */
    private void installSuperKeySupport() {
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (e.getKeyCode() == KeyEvent.VK_WINDOWS) {
                superDown = e.getID() == KeyEvent.KEY_PRESSED;
                return false;
            }
            if (e.getID() == KeyEvent.KEY_TYPED && swallowTyped) {
                swallowTyped = false;
                e.consume();
                return true; // the letter of a handled Super+letter
            }
            if (!superDown || e.getID() != KeyEvent.KEY_PRESSED
                    || (e.getModifiersEx() & InputEvent.META_DOWN_MASK) != 0 // real Meta already works
                    || SwingUtilities.getWindowAncestor(e.getComponent()) != this) {
                return false;
            }
            KeyStroke ks = KeyStroke.getKeyStroke(e.getKeyCode(), e.getModifiersEx() | InputEvent.META_DOWN_MASK);
            String id = keymap.idFor(ks);
            if (id == null || !commands.containsKey(id)) return false;
            swallowTyped = true;
            e.consume();
            SwingUtilities.invokeLater(() -> run(id));
            return true;
        });
        // A Super release swallowed by the desktop (e.g. it opened a menu) must not leave us stuck.
        addWindowFocusListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowLostFocus(java.awt.event.WindowEvent e) {
                superDown = false;
            }
        });
    }

    /** macOS: About, Settings and Quit in the application menu, files opened from the Finder. */
    private void installMacHandlers() {
        if (!java.awt.Desktop.isDesktopSupported()) return;
        java.awt.Desktop d = java.awt.Desktop.getDesktop();
        try {
            if (d.isSupported(java.awt.Desktop.Action.APP_ABOUT)) d.setAboutHandler(e -> SwingUtilities.invokeLater(this::showAbout));
            if (d.isSupported(java.awt.Desktop.Action.APP_PREFERENCES)) d.setPreferencesHandler(e -> SwingUtilities.invokeLater(this::openSettings));
            if (d.isSupported(java.awt.Desktop.Action.APP_QUIT_HANDLER)) {
                d.setQuitHandler((e, response) -> {
                    response.cancelQuit(); // quit() exits itself unless the user cancels
                    SwingUtilities.invokeLater(this::quit);
                });
            }
            if (d.isSupported(java.awt.Desktop.Action.APP_OPEN_FILE)) {
                d.setOpenFileHandler(e -> SwingUtilities.invokeLater(() -> {
                    for (java.io.File f : e.getFiles()) open(f.toPath());
                }));
            }
        } catch (RuntimeException ignored) {
            // not supported by this Java
        }
    }

    /** "Ctrl+U" for a command with a shortcut, else "LaTeX > {@code menuItem}". */
    String shortcutText(String id, String menuItem) {
        KeyStroke ks = keymap.get(id);
        return ks == null ? "LaTeX > " + menuItem : Keymap.format(ks);
    }

    private String shortcutHint(String id) {
        KeyStroke ks = keymap.get(id);
        return ks == null ? "" : " (" + Keymap.format(ks) + ")";
    }

    private static final java.util.Set<String> EDITOR_NATIVE = java.util.Set.of(
            "edit.undo", "edit.redo", "edit.cut", "edit.copy", "edit.paste", "edit.selectAll",
            "edit.toggleComment");

    private static String defaultKey(String id) {
        return Keymap.COMMANDS.stream().filter(c -> c.id().equals(id))
                .map(Keymap.Command::defaultKey).findFirst().orElse("");
    }

    // --- citations and references ----------------------------------------------------------

    private void insertCitation() {
        references.insertCitation();
    }

    private void insertReference() {
        references.insertReference();
    }

    Settings settings() {
        return settings;
    }

    /** Main file for {@code tab} (asking once if needed), or null with a status message. */
    Path projectRoot(EditorTab tab) {
        if (tab.file() == null) {
            status("Save the file first, so Hypatia can find its project", true);
            return null;
        }
        return resolveRoot(tab, true);
    }

    /** Text of a file: from its editor tab if open (including unsaved changes), else from disk. */
    String textOf(Path p) {
        String open = openText(p);
        return open != null ? open : atlantis.hypatia.tex.TextFiles.readOrNull(p);
    }

    /** Text of a file if it is open in a tab, else null. */
    String openText(Path p) {
        EditorTab t = findTab(p);
        return t == null ? null : t.area().getText();
    }

    private void editKeymap() {
        Keymap.load(macros.keyMacroNames()); // makes sure the file exists and lists every command
        open(Keymap.file());
        status("Edit the shortcuts and save; they apply immediately.", false);
    }

    private void reloadKeymap() {
        keymap = Keymap.load(macros.keyMacroNames());
        applyKeymap();
        reportConfigProblems("Keyboard shortcuts reloaded");
    }

    // --- macros -----------------------------------------------------------------------------

    private void editMacros() {
        if (!java.nio.file.Files.exists(MacroFile.file())) macros = MacroFile.load(); // writes the examples
        open(MacroFile.file());
        status("Edit the macros and save; they apply immediately.", false);
    }

    private void reloadMacros() {
        macros = MacroFile.load();
        keymap = Keymap.load(macros.keyMacroNames());
        applyKeymap();
        reportConfigProblems(macros.macros().size() + " macros loaded");
    }

    /** Commands "runmacro.name" for the shortcut-triggered macros. */
    private void registerMacroCommands() {
        commands.keySet().removeIf(id -> id.startsWith("runmacro."));
        for (Macro m : macros.macros()) {
            if (m.trigger() == Macro.Trigger.KEY) commands.put(m.keyId(), () -> runMacro(m));
        }
    }

    private void runMacro(Macro m) {
        RSyntaxTextArea a = currentArea();
        if (a == null) return;
        try {
            MacroRunner.run(a, m, 0);
        } catch (MacroRunner.MacroException e) {
            status(e.getMessage(), true);
        }
    }

    // --- spelling -----------------------------------------------------------------------------

    private static boolean isTexFile(EditorTab tab) {
        return tab.file() == null || tab.file().toString().matches("(?i).*\\.(tex|ltx)$");
    }

    /**
     * Spelling language of a document: its "% !TeX spellcheck" line, else that of its main
     * file, else the default from Settings. Null when there is none or it is not a .tex file.
     */
    private String spellLanguage(EditorTab tab, String text) {
        if (!isTexFile(tab)) return null;
        String lang = Dictionaries.magicLanguage(text);
        if (lang == null && tab.file() != null) {
            RootResolver.Result r = RootResolver.resolve(tab.file(), text, settings);
            if (r != null && !r.root().equals(tab.file())) {
                String rootText = textOf(r.root());
                if (rootText != null) lang = Dictionaries.magicLanguage(rootText);
            }
        }
        if (lang == null) lang = settings.spellLanguage();
        return lang == null || lang.isBlank() ? null : Dictionaries.normalize(lang);
    }

    private void recheckSpelling() {
        for (EditorTab t : allTabs()) {
            if (t.spellChecker() != null) t.spellChecker().recheck();
        }
    }

    // --- style checks ---------------------------------------------------------------------------

    /** The loaded styles (loaded on first use). */
    private StyleLibrary styleLibrary() {
        if (styleLibrary == null) styleLibrary = StyleLibrary.load();
        return styleLibrary;
    }

    /** Reloads styles.cfg, regexp files and plugins (picks up recompiled plugins). */
    private void reloadStyles(boolean report) {
        StyleLibrary old = styleLibrary;
        styleLibrary = StyleLibrary.load();
        if (old != null) old.close();
        if (report) {
            List<String> p = styleLibrary.problems();
            status(p.isEmpty() ? "Styles reloaded: " + String.join(", ", styleLibrary.names()) : String.join("; ", p),
                    !p.isEmpty());
        }
        for (EditorTab t : allTabs()) t.styleMarks().refresh();
    }

    /** The file's style: "% !TeX style" in the file or its main file, else the default style. */
    private String fileStyle(EditorTab tab) {
        String text = tab.area().getText();
        String name = StyleLibrary.magicStyle(text);
        if (name == null && tab.file() != null) {
            RootResolver.Result r = RootResolver.resolve(tab.file(), text, settings);
            if (r != null && !r.root().equals(tab.file())) {
                String rootText = textOf(r.root());
                if (rootText != null) name = StyleLibrary.magicStyle(rootText);
            }
        }
        return name != null ? name : styleLibrary().defaultName();
    }

    /** Checks the current file with style {@code name} (null: the file's style) and keeps checking. */
    private void checkStyle(String name) {
        EditorTab tab = current();
        if (tab == null) return;
        if (!isTexFile(tab)) {
            status("Style checks work on .tex files", true);
            return;
        }
        reloadStyles(false);
        String n = name != null ? name : fileStyle(tab);
        List<String> problems = styleLibrary.problems();
        if (n == null) {
            status("No styles defined: Style > Edit Styles", true);
            return;
        }
        if (styleLibrary.get(n) == null) {
            status("No style called “" + n + "” in styles.cfg (styles: " + String.join(", ", styleLibrary.names()) + ")", true);
            return;
        }
        tab.styleMarks().activate(n);
        status(problems.isEmpty() ? "Checking with style “" + n + "”" : String.join("; ", problems), !problems.isEmpty());
    }

    private void clearStyle(EditorTab tab) {
        if (tab.styleMarks().isActive()) status("Style marks cleared", false);
        tab.styleMarks().clear();
        tab.area().requestFocusInWindow();
    }

    private void goStyleProblem(boolean forward) {
        EditorTab tab = current();
        if (tab == null) return;
        if (!tab.styleMarks().isActive()) {
            status("No style check active: Style > Check Style" + shortcutHint("style.check"), true);
        } else if (!tab.styleMarks().go(forward)) {
            status("No style problems", false);
        }
    }

    private void spellcheckDocument() {
        EditorTab tab = current();
        if (tab == null) return;
        String lang = spellLanguage(tab, tab.area().getText());
        if (lang == null) {
            status(isTexFile(tab) ? "No spelling language: add \"% !TeX spellcheck = en_GB\" or set a default in Settings"
                    : "Spell checking works on .tex files", true);
            return;
        }
        SpellDictionary dict;
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        try {
            dict = Dictionaries.get(lang).get(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            error(c.getMessage() + "\n\nUse LaTeX > Download Dictionary, or put "
                    + lang + ".aff and " + lang + ".dic into\n" + Dictionaries.userDir()
                    + (atlantis.hypatia.Platform.LINUX ? "\n(or install a system package such as myspell-" + lang + ")" : ""));
            return;
        } finally {
            setCursor(java.awt.Cursor.getDefaultCursor());
        }
        try {
            int changes = new SpellCheckDialog(this, tab.area(), dict, spellConfig).run();
            if (changes < 0) status("No spelling mistakes found (" + lang + ")", false);
        } catch (javax.swing.text.BadLocationException e) {
            status("Spell check failed: " + e.getMessage(), true);
        }
        recheckSpelling();
        tab.area().requestFocusInWindow();
    }

    /** Language choices for the current file; choosing one writes "% !TeX spellcheck = ..." at its top. */
    private void fillLanguageMenu(JPopupMenu menu) {
        EditorTab tab = current();
        if (tab == null || !isTexFile(tab)) {
            JMenuItem none = new JMenuItem("Spelling works on .tex files");
            none.setEnabled(false);
            menu.add(none);
            return;
        }
        String text = tab.area().getText();
        String magic = Dictionaries.magicLanguage(text);
        String def = settings.spellLanguage();
        javax.swing.JRadioButtonMenuItem d = new javax.swing.JRadioButtonMenuItem(
                "Default (" + (def.isEmpty() ? "none" : def) + ")", magic == null);
        d.addActionListener(e -> setFileSpellLanguage(tab, null));
        menu.add(d);
        menu.addSeparator();
        for (String l : Dictionaries.available()) {
            javax.swing.JRadioButtonMenuItem it = new javax.swing.JRadioButtonMenuItem(l, l.equals(magic));
            it.addActionListener(e -> setFileSpellLanguage(tab, l));
            menu.add(it);
        }
        if (Dictionaries.available().isEmpty()) {
            JMenuItem none = new JMenuItem("No Hunspell dictionaries found");
            none.setEnabled(false);
            menu.add(none);
        }
        menu.addSeparator();
        JMenuItem dl = new JMenuItem("Download Dictionary…");
        dl.addActionListener(e -> downloadDictionary());
        menu.add(dl);
    }

    /** Lets the user pick a LibreOffice dictionary and installs it in the user dictionary folder. */
    private void downloadDictionary() {
        List<atlantis.hypatia.spell.DictionaryDownloader.Entry> all = atlantis.hypatia.spell.DictionaryDownloader.entries();
        javax.swing.JComboBox<atlantis.hypatia.spell.DictionaryDownloader.Entry> box =
                new javax.swing.JComboBox<>(all.toArray(new atlantis.hypatia.spell.DictionaryDownloader.Entry[0]));
        EditorTab tab = current();
        String want = tab != null ? spellLanguage(tab, tab.area().getText()) : null;
        if (want == null || Dictionaries.exists(want)) want = settings.spellLanguage();
        if (want == null || want.isBlank()) want = java.util.Locale.getDefault().toString();
        var pre = atlantis.hypatia.spell.DictionaryDownloader.forLanguage(want);
        if (pre != null) box.setSelectedItem(pre);
        box.setMaximumRowCount(20);
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(box, BorderLayout.NORTH);
        panel.add(new JLabel("<html><div style='width:380px'>From LibreOffice's dictionary collection"
                + " (github.com/LibreOffice/dictionaries). Each dictionary has its own licence, saved as"
                + " README_<i>language</i>.txt next to it in<br>"
                + Dictionaries.userDir().toString().replace("&", "&amp;").replace("<", "&lt;") + "</div></html>"),
                BorderLayout.CENTER);
        int r = JOptionPane.showConfirmDialog(this, panel, "Download Dictionary",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION || box.getSelectedItem() == null) return;
        var entry = (atlantis.hypatia.spell.DictionaryDownloader.Entry) box.getSelectedItem();
        status("Downloading the " + entry.label() + " dictionary…", false);
        Thread t = new Thread(() -> {
            try {
                atlantis.hypatia.spell.DictionaryDownloader.download(entry);
                SwingUtilities.invokeLater(() -> {
                    status("Installed the " + entry.label() + " dictionary", false);
                    recheckSpelling();
                    updateStatus();
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                SwingUtilities.invokeLater(() -> error("Could not download the " + entry.label()
                        + " dictionary:\n" + msg + "\n\nYou can also put " + entry.code() + ".aff and "
                        + entry.code() + ".dic into\n" + Dictionaries.userDir()));
            }
        }, "hypatia-dictionary-download");
        t.setDaemon(true);
        t.start();
    }

    private void setFileSpellLanguage(EditorTab tab, String lang) {
        RSyntaxTextArea a = tab.area();
        javax.swing.text.Element root = a.getDocument().getDefaultRootElement();
        java.util.regex.Pattern magic = java.util.regex.Pattern.compile(
                "^\\s*%\\s*!\\s*TeX\\s+spellcheck\\s*=.*$", java.util.regex.Pattern.CASE_INSENSITIVE);
        String line = lang == null ? null : "% !TeX spellcheck = " + lang;
        a.beginAtomicEdit();
        try {
            for (int i = 0; i < Math.min(50, root.getElementCount()); i++) {
                javax.swing.text.Element el = root.getElement(i);
                String t = a.getText(el.getStartOffset(), el.getEndOffset() - 1 - el.getStartOffset());
                if (magic.matcher(t).matches()) {
                    if (line == null) {
                        a.getDocument().remove(el.getStartOffset(), Math.min(el.getEndOffset(), a.getDocument().getLength()) - el.getStartOffset());
                    } else {
                        a.replaceRange(line, el.getStartOffset(), el.getEndOffset() - 1);
                    }
                    return;
                }
            }
            if (line != null) a.getDocument().insertString(0, line + "\n", null);
        } catch (javax.swing.text.BadLocationException ignored) {
            // nothing changed
        } finally {
            a.endAtomicEdit();
            tab.spellChecker().recheck();
            updateStatus();
        }
    }

    private void editUserDictionary() {
        EditorTab tab = current();
        String lang = tab == null ? null : spellLanguage(tab, tab.area().getText());
        if (lang == null) lang = settings.spellLanguage();
        if (lang.isBlank()) {
            status("No spelling language set", true);
            return;
        }
        Path f = Dictionaries.userDir().resolve(lang + ".txt");
        try {
            Files.createDirectories(f.getParent());
            if (!Files.exists(f)) Files.writeString(f, "");
        } catch (IOException e) {
            error("Could not create " + f + ":\n" + e.getMessage());
            return;
        }
        open(f);
        status("One word per line. Save to apply.", false);
    }

    private void editSpellConfig() {
        if (!Files.exists(SpellConfig.file())) spellConfig = SpellConfig.load(); // writes the defaults
        open(SpellConfig.file());
        status("Edit the command list and save; it applies immediately.", false);
    }

    /** Text triggers fire in TeX files (and new, unsaved ones), not in e.g. macros.cfg. */
    private static boolean allowsTextTriggers(EditorTab tab) {
        return tab.file() == null || tab.file().toString().matches("(?i).*\\.(tex|ltx|sty|cls|dtx|bib)$");
    }

    private void reportConfigProblems(String okMessage) {
        List<String> problems = new ArrayList<>(macros.problems());
        problems.addAll(keymap.problems());
        if (problems.isEmpty()) {
            if (okMessage != null) status(okMessage, false);
        } else {
            status(String.join("; ", problems), true);
        }
    }

    private static JMenuItem disabled(String text) {
        JMenuItem i = new JMenuItem(text);
        i.setEnabled(false);
        return i;
    }

    private static MenuListener onMenuSelected(Runnable r) {
        return new MenuListener() {
            @Override public void menuSelected(MenuEvent e) { r.run(); }
            @Override public void menuDeselected(MenuEvent e) { }
            @Override public void menuCanceled(MenuEvent e) { }
        };
    }

    // =========================================================================
    // Tabs and files
    // =========================================================================

    /** The editor commands act on: the focused detached window's, else the selected tab. */
    public EditorTab current() {
        if (activeDetached != null && detached.containsKey(activeDetached)) return activeDetached;
        Component c = tabs.getSelectedComponent();
        return c instanceof EditorTab t ? t : null;
    }

    /** Brings an editor to the front: its detached window, or its tab. */
    private void showTab(EditorTab tab) {
        DetachedWindow w = detached.get(tab);
        if (w != null) {
            activeDetached = tab;
            w.toFront();
            w.requestFocus();
            tab.area().requestFocusInWindow();
        } else {
            tabs.setSelectedComponent(tab);
            if (activeDetached != null) {
                activeDetached = null;
                toFront();
            }
        }
    }

    private FindBar findBar() {
        DetachedWindow w = activeDetached == null ? null : detached.get(activeDetached);
        return w != null ? w.findBar : findBar;
    }

    private RSyntaxTextArea currentArea() {
        EditorTab t = current();
        return t == null ? null : t.area();
    }

    private List<EditorTab> allTabs() {
        List<EditorTab> list = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) list.add((EditorTab) tabs.getComponentAt(i));
        list.addAll(detached.keySet());
        return list;
    }

    private EditorTab findTab(Path file) {
        Path norm = file.toAbsolutePath().normalize();
        for (EditorTab t : allTabs()) {
            if (t.file() != null && sameFile(t.file(), norm)) return t;
        }
        return null;
    }

    private static boolean sameFile(Path a, Path b) {
        try {
            return a.equals(b) || (Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b));
        } catch (IOException e) {
            return false;
        }
    }

    private EditorTab addTab(EditorTab tab) {
        tab.applyLook(Theme.palette(), editorFont(), settings.softWrap());
        tab.applyIndentation(settings.autoIndent(), settings.indentDocument(), settings.autoCloseEnvironments());
        tab.setOnStateChange(() -> updateTab(tab));
        installKeys(tab.area());
        // Shift+click shows the clicked line in the PDF, like Shift+click in Okular does the reverse
        ((atlantis.hypatia.editor.HypatiaTextArea) tab.area()).setShiftClickAction(
                () -> forwardSearch(tab, settings.viewerCommand()));
        tab.installStyleMarks(new StyleMarks(tab.area(), this::styleLibrary,
                () -> settings.getInt("style.pluginTimeout", 5000), m -> status(m, true),
                () -> {
                    KeyStroke ks = keymap.get("style.next");
                    return ks == null ? "" : "      " + Keymap.format(ks) + ": next";
                },
                () -> clearStyle(tab)));
        tab.installSpellChecker(new SpellChecker(tab.area(), () -> spellConfig,
                text -> settings.spellEnabled() ? spellLanguage(tab, text) : null,
                Theme::spellSquiggle, m -> status(m, true), this::recheckSpelling));
        TextTriggers.install(tab.area(), () -> macros, () -> allowsTextTriggers(tab),
                () -> settings.getInt("macro.triggerWindow", 1000), m -> status(m, true));
        tab.area().addCaretListener(e -> {
            if (tab == current()) updatePosition();
            if (tab.isOutlineVisible() && tab.file() != null) tab.outline().showPosition(tab.file(), tab.caretLine());
        });
        tab.installOutline(new OutlinePanel((file, line) -> {
                    EditorTab t = open(file);
                    if (t != null) t.goToLine(line, 1);
                }, () -> tab.area().requestFocusInWindow()),
                settings.outlineWidth(), settings.outlineVisible() && isTexFile(tab), w -> {
                    settings.setInt("outline.width", w);
                    for (EditorTab t : allTabs()) if (t != tab) t.setOutlineWidth(w);
                });
        tab.area().getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { edited(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { edited(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { }
            private void edited() {
                if (tab == current()) outlineTimer.restart();
            }
        });
        tabs.addTab(tab.title(), tab);
        showTab(tab);
        updateTab(tab);
        updateView();
        return tab;
    }

    public void newFile() {
        addTab(new EditorTab()).area().requestFocusInWindow();
    }

    public void openDialog() {
        JFileChooser fc = new JFileChooser(startDir());
        fc.setDialogTitle("Open");
        fc.setMultiSelectionEnabled(true);
        fc.setFileFilter(new FileNameExtensionFilter("TeX files (*.tex, *.bib, *.sty, *.cls)",
                "tex", "bib", "sty", "cls", "ltx", "dtx", "bbx", "cbx"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            for (File f : fc.getSelectedFiles()) open(f.toPath());
        }
    }

    public EditorTab open(Path path) {
        EditorTab existing = findTab(path);
        if (existing != null) {
            showTab(existing);
            return existing;
        }
        if (!Files.exists(path)) {
            status("File not found: " + path, true);
            return null;
        }
        EditorTab tab = new EditorTab();
        try {
            tab.load(path);
        } catch (IOException e) {
            error("Could not open " + path + ":\n" + e.getMessage());
            return null;
        }
        // Replace a single untouched "Untitled" tab instead of piling up empty tabs.
        EditorTab only = tabs.getTabCount() == 1 ? current() : null;
        addTab(tab);
        if (only != null && only.file() == null && !only.isDirty()) {
            tabs.remove(only);
        }
        settings.addRecent(tab.file());
        settings.save();
        atlantis.hypatia.Positions.Position pos = positions.get(tab.file());
        if (pos != null) tab.restorePosition(pos.caret(), pos.topLine());
        refreshOutline(tab);
        return tab;
    }

    public void open(List<OpenRequest> requests) {
        EditorTab last = null;
        OpenRequest lastReq = null;
        for (OpenRequest r : requests) {
            EditorTab t = open(r.file());
            if (t != null) {
                last = t;
                lastReq = r;
            }
        }
        if (last != null) {
            showTab(last);
            if (lastReq.line() > 0) {
                EditorTab t = last;
                OpenRequest req = lastReq;
                SwingUtilities.invokeLater(() -> t.goToLine(req.line(), Math.max(1, req.column())));
            }
        }
    }

    /** Requests from another "hypatia" invocation (e.g. the PDF viewer's inverse search). */
    public void handleRemote(List<OpenRequest> requests) {
        open(requests);
        if (getExtendedState() == Frame.ICONIFIED) setExtendedState(Frame.NORMAL);
        setVisible(true);
        // Window managers often refuse plain toFront(); briefly raising works on KDE/X11.
        setAlwaysOnTop(true);
        toFront();
        requestFocus();
        setAlwaysOnTop(false);
        EditorTab t = current();
        if (t != null) t.area().requestFocusInWindow();
    }

    public boolean save(EditorTab tab) {
        if (tab == null) return false;
        if (tab.file() == null) return saveAs(tab);
        try {
            tab.save();
            rememberPosition(tab);
            positions.save();
            refreshOutline(tab);
            if (sameFile(tab.file(), Keymap.file())) reloadKeymap();
            if (sameFile(tab.file(), MacroFile.file())) reloadMacros();
            if (sameFile(tab.file(), SpellConfig.file())) {
                spellConfig = SpellConfig.load();
                recheckSpelling();
                status(spellConfig.problems().isEmpty() ? "Spell-check commands reloaded"
                        : String.join("; ", spellConfig.problems()), !spellConfig.problems().isEmpty());
            }
            if (tab.file().startsWith(StyleLibrary.dir())) reloadStyles(true);
            if (tab.file().startsWith(Dictionaries.userDir())) {
                Dictionaries.reloadUserWords();
                recheckSpelling();
            }
            return true;
        } catch (IOException e) {
            error("Could not save " + tab.file() + ":\n" + e.getMessage());
            return false;
        }
    }

    public boolean saveAs(EditorTab tab) {
        if (tab == null) return false;
        JFileChooser fc = new JFileChooser(tab.file() != null ? tab.file().getParent().toFile() : startDir());
        fc.setDialogTitle("Save As");
        if (tab.file() != null) fc.setSelectedFile(tab.file().toFile());
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return false;
        Path p = fc.getSelectedFile().toPath();
        if (!p.getFileName().toString().contains(".")) p = p.resolveSibling(p.getFileName() + ".tex");
        if (Files.exists(p) && (tab.file() == null || !sameFile(p, tab.file()))) {
            int r = JOptionPane.showConfirmDialog(this, p.getFileName() + " exists. Replace it?",
                    "Save As", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return false;
        }
        try {
            tab.saveAs(p);
            settings.addRecent(tab.file());
            settings.save();
            updateTab(tab);
            return true;
        } catch (IOException e) {
            error("Could not save " + p + ":\n" + e.getMessage());
            return false;
        }
    }

    private boolean saveAll() {
        for (EditorTab t : allTabs()) {
            if (t.isDirty() && t.file() != null && !save(t)) return false;
        }
        return true;
    }

    /** Asks about unsaved changes. Returns false if the user cancelled. */
    public boolean closeTab(EditorTab tab) {
        if (tab == null) return true;
        if (tab.isDirty()) {
            showTab(tab);
            int r = JOptionPane.showOptionDialog(this,
                    "Save changes to " + tab.title() + "?", "Unsaved changes",
                    JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null,
                    new Object[] {"Save", "Don't Save", "Cancel"}, "Save");
            if (r == 0) {
                if (!save(tab)) return false;
            } else if (r != 1) {
                return false;
            }
        }
        rememberPosition(tab);
        positions.save();
        DetachedWindow w = detached.remove(tab);
        if (w != null) {
            if (activeDetached == tab) activeDetached = null;
            w.dispose();
            toFront();
        } else {
            tabs.remove(tab);
        }
        updateView();
        return true;
    }

    // --- remembered positions ------------------------------------------------------------------

    private void rememberPosition(EditorTab t) {
        if (t.file() != null) positions.put(t.file(), t.area().getCaretPosition(), t.topLine());
    }

    private void rememberPositions() {
        for (EditorTab t : allTabs()) rememberPosition(t);
        positions.save();
    }

    // --- detached editors ------------------------------------------------------------------------

    /** A window holding one detached editor, with its own find bar. */
    private final class DetachedWindow extends JFrame {
        final EditorTab tab;
        final FindBar findBar;

        DetachedWindow(EditorTab tab) {
            this.tab = tab;
            this.findBar = new FindBar(tab::area);
            setIconImages(Icons.appImages());
            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
            JPanel content = new JPanel(new BorderLayout());
            content.add(tab, BorderLayout.CENTER);
            content.add(findBar, BorderLayout.SOUTH);
            setContentPane(content);
            addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) { attach(tab); }
                @Override public void windowDeactivated(WindowEvent e) { rememberPositions(); }
            });
            addWindowFocusListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowGainedFocus(WindowEvent e) {
                    if (activeDetached != tab) {
                        activeDetached = tab;
                        updateView();
                    }
                    refreshOutline(tab);
                    SwingUtilities.invokeLater(MainFrame.this::checkFilesOnDisk);
                }
            });
            updateTitle();
        }

        void updateTitle() {
            setTitle((tab.isDirty() ? "• " : "") + tab.title() + " — Hypatia");
        }
    }

    /** Detaches {@code tab} into a window of its own, or puts a detached one back. */
    private void toggleDetach(EditorTab tab) {
        if (tab == null) return;
        if (detached.containsKey(tab)) {
            attach(tab);
            return;
        }
        int caret = tab.area().getCaretPosition();
        tabs.remove(tab);
        DetachedWindow w = new DetachedWindow(tab);
        detached.put(tab, w);
        // Where it was last time, else to the right of the main window (or overlapping it).
        int dw = settings.getInt("detached.width", Math.max(600, getWidth() / 2));
        int dh = settings.getInt("detached.height", getHeight());
        int dx = settings.getInt("detached.x", Integer.MIN_VALUE);
        int dy = settings.getInt("detached.y", getY());
        if (dx == Integer.MIN_VALUE) {
            java.awt.Rectangle screen = getGraphicsConfiguration().getBounds();
            dx = Math.min(getX() + getWidth(), screen.x + screen.width - dw);
        }
        w.setBounds(dx, dy, dw, dh);
        w.setVisible(true);
        activeDetached = tab;
        tab.area().setCaretPosition(caret);
        tab.area().requestFocusInWindow();
        updateView();
        status(tab.title() + " detached; " + Keymap.format(keymap.get("view.detach")) + " or closing its window puts it back",
                false);
    }

    /** Puts a detached editor back into the main window. */
    private void attach(EditorTab tab) {
        DetachedWindow w = detached.remove(tab);
        if (w == null) return;
        settings.setInt("detached.x", w.getX());
        settings.setInt("detached.y", w.getY());
        settings.setInt("detached.width", w.getWidth());
        settings.setInt("detached.height", w.getHeight());
        int caret = tab.area().getCaretPosition();
        w.getContentPane().remove(tab);
        w.dispose();
        if (activeDetached == tab) activeDetached = null;
        tabs.addTab(tab.title(), tab);
        tabs.setSelectedComponent(tab);
        updateTab(tab);
        updateView();
        toFront();
        tab.area().setCaretPosition(caret);
        tab.area().requestFocusInWindow();
    }

    // --- structure sidebar -------------------------------------------------------------------------

    private void toggleOutline() {
        boolean show = !settings.outlineVisible();
        settings.setBool("outline.visible", show);
        settings.save();
        for (EditorTab t : allTabs()) t.setOutlineVisible(show && isTexFile(t));
        EditorTab t = current();
        if (show && t != null) {
            refreshOutline(t);
            if (t.isOutlineVisible()) t.outline().focusTree();
        } else if (t != null) {
            t.area().requestFocusInWindow();
        }
    }

    /**
     * For a file without a known main file: the main file of an open project that includes it
     * (via \\input/\\include), else the file itself.
     */
    private Path includingRoot(Path file) {
        for (EditorTab t : allTabs()) {
            if (t.file() == null || t.file().equals(file) || !isTexFile(t)) continue;
            RootResolver.Result rr = RootResolver.resolve(t.file(), t.area().getText(), settings);
            if (rr == null || rr.root().equals(file) || !Files.exists(rr.root())) continue;
            if (new atlantis.hypatia.tex.ProjectScanner(this::textOf).texFiles(rr.root()).contains(file)) {
                return rr.root();
            }
        }
        return file;
    }

    /** Rebuilds a tab's sidebar from its project (main file and included files). */
    private void refreshOutline(EditorTab tab) {
        if (tab == null || !tab.isOutlineVisible() || tab.file() == null) return;
        String text = tab.area().getText();
        RootResolver.Result r = RootResolver.resolve(tab.file(), text, settings);
        Path root = r != null && Files.exists(r.root()) ? r.root() : includingRoot(tab.file());
        tab.outline().update(atlantis.hypatia.tex.Outline.build(root, this::textOf));
        tab.outline().showPosition(tab.file(), tab.caretLine());
    }

    private File startDir() {
        EditorTab t = current();
        if (t != null && t.file() != null) return t.file().getParent().toFile();
        List<Path> recent = settings.recentFiles();
        return recent.isEmpty() ? new File(System.getProperty("user.home")) : recent.get(0).getParent().toFile();
    }

    private void checkFilesOnDisk() {
        if (checkingDisk) return;
        checkingDisk = true;
        try {
            for (EditorTab t : allTabs()) {
                if (!t.changedOnDisk()) continue;
                if (!t.isDirty()) {
                    t.reload();
                    status("Reloaded " + t.title() + " (changed on disk)", false);
                } else {
                    showTab(t);
                    int r = JOptionPane.showConfirmDialog(this,
                            t.title() + " was changed by another program.\nReload it and lose your changes here?",
                            "File changed on disk", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (r == JOptionPane.YES_OPTION) t.reload(); else t.acceptDiskVersion();
                }
            }
        } catch (IOException e) {
            status("Reload failed: " + e.getMessage(), true);
        } finally {
            checkingDisk = false;
        }
    }

    // =========================================================================
    // Building and viewing
    // =========================================================================

    private void buildOrStop() {
        if (build != null) {
            runner.cancel();
        } else {
            build();
        }
    }

    public void build() {
        run(Tool.BUILD);
    }

    /** What a run does: the full build, a single LaTeX run or a single BibTeX run. */
    private enum Tool {
        BUILD("Build", "Building"), LATEX("pdfLaTeX", "Running pdfLaTeX on"), BIBTEX("BibTeX", "Running BibTeX on");

        final String label;
        final String doing;

        Tool(String label, String doing) {
            this.label = label;
            this.doing = doing;
        }
    }

    private String command(Tool tool) {
        return switch (tool) {
            case BUILD -> settings.compileCommand();
            case LATEX -> settings.latexCommand();
            case BIBTEX -> settings.bibtexCommand();
        };
    }

    /** Runs the build, pdfLaTeX or BibTeX on the main file of the current tab. */
    private void run(Tool tool) {
        checkFilesOnDisk();
        EditorTab tab = current();
        if (tab == null || build != null) return;
        if (tab.file() == null && !saveAs(tab)) return;
        Path root = resolveRoot(tab, true);
        if (root == null) return;
        if (!saveAll()) return;
        if (!Files.exists(root)) {
            error("Main file not found:\n" + root);
            return;
        }

        lastRoot = root;
        List<String> cmd = CommandTemplate.build(command(tool), placeholders(root, tab));
        if (cmd.isEmpty()) {
            error("The " + tool.label + " command is empty. Set it in Settings > Building.");
            return;
        }
        logPanel.start(String.join(" ", cmd));
        status(tool.doing + " " + root.getFileName() + "…", false);
        setBuilding(true);
        long started = System.nanoTime();
        List<String> output = new ArrayList<>();

        build = new SwingWorker<>() {
            @Override
            protected Integer doInBackground() throws Exception {
                return runner.run(cmd, root.getParent(), line -> {
                    synchronized (output) { output.add(line); }
                    publish(line);
                });
            }

            @Override
            protected void process(List<String> chunks) {
                logPanel.append(chunks);
            }

            @Override
            protected void done() {
                build = null;
                setBuilding(false);
                double secs = (System.nanoTime() - started) / 1e9;
                int exit;
                try {
                    exit = get();
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    String msg = "Could not run '" + cmd.get(0) + "': " + cause.getMessage();
                    logPanel.append(List.of("", msg, "Is TeX installed? Help > Check TeX Installation shows what"
                            + " Hypatia finds; the command can be changed in Settings > Building."));
                    logPanel.finish(List.of(), msg, false);
                    status(msg, true);
                    showLog(true);
                    return;
                }
                if (tool == Tool.BIBTEX) {
                    List<String> lines;
                    synchronized (output) { lines = new ArrayList<>(output); }
                    bibtexDone(lines, exit, secs);
                    return;
                }
                List<Problem> problems;
                synchronized (output) { problems = LogParser.parse(output, root); }
                markProblems(problems);
                long errors = problems.stream().filter(p -> p.severity() == Severity.ERROR).count();
                long warnings = problems.size() - errors;
                boolean ok = exit == 0 && errors == 0;
                String what = tool == Tool.BUILD ? "Build" : tool.label;
                String summary;
                if (runner.wasCancelled()) {
                    summary = what + " stopped";
                } else if (ok) {
                    summary = String.format("%s %s in %.1f s%s", tool == Tool.BUILD ? "Built" : "pdfLaTeX: built",
                            RootResolver.pdfFor(root).getFileName(), secs,
                            warnings > 0 ? " · " + plural(warnings, "warning") : "");
                } else {
                    summary = String.format("%s failed: %s%s", what, errors > 0 ? plural(errors, "error") : "exit code " + exit,
                            warnings > 0 ? ", " + plural(warnings, "warning") : "");
                }
                if (!ok && missingPerl(output)) {
                    logPanel.append(List.of("", "latexmk is a Perl script, and MiKTeX found no Perl. Install Perl"
                            + " (e.g. Strawberry Perl, strawberryperl.com) and restart Hypatia, or use pdflatex as the"
                            + " build command in Settings > Building."));
                    summary += " (latexmk needs Perl, see the log)";
                }
                logPanel.finish(problems, summary, ok);
                if (problemsWindow != null && problemsWindow.isVisible()) problemsWindow.refresh();
                status(summary, !ok);
                if (!ok && !runner.wasCancelled()) showLog(true);
                if (errors > 0 && !runner.wasCancelled()) {
                    goToFirstError(problems, root);
                } else if (ok && settings.syncAfterCompile() && tab == current()) {
                    forwardSearch();
                }
            }
        };
        build.execute();
    }

    /** MiKTeX's message when latexmk is run without Perl installed. */
    private static boolean missingPerl(List<String> output) {
        synchronized (output) {
            return output.stream().anyMatch(l -> l.contains("script engine") && l.contains("perl"));
        }
    }

    /** Help > Check TeX Installation: where Hypatia finds the programs it uses. */
    private void showTexCheck() {
        StringBuilder sb = new StringBuilder();
        java.util.LinkedHashSet<String> programs = new java.util.LinkedHashSet<>();
        for (String template : List.of(settings.compileCommand(), settings.latexCommand(), settings.bibtexCommand(),
                settings.viewerCommand())) {
            List<String> words = CommandTemplate.build(template, Map.of());
            if (!words.isEmpty()) programs.add(words.get(0));
        }
        programs.addAll(List.of("pdflatex", "latexmk", "bibtex", "biber", "kpsewhich"));
        if (atlantis.hypatia.Platform.WINDOWS) programs.add("perl");
        sb.append("Programs (as Hypatia finds them):\n");
        for (String prog : programs) {
            Path found = atlantis.hypatia.Platform.findProgram(prog);
            sb.append(String.format("  %-12s %s%n", prog, found != null ? found : "NOT FOUND"));
        }
        String latexmk = String.valueOf(atlantis.hypatia.Platform.findProgram("latexmk"));
        if (atlantis.hypatia.Platform.WINDOWS && latexmk.toLowerCase().contains("miktex")
                && atlantis.hypatia.Platform.findProgram("perl") == null) {
            sb.append("\n  MiKTeX's latexmk needs Perl (e.g. Strawberry Perl). Without it, use pdflatex as build command.\n");
        }
        sb.append("\nTeX distribution: ");
        try {
            Process p = atlantis.hypatia.Platform.processBuilder(List.of("pdflatex", "--version"))
                    .redirectErrorStream(true).start();
            p.getOutputStream().close();
            String first = new String(p.getInputStream().readNBytes(400)).lines().findFirst().orElse("?");
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            sb.append(first);
        } catch (Exception e) {
            sb.append("pdflatex does not run (").append(e.getMessage()).append(')');
        }
        sb.append("\n\nFolders searched for programs:\n");
        for (Path d : atlantis.hypatia.Platform.searchPath()) {
            if (Files.isDirectory(d)) sb.append("  ").append(d).append('\n');
        }
        sb.append("\nSettings folder: ").append(Settings.configDir())
          .append("\nHypatia: ").append(atlantis.hypatia.Platform.hypatiaCommandLine())
          .append("\nJava ").append(System.getProperty("java.version")).append(", ")
          .append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version")).append('\n');
        JTextArea text = new JTextArea(sb.toString(), 24, 90);
        text.setEditable(false);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        text.setCaretPosition(0);
        JOptionPane.showMessageDialog(this, new JScrollPane(text), "TeX installation", JOptionPane.PLAIN_MESSAGE);
    }

    private static final java.util.regex.Pattern BIBTEX_COUNT = java.util.regex.Pattern.compile(
            "^\\(There (?:was|were) (\\d+) (error|warning)"); // "...2 error messages)", "...1 warning)"

    /** Summary after BibTeX (or Biber): counts its errors and warnings from the console output. */
    private void bibtexDone(List<String> lines, int exit, double secs) {
        long errors = 0;
        long warnings = 0;
        boolean counted = false;
        for (String l : lines) {
            java.util.regex.Matcher m = BIBTEX_COUNT.matcher(l);
            if (m.find()) { // BibTeX's own totals
                counted = true;
                if (m.group(2).equals("error")) errors = Long.parseLong(m.group(1));
                else warnings = Long.parseLong(m.group(1));
            }
        }
        if (!counted) { // Biber, or BibTeX stopped early
            for (String l : lines) {
                if (l.startsWith("Warning--") || l.contains("WARN - ")) warnings++;
                else if (l.contains("ERROR - ") || l.startsWith("I couldn't") || l.startsWith("I found no")) errors++;
            }
        }
        boolean ok = !runner.wasCancelled() && errors == 0 && exit <= 1;
        String summary;
        if (runner.wasCancelled()) {
            summary = "BibTeX stopped";
        } else if (ok) {
            summary = String.format("BibTeX finished in %.1f s%s", secs,
                    warnings > 0 ? " · " + plural(warnings, "warning") : "");
        } else {
            summary = String.format("BibTeX failed: %s%s", errors > 0 ? plural(errors, "error") : "exit code " + exit,
                    warnings > 0 ? ", " + plural(warnings, "warning") : "");
        }
        logPanel.finish(List.of(), summary, ok);
        if (problemsWindow != null && problemsWindow.isVisible()) problemsWindow.refresh();
        status(summary, !ok);
        if (!ok && !runner.wasCancelled()) showLog(true);
    }

    /**
     * After a run with errors: opens the file of the first error in the project, puts the
     * caret where TeX stopped and marks the line red until the next edit.
     */
    private void goToFirstError(List<Problem> problems, Path root) {
        Path dir = root.getParent();
        for (Problem p : problems) {
            if (p.severity() != Severity.ERROR || p.file() == null || p.line() <= 0) continue;
            if (!Files.exists(p.file())) continue;
            Path f = atlantis.hypatia.Platform.canonical(p.file());
            if (!f.startsWith(atlantis.hypatia.Platform.canonical(dir))) continue; // not a system .sty
            EditorTab t = open(p.file());
            if (t == null) return;
            t.goToLine(p.line(), errorColumn(t, p));
            t.markFatalLine(p.line());
            return;
        }
    }

    /**
     * 1-based column after the text TeX had read when it stopped: the "l.12 ..." context
     * ends there. The start of the line's text if that cannot be found.
     */
    private static int errorColumn(EditorTab t, Problem p) {
        String text;
        try {
            int i = p.line() - 1;
            if (i >= t.area().getLineCount()) return 1;
            int s = t.area().getLineStartOffset(i);
            text = t.area().getText(s, t.area().getLineEndOffset(i) - s).replace("\n", "");
        } catch (javax.swing.text.BadLocationException e) {
            return 1;
        }
        int indent = text.length() - text.stripLeading().length();
        String ctx = p.context();
        if (ctx == null) return indent + 1;
        if (ctx.startsWith("...")) ctx = ctx.substring(3);
        ctx = ctx.stripTrailing();
        if (ctx.isEmpty()) return indent + 1;
        int at = text.indexOf(ctx);
        if (at < 0 && ctx.length() > 20) { // TeX shows non-ASCII differently: try the end only
            String tail = ctx.substring(ctx.length() - 20);
            int k = text.indexOf(tail);
            if (k >= 0) return k + tail.length() + 1;
        }
        return at < 0 ? indent + 1 : at + ctx.length() + 1;
    }

    private static String plural(long n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }

    private void setBuilding(boolean running) {
        buildButton.setIcon(Icons.of(running ? Icons.Glyph.STOP : Icons.Glyph.BUILD));
        buildButton.setText(running ? "Stop" : "Build");
        buildButton.setToolTipText((running ? "Stop the build" : "Build") + shortcutHint("latex.build"));
        buildAction.putValue(Action.NAME, running ? "Stop Build" : "Build");
    }

    private void markProblems(List<Problem> problems) {
        for (EditorTab t : allTabs()) t.clearProblems();
        for (Problem p : problems) {
            if (p.severity() != Severity.ERROR || p.file() == null || p.line() <= 0) continue;
            EditorTab t = findTab(p.file());
            if (t != null) t.markProblemLine(p.line());
        }
    }

    /**
     * Edit > Refactor Selection: replaces the selected text (a label, say) in the body of all
     * files of the project. Changed files are opened in tabs and left unsaved.
     */
    private void refactor() {
        EditorTab tab = current();
        if (tab == null) return;
        String old = tab.area().getSelectedText();
        if (old == null || old.isBlank() || old.contains("\n")) {
            status("Select the text to rename first (within one line)", true);
            return;
        }
        Path root = tab.file() == null ? null : resolveRoot(tab, false);
        // the files, each with the offset where its body starts
        java.util.Map<Path, Integer> files = new java.util.LinkedHashMap<>();
        if (root != null) {
            for (Path f : new atlantis.hypatia.tex.ProjectScanner(this::textOf).texFiles(root)) {
                String text = textOf(f);
                if (text != null) files.put(f, f.equals(root) ? RefactorDialog.bodyStart(text) : 0);
            }
        }
        if (tab.file() != null && !files.containsKey(tab.file())) {
            files.put(tab.file(), RefactorDialog.bodyStart(tab.area().getText()));
        }
        java.util.Map<Path, String> unsavedNew = new java.util.HashMap<>();
        if (tab.file() == null) {
            Path pseudo = Path.of(tab.title());
            unsavedNew.put(pseudo, tab.area().getText());
            files.put(pseudo, RefactorDialog.bodyStart(tab.area().getText()));
        }
        java.util.function.Function<Path, String> reader =
                f -> unsavedNew.containsKey(f) ? unsavedNew.get(f) : textOf(f);
        RefactorDialog d = new RefactorDialog(this, old, files, reader, root == null ? null : root.getParent());
        String replacement = d.showDialog();
        if (replacement == null) return;

        java.util.regex.Pattern p = RefactorDialog.pattern(old);
        int total = 0;
        int changedFiles = 0;
        for (Path f : d.counts().keySet()) {
            EditorTab t = unsavedNew.containsKey(f) ? tab : open(f);
            if (t == null) continue;
            RSyntaxTextArea a = t.area();
            String text = a.getText();
            int from = files.getOrDefault(f, 0);
            if (!unsavedNew.containsKey(f)) from = f.equals(root) ? RefactorDialog.bodyStart(text) : 0;
            List<int[]> ranges = RefactorDialog.matches(p, text, from);
            if (ranges.isEmpty()) continue;
            a.beginAtomicEdit();
            try {
                for (int i = ranges.size() - 1; i >= 0; i--) {
                    int[] r = ranges.get(i);
                    a.getDocument().remove(r[0], r[1] - r[0]);
                    a.getDocument().insertString(r[0], replacement, null);
                }
            } catch (javax.swing.text.BadLocationException e) {
                status("Refactoring " + f.getFileName() + " failed: " + e.getMessage(), true);
            } finally {
                a.endAtomicEdit();
            }
            total += ranges.size();
            changedFiles++;
        }
        showTab(tab);
        tab.area().requestFocusInWindow();
        status("Replaced " + old + " by " + replacement + ": " + plural(total, "occurrence") + " in "
                + plural(changedFiles, "file") + " (not saved yet)", false);
    }

    /**
     * LaTeX > Switch BibTeX File: replaces the citation keys of the old .bib file by those of
     * the same works in a new one (matched by DOI, arXiv number, title and authors, checked in
     * a dialog), and points \\bibliography / \\addbibresource / "% !TeX bibliography" to it.
     */
    private void switchBibliography() {
        EditorTab tab = current();
        if (tab == null) return;
        Path root = projectRoot(tab);
        if (root == null) return;
        atlantis.hypatia.tex.ProjectScanner scanner = new atlantis.hypatia.tex.ProjectScanner(this::textOf);
        List<Path> texFiles = new ArrayList<>(scanner.texFiles(root));
        if (tab.file() != null && !texFiles.contains(tab.file())) texFiles.add(tab.file());
        List<Path> bibs = scanner.bibFiles(root, texFiles);
        if (bibs.isEmpty()) {
            status("No .bib file found for " + root.getFileName(), true);
            return;
        }
        Path old = bibs.get(0);
        if (bibs.size() > 1) {
            String[] names = bibs.stream().map(Path::toString).toArray(String[]::new);
            Object pick = JOptionPane.showInputDialog(this, "This project uses several BibTeX files. Which one should be replaced?",
                    "Switch BibTeX File", JOptionPane.QUESTION_MESSAGE, null, names, names[0]);
            if (pick == null) return;
            old = Path.of(pick.toString());
        }
        javax.swing.JFileChooser fc = new javax.swing.JFileChooser(old.getParent().toFile());
        fc.setDialogTitle("New BibTeX file instead of " + old.getFileName());
        fc.setFileFilter(new FileNameExtensionFilter("BibTeX files (*.bib)", "bib"));
        if (fc.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        Path neu = fc.getSelectedFile().toPath().toAbsolutePath().normalize();
        if (neu.equals(old)) {
            status("That is the current BibTeX file", true);
            return;
        }
        atlantis.hypatia.tex.Bibliography oldBib = atlantis.hypatia.tex.Bibliography.load(List.of(old), this::openText);
        atlantis.hypatia.tex.Bibliography newBib = atlantis.hypatia.tex.Bibliography.load(List.of(neu), this::openText);
        if (newBib.size() == 0) {
            status("No entries found in " + neu.getFileName(), true);
            return;
        }
        // the old entries that are cited, in the order of their first citation
        java.util.Map<String, atlantis.hypatia.tex.Bibliography.Entry> oldByKey = new java.util.HashMap<>();
        for (var e : oldBib.entries()) oldByKey.putIfAbsent(e.key(), e);
        java.util.LinkedHashMap<String, atlantis.hypatia.tex.Bibliography.Entry> cited = new java.util.LinkedHashMap<>();
        for (Path f : texFiles) {
            String text = textOf(f);
            if (text == null) continue;
            for (var k : atlantis.hypatia.tex.Citations.find(text)) {
                var e = oldByKey.get(k.key());
                if (e != null) cited.putIfAbsent(k.key(), e);
            }
        }
        if (cited.isEmpty()) {
            status("None of the keys cited in the project is in " + old.getFileName(), true);
            return;
        }
        List<atlantis.hypatia.tex.BibMatcher.Row> rows =
                atlantis.hypatia.tex.BibMatcher.match(new ArrayList<>(cited.values()), newBib.entries());
        SwitchBibDialog d = new SwitchBibDialog(this, old, neu, rows, newBib, editorFont());
        if (!d.showDialog()) return;

        java.util.Map<String, String> mapping = new java.util.HashMap<>();
        for (var r : rows) {
            if (r.proposal != null && !r.proposal.key().equals(r.old.key())) mapping.put(r.old.key(), r.proposal.key());
        }
        int keys = 0;
        int refs = 0;
        int files = 0;
        Path rootDir = root.getParent();
        for (Path f : texFiles) {
            String text = textOf(f);
            if (text == null) continue;
            List<int[]> ranges = new ArrayList<>();
            List<String> repl = new ArrayList<>();
            for (var k : atlantis.hypatia.tex.Citations.find(text)) {
                String n = mapping.get(k.key());
                if (n != null) {
                    ranges.add(new int[] {k.start(), k.end()});
                    repl.add(n);
                }
            }
            int keysHere = ranges.size();
            refs += bibReferences(text, f, rootDir, old, neu, ranges, repl);
            if (ranges.isEmpty()) continue;
            EditorTab t = open(f);
            if (t == null) continue;
            applyEdits(t, ranges, repl);
            keys += keysHere;
            files++;
        }
        showTab(tab);
        tab.area().requestFocusInWindow();
        long kept = rows.stream().filter(r -> r.proposal == null).count();
        status("Switched to " + neu.getFileName() + ": " + plural(keys, "citation key") + " replaced in "
                + plural(files, "file") + (refs == 0 ? "; no \\bibliography to update, add one for " + neu.getFileName()
                : "") + (kept > 0 ? "; " + kept + " without a match kept" : "") + " (not saved yet)", refs == 0 || kept > 0);
    }

    /**
     * Adds the edits that point \\bibliography{...}, \\addbibresource{...} and
     * "% !TeX bibliography = ..." from {@code old} to {@code neu}; returns their number.
     */
    private static int bibReferences(String text, Path file, Path rootDir, Path old, Path neu,
                                     List<int[]> ranges, List<String> repl) {
        int n = 0;
        String code = atlantis.hypatia.tex.Citations.blankComments(text); // comments as spaces: same offsets
        java.util.regex.Matcher m = atlantis.hypatia.tex.ProjectScanner.BIBLIOGRAPHY.matcher(code);
        while (m.find()) {
            int s = m.start(1);
            for (String part : m.group(1).split(",", -1)) {
                String name = part.trim();
                if (!name.isEmpty() && old.equals(atlantis.hypatia.tex.ProjectScanner.resolveBib(name, rootDir))) {
                    int a = s + part.indexOf(name);
                    ranges.add(new int[] {a, a + name.length()});
                    repl.add(bibName(neu, rootDir, false));
                    n++;
                }
                s += part.length() + 1;
            }
        }
        m = atlantis.hypatia.tex.ProjectScanner.BIBRESOURCE.matcher(code);
        while (m.find()) {
            String name = m.group(1).trim();
            if (old.equals(atlantis.hypatia.tex.ProjectScanner.resolveBib(name, rootDir))) {
                int a = m.start(1) + m.group(1).indexOf(name);
                ranges.add(new int[] {a, a + name.length()});
                repl.add(bibName(neu, rootDir, true));
                n++;
            }
        }
        m = atlantis.hypatia.tex.ProjectScanner.MAGIC_BIB.matcher(text);
        while (m.find()) {
            if (m.start() > 0 && text.substring(0, m.start()).chars().filter(c -> c == '\n').count() >= 50) break;
            List<Path> named = atlantis.hypatia.tex.ProjectScanner.magicBibFiles(file, m.group());
            if (named.contains(old)) {
                Path dir = file.getParent();
                String rel = neu.startsWith(dir) ? dir.relativize(neu).toString().replace('\\', '/') : neu.toString();
                ranges.add(new int[] {m.start(1), m.end(1)});
                repl.add(rel);
                n++;
            }
        }
        return n;
    }

    /** How to name {@code bib} in \\bibliography (no .bib) or \\addbibresource (with .bib). */
    private static String bibName(Path bib, Path rootDir, boolean withExtension) {
        String base = bib.getFileName().toString();
        String name;
        if (bib.equals(atlantis.hypatia.tex.ProjectScanner.kpsewhich(base))) {
            name = base; // found by TeX anyway, e.g. in ~/texmf/bibtex/bib
        } else {
            name = rootDir.relativize(bib).toString().replace('\\', '/');
        }
        if (!withExtension && name.endsWith(".bib")) name = name.substring(0, name.length() - 4);
        return name;
    }

    /** Replaces the ranges (in any order, not overlapping) in one undoable edit. */
    private static void applyEdits(EditorTab t, List<int[]> ranges, List<String> repl) {
        Integer[] order = new Integer[ranges.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (x, y) -> Integer.compare(ranges.get(y)[0], ranges.get(x)[0]));
        RSyntaxTextArea a = t.area();
        a.beginAtomicEdit();
        try {
            for (int i : order) {
                int[] r = ranges.get(i);
                a.getDocument().remove(r[0], r[1] - r[0]);
                a.getDocument().insertString(r[0], repl.get(i), null);
            }
        } catch (javax.swing.text.BadLocationException e) {
            // file changed meanwhile: leave the rest
        } finally {
            a.endAtomicEdit();
        }
    }

    /** View > Errors and Warnings: all errors and warnings from the .log of the current project. */
    private void showProblems() {
        if (problemsWindow == null) {
            problemsWindow = new ProblemsWindow(this, this::problemsRoot, this::openLogEntry);
        }
        problemsWindow.showFor();
    }

    private Path problemsRoot() {
        EditorTab tab = current();
        if (tab != null && tab.file() != null) {
            Path r = resolveRoot(tab, false);
            if (r != null) return r;
        }
        return lastRoot;
    }

    private static final java.util.regex.Pattern MULTIPLY_DEFINED_LABEL =
            java.util.regex.Pattern.compile("Label [`'](.+?)' multiply defined");

    private void openLogEntry(atlantis.hypatia.tex.LatexLog.Entry e) {
        Path root = problemsRoot();
        if (e.file() != null) {
            EditorTab t = open(e.file());
            if (t != null && e.line() > 0) t.goToLine(e.line(), 1);
            return;
        }
        // a label defined twice: go to its first \label
        java.util.regex.Matcher m = MULTIPLY_DEFINED_LABEL.matcher(e.message());
        if (m.find() && root != null) {
            String needle = "\\label{" + m.group(1) + "}";
            for (Path f : new atlantis.hypatia.tex.ProjectScanner(this::textOf).texFiles(root)) {
                String text = textOf(f);
                int at = text == null ? -1 : text.indexOf(needle);
                if (at >= 0) {
                    EditorTab t = open(f);
                    if (t != null) t.goToLine(1 + (int) text.substring(0, at).chars().filter(c -> c == '\n').count(), 1);
                    return;
                }
            }
        }
        if (root != null) open(root);
    }

    private void openProblem(Problem p) {
        Path f = p.file() != null ? p.file() : lastRoot;
        if (f == null) return;
        EditorTab t = open(f);
        if (t != null && p.line() > 0) t.goToLine(p.line(), 1);
    }

    /** Forward search: show the current line in the PDF viewer. */
    public void forwardSearch() {
        forwardSearch(settings.viewerCommand());
    }

    /** Runs a viewer command (forward search) for the current line. */
    private void forwardSearch(String template) {
        forwardSearch(current(), template);
    }

    /** Runs a viewer command (forward search) for the cursor line of {@code tab}. */
    private void forwardSearch(EditorTab tab, String template) {
        if (tab == null) return;
        if (tab.file() == null) {
            status("Save and build the file first", true);
            return;
        }
        Path root = resolveRoot(tab, true);
        if (root == null) return;
        Path pdf = RootResolver.pdfFor(root);
        if (!Files.exists(pdf)) {
            status("No PDF yet. Build first" + shortcutHint("latex.build") + ".", true);
            return;
        }
        List<String> cmd = CommandTemplate.build(template, placeholders(root, tab));
        if (template.equals(settings.viewerCommand()) && settings.separateViewerWindows()
                && atlantis.hypatia.OkularWindows.isOkular(cmd) && atlantis.hypatia.OkularWindows.available()) {
            atlantis.hypatia.OkularWindows.show(cmd, pdf, tab.file(), tab.caretLine(), root.getParent(),
                    msg -> SwingUtilities.invokeLater(() -> error("Could not start the PDF viewer:\n" + msg
                            + "\n\nYou can change the viewer command in Settings.")));
            return;
        }
        try {
            ProcessRunner.launchDetached(cmd, root.getParent());
        } catch (IOException e) {
            error("Could not start the PDF viewer:\n" + String.join(" ", cmd) + "\n\n" + e.getMessage()
                    + "\n\nYou can change the viewer command in Settings.");
        }
    }

    private Map<Character, String> placeholders(Path root, EditorTab tab) {
        Map<Character, String> m = new HashMap<>();
        m.put('f', root.getFileName().toString());
        m.put('n', RootResolver.baseName(root));
        m.put('d', root.getParent().toString());
        m.put('p', RootResolver.pdfFor(root).toString());
        m.put('t', tab.file() == null ? "" : tab.file().toString());
        m.put('l', Integer.toString(tab.caretLine()));
        m.put('c', Integer.toString(tab.caretColumn()));
        return m;
    }

    private Path resolveRoot(EditorTab tab, boolean interactive) {
        RootResolver.Result r = RootResolver.resolve(tab.file(), tab.area().getText(), settings);
        if (r != null) {
            if (r.source() == RootResolver.Source.MAGIC_COMMENT && !Files.exists(r.root())) {
                if (interactive) error("The main file named in “% !TEX root” does not exist:\n" + r.root());
                return null;
            }
            return r.root();
        }
        return interactive ? chooseMainFile(tab) : null;
    }

    /**
     * Remembers {@code root} as the main file of {@code tab}, opened as one of root's files,
     * unless the tab already has a main file.
     */
    void adoptRoot(EditorTab tab, Path root) {
        if (tab.file() == null || root == null || tab.file().equals(root) || resolveRoot(tab, false) != null) return;
        settings.setRootFor(tab.file(), root);
        settings.save();
        updateStatus();
    }

    private Path chooseMainFile(EditorTab tab) {
        JFileChooser fc = new JFileChooser(tab.file().getParent().toFile());
        fc.setDialogTitle("Main file for " + tab.title());
        fc.setFileFilter(new FileNameExtensionFilter("TeX files", "tex", "ltx"));
        fc.setApproveButtonText("Use as Main File");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return null;
        Path root = fc.getSelectedFile().toPath().toAbsolutePath().normalize();
        settings.setRootFor(tab.file(), root);
        settings.save();
        return root;
    }

    // =========================================================================
    // Settings, view state
    // =========================================================================

    Font editorFont() {
        return Theme.editorFont(settings.fontFamily(), settings.fontSize());
    }

    private void openSettings() {
        if (new SettingsDialog(this, settings).showDialog()) {
            applySettings(true);
        }
    }

    /** Re-applies theme (optionally the whole look and feel), font and wrapping. */
    private void applySettings(boolean laf) {
        if (laf && settings.isDark() != Theme.isDark()) {
            Theme.install(settings.isDark());
            FlatLaf.updateUI();
        }
        darkItem.setSelected(settings.isDark());
        wrapItem.setSelected(settings.softWrap());
        wrapButton.setSelected(settings.softWrap());
        spellItem.setSelected(settings.spellEnabled());
        recheckSpelling();
        for (EditorTab t : allTabs()) {
            t.applyLook(Theme.palette(), editorFont(), settings.softWrap());
            t.styleMarks().themeChanged();
            t.outline().themeChanged();
            t.applyIndentation(settings.autoIndent(), settings.indentDocument(), settings.autoCloseEnvironments());
        }
        updateView();
    }

    private void setWrap(boolean wrap) {
        settings.setBool("editor.softWrap", wrap);
        settings.save();
        applySettings(false);
        status(wrap ? "Long lines wrap" : "Long lines don't wrap", false);
    }

    private void zoom(int delta) {
        int size = delta == 0 ? 14 : Math.max(8, Math.min(40, settings.fontSize() + delta));
        settings.setInt("editor.font.size", size);
        settings.save();
        applySettings(false);
        status("Font size " + size, false);
    }

    private void showLog(boolean show) {
        if (show == logPanel.isVisible()) return;
        if (!show) logDivider = mainSplit.getDividerLocation();
        logPanel.setVisible(show);
        if (show) {
            int h = mainSplit.getHeight();
            mainSplit.setDividerLocation(logDivider > 0 && logDivider < h - 60 ? logDivider : Math.max(100, h - 240));
        }
        mainSplit.revalidate();
    }

    private void onTabChanged() {
        SwingUtilities.invokeLater(this::checkFilesOnDisk);
        findBar.close();
        updateView();
        refreshOutline(current());
    }

    private void updateView() {
        boolean empty = tabs.getTabCount() == 0;
        if (empty) welcome.refresh(settings.recentFiles());
        cards.show(center, empty ? "welcome" : "editor");
        updateTitle();
        updateStatus();
    }

    private void updateTab(EditorTab tab) {
        DetachedWindow w = detached.get(tab);
        if (w != null) {
            w.updateTitle();
            if (tab == current()) SwingUtilities.invokeLater(this::updateStatus);
            return;
        }
        int i = tabs.indexOfComponent(tab);
        if (i < 0) return;
        tabs.setTitleAt(i, tab.isDirty() ? tab.title() + " •" : tab.title());
        tabs.setToolTipTextAt(i, tab.file() == null ? "Not saved yet" : tab.file().toString());
        if (tab == current()) {
            updateTitle();
            // Later: this can run in the middle of an edit (e.g. a replace), when
            // the text is momentarily incomplete.
            SwingUtilities.invokeLater(this::updateStatus);
        }
    }

    private void updateTitle() {
        EditorTab t = current();
        setTitle(t == null ? "Hypatia" : (t.isDirty() ? "• " : "") + t.title() + " — Hypatia");
    }

    private void updateStatus() {
        EditorTab t = current();
        if (t == null) {
            rootLabel.setText("");
            positionLabel.setText("");
            encodingLabel.setText("");
            spellLabel.setText("");
            return;
        }
        updatePosition();
        encodingLabel.setText(t.charset().name());
        String lang = settings.spellEnabled() ? spellLanguage(t, t.area().getText()) : null;
        spellLabel.setText(lang == null ? "" : lang);
        spellLabel.setToolTipText(lang == null ? null
                : "Spelling language (click to change)" + (Dictionaries.exists(lang) ? "" : ": no dictionary installed"));
        spellLabel.setForeground(lang == null || Dictionaries.exists(lang)
                ? UIManager.getColor("Label.foreground") : Theme.error());
        if (t.file() != null && !t.file().toString().matches("(?i).*\\.(tex|ltx)$")) {
            rootLabel.setText("");
            return;
        }
        RootResolver.Result r = t.file() == null ? null
                : RootResolver.resolve(t.file(), t.area().getText(), settings);
        if (r == null) {
            rootLabel.setText("Main file: not set");
            rootLabel.setToolTipText("Chosen when you first build (LaTeX > Set Main File…)");
        } else {
            rootLabel.setText(r.source() == RootResolver.Source.SELF ? "Main file" : "Main: " + r.root().getFileName());
            rootLabel.setToolTipText(r.root() + switch (r.source()) {
                case MAGIC_COMMENT -> "  (from % !TEX root)";
                case SELF -> "  (contains \\documentclass)";
                case REMEMBERED -> "  (chosen by you)";
            });
        }
    }

    private void updatePosition() {
        EditorTab t = current();
        if (t != null) positionLabel.setText("Ln " + t.caretLine() + ", Col " + t.caretColumn());
    }

    void status(String text, boolean problem) {
        statusLabel.setText(text);
        statusLabel.setForeground(problem ? Theme.error() : UIManager.getColor("Label.foreground"));
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(this, message, "Hypatia", JOptionPane.ERROR_MESSAGE);
    }

    private void goToLineDialog() {
        EditorTab t = current();
        if (t == null) return;
        String s = JOptionPane.showInputDialog(this, "Line (1–" + t.area().getLineCount() + "):",
                "Go to Line", JOptionPane.PLAIN_MESSAGE);
        if (s == null) return;
        try {
            t.goToLine(Integer.parseInt(s.trim()), 1);
        } catch (NumberFormatException e) {
            status("Not a line number: " + s, true);
        }
    }

    /** Where manual.pdf may be: next to the jar, the project's doc/ folder, the data and config folders. */
    private static List<Path> manualLocations() {
        List<Path> out = new ArrayList<>();
        try {
            Path code = Path.of(MainFrame.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(code) ? code : code.getParent();
            out.add(dir.resolve("manual.pdf"));
            if (dir.getParent() != null) {
                out.add(dir.getParent().resolve("doc/manual.pdf"));               // target/hypatia.jar
                if (dir.getParent().getParent() != null) {
                    out.add(dir.getParent().getParent().resolve("doc/manual.pdf")); // target/classes
                }
            }
        } catch (Exception ignored) {
            // unusual class loader: skip
        }
        if (atlantis.hypatia.Platform.LINUX) {
            String data = System.getenv("XDG_DATA_HOME");
            Path dataDir = data != null && !data.isBlank() ? Path.of(data)
                    : Path.of(System.getProperty("user.home"), ".local", "share");
            out.add(dataDir.resolve("hypatia/manual.pdf"));
        }
        out.add(Settings.configDir().resolve("manual.pdf"));
        return out;
    }

    /** The manual inside the JAR (if it was built with doc/manual.pdf present), copied to a temporary file. */
    private static Path bundledManual() {
        try (java.io.InputStream in = MainFrame.class.getResourceAsStream("/atlantis/hypatia/manual.pdf")) {
            if (in == null) return null;
            Path out = Path.of(System.getProperty("java.io.tmpdir"), "hypatia-manual-" + VERSION + ".pdf");
            Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return out;
        } catch (IOException e) {
            return null;
        }
    }

    /** Opens manual.pdf in the desktop's default PDF viewer. */
    private void showManual() {
        Path manual = manualLocations().stream().filter(Files::isRegularFile).findFirst().orElse(null);
        if (manual == null) manual = bundledManual();
        if (manual == null) {
            StringBuilder sb = new StringBuilder("manual.pdf was not found. Hypatia looks in:\n");
            for (Path p : manualLocations()) sb.append("  ").append(p.normalize()).append('\n');
            sb.append("\nCompile doc/manual.tex (latexmk -pdf manual.tex) and run packaging/install.sh,\n"
                    + "or copy manual.pdf to one of these places.");
            error(sb.toString());
            return;
        }
        try {
            atlantis.hypatia.Platform.open(manual);
            status("Opening " + manual, false);
        } catch (IOException e) {
            error("Could not open " + manual + ":\n" + e.getMessage());
        }
    }

    private void showCommandLineHelp() {
        String viewer = settings.viewerCommand();
        atlantis.hypatia.Viewers.Viewer v = atlantis.hypatia.Viewers.byCommand(viewer);
        String start = atlantis.hypatia.Platform.hypatiaCommandLine();
        String usage = OpenRequest.USAGE;
        if (!start.equals("hypatia")) usage = usage.replace("Usage: hypatia", "Usage: " + start);
        JTextArea text = new JTextArea(usage + "\n"
                + "Forward search (LaTeX > Show in PDF Viewer) runs the viewer command"
                + (v != null ? " for " + v.name() : "") + ":\n  " + viewer + "\n"
                + (settings.separateViewerWindows() && atlantis.hypatia.OkularWindows.isOkular(CommandTemplate.build(viewer, Map.of()))
                        ? (atlantis.hypatia.OkularWindows.available()
                                ? "Each document gets its own Okular window: Hypatia finds it over D-Bus, or starts Okular without --unique.\n"
                                : "Separate Okular windows are on, but none of dbus-send, gdbus, qdbus was found, so one window is used.\n")
                        : "")
                + "The build must produce a .synctex.gz file (-synctex=1).\n\n"
                + "Inverse search: " + (v != null && !v.inverse().isEmpty()
                        ? v.inverse() + "\n  " + v.howTo()
                        : start + " --line LINE FILE, with your viewer's placeholders for LINE and FILE")
                + "\n");
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setColumns(80);
        text.setRows(24);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        text.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JOptionPane.showMessageDialog(this, new JScrollPane(text), "Command line & PDF viewer",
                JOptionPane.PLAIN_MESSAGE);
    }

    private void showAbout() {
        Icon icon = Icons.appIcon(128);
        JOptionPane.showMessageDialog(this,
                "<html><h2 style='margin:0'>Hypatia " + VERSION + "</h2>"
                        + "<p>A LaTeX editor.</p><br>"
                        + "<p style='color:gray'>Built with RSyntaxTextArea and FlatLaf.<br>Java "
                        + System.getProperty("java.version") + "</p></html>",
                "About Hypatia", JOptionPane.PLAIN_MESSAGE, icon);
    }

    // =========================================================================
    // Session and shutdown
    // =========================================================================

    public void restoreSession() {
        List<Settings.SessionEntry> entries = settings.session();
        for (Settings.SessionEntry e : entries) {
            EditorTab t = open(e.file());
            if (t != null && positions.get(t.file()) == null) {
                t.area().setCaretPosition(Math.min(e.caret(), t.area().getDocument().getLength()));
            }
        }
        int active = settings.getInt("session.active", 0);
        if (active >= 0 && active < tabs.getTabCount()) tabs.setSelectedIndex(active);
    }

    private void restoreBounds() {
        int w = settings.getInt("window.width", 1200);
        int h = settings.getInt("window.height", 820);
        setSize(w, h);
        int x = settings.getInt("window.x", Integer.MIN_VALUE);
        int y = settings.getInt("window.y", Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE) setLocationRelativeTo(null); else setLocation(x, y);
        if (settings.getBool("window.maximized", false)) setExtendedState(Frame.MAXIMIZED_BOTH);
    }

    private void saveWindowState() {
        boolean max = (getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
        settings.setBool("window.maximized", max);
        if (!max) {
            settings.setInt("window.width", getWidth());
            settings.setInt("window.height", getHeight());
            settings.setInt("window.x", getX());
            settings.setInt("window.y", getY());
        }
        List<Settings.SessionEntry> entries = new ArrayList<>();
        int active = 0;
        for (EditorTab t : allTabs()) {
            if (t.file() == null) continue;
            if (t == current()) active = entries.size();
            entries.add(new Settings.SessionEntry(t.file(), t.area().getCaretPosition()));
        }
        settings.setSession(entries, active);
        settings.save();
    }

    public void quit() {
        if (build != null) runner.cancel();
        saveWindowState(); // before closing tabs, so the session remembers them
        rememberPositions();
        for (DetachedWindow w : new ArrayList<>(detached.values())) {
            settings.setInt("detached.x", w.getX());
            settings.setInt("detached.y", w.getY());
            settings.setInt("detached.width", w.getWidth());
            settings.setInt("detached.height", w.getHeight());
        }
        for (EditorTab t : allTabs()) {
            if (t.isDirty() && !confirmClose(t)) return;
        }
        if (instanceServer != null) instanceServer.stop();
        dispose();
        System.exit(0);
    }

    private boolean confirmClose(EditorTab tab) {
        showTab(tab);
        int r = JOptionPane.showOptionDialog(this,
                "Save changes to " + tab.title() + " before quitting?", "Unsaved changes",
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null,
                new Object[] {"Save", "Don't Save", "Cancel"}, "Save");
        if (r == 0) return save(tab);
        return r == 1;
    }

    /** Opens files dropped onto the window. */
    private final class FileDropHandler extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport s) {
            return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean importData(TransferSupport s) {
            try {
                List<File> files = (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                files.forEach(f -> open(f.toPath()));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
