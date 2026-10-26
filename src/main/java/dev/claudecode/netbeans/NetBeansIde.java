package dev.claudecode.netbeans;

import dev.claudecode.netbeans.core.Ide;
import dev.claudecode.netbeans.core.ToolException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.swing.JEditorPane;
import javax.swing.text.JTextComponent;
import javax.swing.text.StyledDocument;
import org.netbeans.api.editor.EditorRegistry;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.cookies.EditorCookie;
import org.openide.cookies.SaveCookie;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;

/** Implements the IDE operations Claude Code asks for, using NetBeans APIs. */
final class NetBeansIde implements Ide {

    @Override
    public List<String> workspaceFolders() {
        Set<String> roots = new LinkedHashSet<>();
        for (Project p : OpenProjects.getDefault().getOpenProjects()) {
            File dir = FileUtil.toFile(p.getProjectDirectory());
            if (dir != null) {
                roots.add(dir.getAbsolutePath());
            }
        }
        return new ArrayList<>(roots);
    }

    @Override
    public Selection currentSelection() throws Exception {
        return Edt.call(() -> EditorSelection.from(EditorRegistry.lastFocusedComponent()));
    }

    @Override
    public List<EditorTab> openEditors() throws Exception {
        return Edt.call(() -> {
            JTextComponent focused = EditorRegistry.lastFocusedComponent();
            Set<String> seen = new LinkedHashSet<>();
            List<EditorTab> tabs = new ArrayList<>();
            for (JTextComponent c : EditorRegistry.componentList()) {
                File f = EditorSelection.fileOf(c.getDocument());
                if (f == null || !seen.add(f.getAbsolutePath())) {
                    continue;
                }
                DataObject d = dataObject(f.getAbsolutePath());
                tabs.add(new EditorTab(f.getAbsolutePath(), c == focused, d != null && d.isModified()));
            }
            return tabs;
        });
    }

    @Override
    public void openFile(String filePath, String startText, String endText,
                         boolean selectToEndOfLine, boolean makeFrontmost) throws Exception {
        DataObject d = dataObject(filePath);
        if (d == null) {
            throw new ToolException("File not found: " + filePath);
        }
        EditorCookie ec = d.getLookup().lookup(EditorCookie.class);
        if (ec == null) {
            throw new ToolException("NetBeans cannot open this file in an editor: " + filePath);
        }
        int[] range = null;
        if (startText != null && !startText.isEmpty()) {
            StyledDocument doc = ec.openDocument();
            String text = doc.getText(0, doc.getLength());
            int start = text.indexOf(startText);
            if (start >= 0) {
                int end = start + startText.length();
                if (endText != null && !endText.isEmpty()) {
                    int e = text.indexOf(endText, start);
                    if (e >= 0) {
                        end = e + endText.length();
                    }
                }
                if (selectToEndOfLine) {
                    int nl = text.indexOf('\n', end);
                    end = nl < 0 ? text.length() : nl;
                }
                range = new int[]{start, end};
            }
        }
        int[] sel = range;
        Edt.later(() -> {
            ec.open();
            Edt.later(() -> {
                JEditorPane[] panes = ec.getOpenedPanes();
                if (panes == null || panes.length == 0) {
                    return;
                }
                JEditorPane pane = panes[0];
                if (sel != null) {
                    pane.setCaretPosition(sel[0]);
                    pane.moveCaretPosition(sel[1]);
                }
                if (makeFrontmost) {
                    pane.requestFocusInWindow();
                }
            });
        });
    }

    @Override
    public CompletableFuture<DiffResult> openDiff(String oldFilePath, String newFilePath,
                                                  String newContents, String tabName) {
        CompletableFuture<DiffResult> result = new CompletableFuture<>();
        String oldContents;
        try {
            oldContents = currentContents(oldFilePath);
        } catch (Exception e) {
            result.completeExceptionally(e);
            return result;
        }
        Edt.later(() -> {
            try {
                DiffTopComponent tc = new DiffTopComponent(tabName, newFilePath, oldContents, newContents, result);
                tc.open();
                tc.requestActive();
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    @Override
    public boolean closeTab(String tabName) {
        DiffTopComponent tc = DiffTopComponent.byName(tabName);
        if (tc == null) {
            return false;
        }
        Edt.later(tc::reject);
        return true;
    }

    @Override
    public int closeAllDiffTabs() {
        List<DiffTopComponent> all = DiffTopComponent.all();
        Edt.later(() -> all.forEach(DiffTopComponent::reject));
        return all.size();
    }

    @Override
    public Boolean isDirty(String filePath) {
        DataObject d = dataObject(filePath);
        return d == null ? null : d.isModified();
    }

    @Override
    public boolean save(String filePath) throws Exception {
        DataObject d = dataObject(filePath);
        if (d == null) {
            throw new ToolException("File not found: " + filePath);
        }
        SaveCookie sc = d.getLookup().lookup(SaveCookie.class);
        if (sc == null) {
            return false;
        }
        sc.save();
        return true;
    }

    @Override
    public List<Map<String, Object>> diagnostics(String fileUri) {
        // NetBeans has no public API for reading the editor's error list, so none are reported.
        return List.of();
    }

    // ------------------------------------------------------------------

    private static DataObject dataObject(String path) {
        try {
            FileObject fo = FileUtil.toFileObject(FileUtil.normalizeFile(new File(path)));
            return fo == null ? null : DataObject.find(fo);
        } catch (Exception e) {
            return null;
        }
    }

    /** Text as the user sees it: the open (possibly unsaved) document, else the file on disk. */
    static String currentContents(String path) throws Exception {
        DataObject d = dataObject(path);
        if (d != null) {
            EditorCookie ec = d.getLookup().lookup(EditorCookie.class);
            if (ec != null && ec.getDocument() != null) {
                StyledDocument doc = ec.getDocument();
                String[] text = new String[1];
                Exception[] err = new Exception[1];
                doc.render(() -> {
                    try {
                        text[0] = doc.getText(0, doc.getLength());
                    } catch (Exception e) {
                        err[0] = e;
                    }
                });
                if (err[0] != null) {
                    throw err[0];
                }
                return text[0];
            }
        }
        Path p = Path.of(path);
        return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
    }
}
