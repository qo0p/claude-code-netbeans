package dev.claudecode.netbeans;

import dev.claudecode.netbeans.actions.StartClaudeAction;
import dev.claudecode.netbeans.chat.ChatHost;
import dev.claudecode.netbeans.chat.ChatPanel;
import dev.claudecode.netbeans.core.Ide;
import java.awt.BorderLayout;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.text.JTextComponent;
import org.netbeans.api.editor.EditorRegistry;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectUtils;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.filesystems.FileUtil;
import org.openide.awt.HtmlBrowser;
import org.openide.util.NbPreferences;
import org.openide.windows.Mode;
import org.openide.windows.TopComponent;
import org.openide.windows.WindowManager;

/** The "Claude Code" chat window, docked on the right like the VS Code panel. */
public final class ChatTopComponent extends TopComponent implements ChatHost {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(ChatTopComponent.class.getName());
    private static final AtomicInteger DIFFS = new AtomicInteger();
    private static ChatTopComponent instance;

    private final ChatPanel panel;

    private ChatTopComponent() {
        setName("Claude Code");
        setDisplayName("Claude Code");
        setToolTipText("Chat with Claude Code");
        setLayout(new BorderLayout());
        panel = new ChatPanel(this);
        add(panel, BorderLayout.CENTER);
    }

    /** Opens (or shows) the chat window and focuses the message box. Call on the Swing thread. */
    public static void showChat() {
        ChatTopComponent tc = instance();
        WindowManager wm = WindowManager.getDefault();
        if (!tc.isOpened()) {
            Mode mode = wm.findMode("properties");
            if (mode == null) {
                mode = wm.findMode("explorer");
            }
            if (mode != null) {
                mode.dockInto(tc);
            }
            tc.open();
        }
        // A minimized (sliding) window would hide again as soon as it loses focus,
        // so always show the chat docked.
        if (wm.isTopComponentMinimized(tc)) {
            wm.setTopComponentMinimized(tc, false);
        }
        tc.requestActive();
        tc.panel.focusInput();
    }

    /** Puts text (e.g. an @-mention) into the message box if the chat window is open. */
    public static boolean insertIntoChat(String text) {
        ChatTopComponent tc = instance;
        if (tc == null || !tc.isOpened()) {
            return false;
        }
        tc.requestActive();
        tc.panel.insertText(text);
        return true;
    }

    static synchronized ChatTopComponent instance() {
        if (instance == null) {
            instance = new ChatTopComponent();
        }
        return instance;
    }

    static void shutdownChat() {
        ChatTopComponent tc = instance;
        if (tc != null) {
            Edt.later(tc.panel::shutdown);
        }
    }

    @Override
    protected void componentActivated() {
        panel.focusInput();
    }

    @Override
    public int getPersistenceType() {
        return PERSISTENCE_NEVER;
    }

    @Override
    protected String preferredID() {
        return "ClaudeCodeChat";
    }

    // ------------------------------------------------------------------ ChatHost

    @Override
    public File workingDirectory() {
        return StartClaudeAction.workingDirectory();
    }

    @Override
    public boolean projectsLoading() {
        return !OpenProjects.getDefault().openProjects().isDone();
    }

    @Override
    public List<ProjectInfo> openProjects() {
        Project main = OpenProjects.getDefault().getMainProject();
        List<ProjectInfo> list = new ArrayList<>();
        for (Project p : OpenProjects.getDefault().getOpenProjects()) {
            File dir = FileUtil.toFile(p.getProjectDirectory());
            if (dir == null) {
                continue;
            }
            String name;
            try {
                name = ProjectUtils.getInformation(p).getDisplayName();
            } catch (RuntimeException e) {
                name = dir.getName();
            }
            list.add(new ProjectInfo(name, dir.getAbsolutePath(), p.equals(main), buildKind(dir)));
        }
        list.sort(Comparator.comparing((ProjectInfo i) -> !i.main())
                .thenComparing(i -> i.name().toLowerCase(java.util.Locale.ROOT)));
        return list;
    }

    private static String buildKind(File dir) {
        if (new File(dir, "pom.xml").isFile()) {
            return "Maven";
        }
        if (new File(dir, "build.gradle").isFile() || new File(dir, "build.gradle.kts").isFile()
                || new File(dir, "settings.gradle").isFile() || new File(dir, "settings.gradle.kts").isFile()) {
            return "Gradle";
        }
        if (new File(dir, "nbproject/project.xml").isFile()) {
            return "Ant";
        }
        if (new File(dir, "package.json").isFile()) {
            return "Node.js";
        }
        if (new File(dir, "composer.json").isFile()) {
            return "PHP";
        }
        return null;
    }

    @Override
    public EditorContext editorContext() {
        JTextComponent c = EditorRegistry.lastFocusedComponent();
        Ide.Selection s = EditorSelection.from(c);
        if (s == null) {
            return null;
        }
        int start = s.startLine() + 1;
        int end = s.endLine() + 1;
        if (end > start && s.endCharacter() == 0) {
            end--; // selection ends at the start of a line that isn't really included
        }
        return new EditorContext(s.filePath(), s.text(), start, end);
    }

    @Override
    public String currentContents(String filePath) throws Exception {
        return NetBeansIde.currentContents(filePath);
    }

    @Override
    public DiffReview openDiff(String filePath, String oldContents, String newContents, String title) {
        CompletableFuture<Ide.DiffResult> result = new CompletableFuture<>();
        DiffTopComponent tc = new DiffTopComponent(title + " #" + DIFFS.incrementAndGet(), filePath,
                oldContents, newContents, result);
        tc.open();
        tc.requestActive();
        CompletableFuture<Boolean> accepted = result.thenApply(Ide.DiffResult::accepted);
        return new DiffReview() {
            @Override
            public CompletableFuture<Boolean> result() {
                return accepted;
            }

            @Override
            public void accept() {
                Edt.later(tc::accept);
            }

            @Override
            public void reject() {
                Edt.later(tc::reject);
            }
        };
    }

    @Override
    public void openFile(String filePath, int line) {
        try {
            new NetBeansIde().openFile(filePath, null, null, false, true);
        } catch (Exception e) {
            LOG.log(Level.INFO, "Could not open " + filePath, e);
        }
    }

    @Override
    public void openUrl(String url) {
        try {
            HtmlBrowser.URLDisplayer.getDefault().showURL(URI.create(url).toURL());
        } catch (Exception e) {
            LOG.log(Level.INFO, "Could not open " + url, e);
        }
    }

    @Override
    public String loadSetting(String key) {
        return NbPreferences.forModule(ChatTopComponent.class).get(key, null);
    }

    @Override
    public void saveSetting(String key, String value) {
        NbPreferences.forModule(ChatTopComponent.class).put(key, value);
    }
}
