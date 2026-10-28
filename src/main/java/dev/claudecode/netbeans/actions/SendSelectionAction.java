package dev.claudecode.netbeans.actions;

import dev.claudecode.netbeans.ChatTopComponent;
import dev.claudecode.netbeans.ClaudeCodeService;
import dev.claudecode.netbeans.EditorSelection;
import dev.claudecode.netbeans.core.ClaudeIdeServer;
import dev.claudecode.netbeans.core.Ide;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.nio.file.Path;
import org.netbeans.api.editor.EditorRegistry;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionReferences;
import org.openide.awt.ActionRegistration;
import org.openide.awt.StatusDisplayer;
import org.openide.util.NbBundle.Messages;

/** Inserts an @file#Lx-y reference to the current selection into the Claude Code prompt. */
@ActionID(category = "Tools", id = "dev.claudecode.netbeans.actions.SendSelectionAction")
@ActionRegistration(displayName = "#CTL_SendSelectionAction")
@ActionReferences({
    @ActionReference(path = "Menu/Tools", position = 152),
    @ActionReference(path = "Editors/Popup", position = 4050, separatorBefore = 4040),
    @ActionReference(path = "Shortcuts", name = "DO-K")
})
@Messages("CTL_SendSelectionAction=Send Selection to Claude Code")
public final class SendSelectionAction implements ActionListener {

    @Override
    public void actionPerformed(ActionEvent e) {
        ClaudeIdeServer server = ClaudeCodeService.get().server();
        Ide.Selection s = EditorSelection.from(EditorRegistry.lastFocusedComponent());
        if (s != null && insertIntoChat(s)) {
            return;
        }
        if (server == null || s == null) {
            StatusDisplayer.getDefault().setStatusText("Claude Code: open a file in the editor first");
            return;
        }
        if (server.connectionCount() == 0) {
            DialogDisplayer.getDefault().notify(new NotifyDescriptor.Message(
                    "No Claude Code session is connected.\nOpen Tools > Claude Code Chat, "
                    + "or run /ide inside a terminal session.",
                    NotifyDescriptor.INFORMATION_MESSAGE));
            return;
        }
        int endLine = s.endLine();
        if (endLine > s.startLine() && s.endCharacter() == 0) {
            endLine--; // a selection ending at column 0 doesn't include that line
        }
        server.atMention(s.filePath(), s.startLine(), endLine);
        StatusDisplayer.getDefault().setStatusText("Sent lines " + (s.startLine() + 1) + "-" + (endLine + 1)
                + " to Claude Code");
    }

    /** If the chat window is open, mention the selection there as @file#Lx-y. */
    private static boolean insertIntoChat(Ide.Selection s) {
        int start = s.startLine() + 1;
        int end = s.endLine() + 1;
        if (end > start && s.endCharacter() == 0) {
            end--;
        }
        String path = s.filePath();
        try {
            Path base = StartClaudeAction.workingDirectory().toPath().toAbsolutePath();
            Path p = Path.of(path).toAbsolutePath();
            if (p.startsWith(base)) {
                path = base.relativize(p).toString().replace(File.separatorChar, '/');
            }
        } catch (RuntimeException ignored) {
        }
        String mention = "@" + path + (s.isEmpty() ? "" : "#L" + start + (end != start ? "-" + end : "")) + " ";
        return ChatTopComponent.insertIntoChat(mention);
    }
}
