package dev.claudecode.netbeans;

import dev.claudecode.netbeans.core.Ide;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import org.netbeans.api.diff.DiffController;
import org.netbeans.api.diff.StreamSource;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.windows.Mode;
import org.openide.windows.TopComponent;
import org.openide.windows.WindowManager;

/**
 * Editor tab showing a change Claude Code proposes, with Accept / Reject.
 * Closing the tab without deciding counts as Reject.
 */
final class DiffTopComponent extends TopComponent {

    private static final long serialVersionUID = 1L;
    private static final Map<String, DiffTopComponent> OPEN = new ConcurrentHashMap<>();

    private final String tabName;
    private final String newContents;
    private final transient CompletableFuture<Ide.DiffResult> result;
    private boolean decided;

    DiffTopComponent(String tabName, String filePath, String oldContents, String newContents,
                     CompletableFuture<Ide.DiffResult> result) {
        this.tabName = tabName;
        this.newContents = newContents;
        this.result = result;
        String fileName = new File(filePath).getName();
        setName(tabName);
        setDisplayName("Claude: " + fileName);
        setToolTipText("Change proposed by Claude Code to " + filePath);
        setLayout(new BorderLayout());

        String mime = mimeType(filePath);
        JComponent view;
        try {
            StreamSource base = StreamSource.createSource(filePath + "#current", fileName + " (current)",
                    mime, new StringReader(oldContents));
            StreamSource proposed = StreamSource.createSource(filePath + "#proposed", fileName + " (proposed by Claude)",
                    mime, new StringReader(newContents));
            view = DiffController.createEnhanced(base, proposed).getJComponent();
        } catch (IOException e) {
            JTextArea area = new JTextArea(newContents);
            area.setEditable(false);
            view = new JScrollPane(area);
        }
        add(view, BorderLayout.CENTER);

        JButton accept = new JButton(new AbstractAction("Accept") {
            @Override
            public void actionPerformed(ActionEvent e) {
                DiffTopComponent.this.accept();
            }
        });
        JButton reject = new JButton(new AbstractAction("Reject") {
            @Override
            public void actionPerformed(ActionEvent e) {
                DiffTopComponent.this.reject();
            }
        });
        accept.setToolTipText("Let Claude Code write this change (Ctrl+Enter)");
        reject.setToolTipText("Tell Claude Code not to make this change (Escape)");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(reject);
        buttons.add(accept);
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 4));
        bar.add(new JLabel("Claude Code wants to change " + fileName + ". Review the diff, then accept or reject."),
                BorderLayout.CENTER);
        bar.add(buttons, BorderLayout.EAST);
        add(bar, BorderLayout.SOUTH);

        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "claude.accept");
        getActionMap().put("claude.accept", accept.getAction());
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "claude.reject");
        getActionMap().put("claude.reject", reject.getAction());

        DiffTopComponent previous = OPEN.put(tabName, this);
        if (previous != null && previous != this) {
            previous.reject();
        }
    }

    static DiffTopComponent byName(String tabName) {
        return OPEN.get(tabName);
    }

    static List<DiffTopComponent> all() {
        return new ArrayList<>(OPEN.values());
    }

    void accept() {
        decide(new Ide.DiffResult(true, newContents));
        close();
    }

    void reject() {
        decide(new Ide.DiffResult(false, null));
        close();
    }

    private void decide(Ide.DiffResult r) {
        if (!decided) {
            decided = true;
            OPEN.remove(tabName, this);
            result.complete(r);
        }
    }

    @Override
    public void open() {
        Mode editor = WindowManager.getDefault().findMode("editor");
        if (editor != null) {
            editor.dockInto(this);
        }
        super.open();
    }

    @Override
    protected void componentClosed() {
        decide(new Ide.DiffResult(false, null));
        super.componentClosed();
    }

    @Override
    public int getPersistenceType() {
        return PERSISTENCE_NEVER;
    }

    @Override
    protected String preferredID() {
        return "ClaudeCodeDiff";
    }

    private static String mimeType(String path) {
        FileObject fo = FileUtil.toFileObject(FileUtil.normalizeFile(new File(path)));
        if (fo != null) {
            String m = fo.getMIMEType();
            if (m != null && !m.equals("content/unknown")) {
                return m;
            }
        }
        return "text/plain";
    }
}
