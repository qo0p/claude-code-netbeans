package dev.claudecode.netbeans.actions;

import dev.claudecode.netbeans.ClaudeCodeService;
import dev.claudecode.netbeans.EditorSelection;
import dev.claudecode.netbeans.TerminalLauncher;
import dev.claudecode.netbeans.core.ClaudeIdeServer;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import javax.swing.text.JTextComponent;
import org.netbeans.api.editor.EditorRegistry;
import org.netbeans.api.project.FileOwnerQuery;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionReferences;
import org.openide.awt.ActionRegistration;
import org.openide.awt.StatusDisplayer;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.NbBundle.Messages;

@ActionID(category = "Tools", id = "dev.claudecode.netbeans.actions.StartClaudeAction")
@ActionRegistration(displayName = "#CTL_StartClaudeAction")
@ActionReferences({
    @ActionReference(path = "Menu/Tools", position = 150),
    @ActionReference(path = "Shortcuts", name = "DOS-T")
})
@Messages("CTL_StartClaudeAction=Start Claude Code in Terminal")
public final class StartClaudeAction implements ActionListener {

    @Override
    public void actionPerformed(ActionEvent e) {
        ClaudeIdeServer server = ClaudeCodeService.get().server();
        if (server == null) {
            DialogDisplayer.getDefault().notify(new NotifyDescriptor.Message(
                    "The Claude Code integration is not running: " + ClaudeCodeService.get().startupError(),
                    NotifyDescriptor.ERROR_MESSAGE));
            return;
        }
        File dir = workingDirectory();
        try {
            TerminalLauncher.launch(dir, server.port());
            StatusDisplayer.getDefault().setStatusText("Starting Claude Code in " + dir);
        } catch (IOException ex) {
            DialogDisplayer.getDefault().notify(new NotifyDescriptor.Message(
                    "Could not open a terminal (" + ex.getMessage() + ").\n\nRun this in any terminal instead:\n\n"
                    + TerminalLauncher.manualCommand(dir, server.port()),
                    NotifyDescriptor.WARNING_MESSAGE));
        }
    }

    /** Project of the file being edited, else the main project, else the first open one, else home. */
    public static File workingDirectory() {
        JTextComponent c = EditorRegistry.lastFocusedComponent();
        File f = c == null ? null : EditorSelection.fileOf(c.getDocument());
        if (f != null) {
            FileObject fo = FileUtil.toFileObject(FileUtil.normalizeFile(f));
            Project p = fo == null ? null : FileOwnerQuery.getOwner(fo);
            File dir = p == null ? null : FileUtil.toFile(p.getProjectDirectory());
            if (dir != null) {
                return dir;
            }
        }
        Project main = OpenProjects.getDefault().getMainProject();
        if (main != null && FileUtil.toFile(main.getProjectDirectory()) != null) {
            return FileUtil.toFile(main.getProjectDirectory());
        }
        for (Project p : OpenProjects.getDefault().getOpenProjects()) {
            File dir = FileUtil.toFile(p.getProjectDirectory());
            if (dir != null) {
                return dir;
            }
        }
        return new File(System.getProperty("user.home"));
    }
}
