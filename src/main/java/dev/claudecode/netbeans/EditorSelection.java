package dev.claudecode.netbeans;

import dev.claudecode.netbeans.core.Ide;
import java.io.File;
import javax.swing.text.BadLocationException;
import javax.swing.text.Caret;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;

/** Reads file and selection information out of NetBeans editor components. Call on the EDT. */
public final class EditorSelection {

    private EditorSelection() {
    }

    /** The file shown in a document, or null for documents that are not files on disk. */
    public static File fileOf(Document doc) {
        if (doc == null) {
            return null;
        }
        Object o = doc.getProperty(Document.StreamDescriptionProperty);
        FileObject fo = null;
        if (o instanceof DataObject) {
            fo = ((DataObject) o).getPrimaryFile();
        } else if (o instanceof FileObject) {
            fo = (FileObject) o;
        }
        return fo == null ? null : FileUtil.toFile(fo);
    }

    /** Selection in the given editor, or null if it doesn't show a file. Lines are 0-based. */
    public static Ide.Selection from(JTextComponent c) {
        if (c == null) {
            return null;
        }
        Document doc = c.getDocument();
        File f = fileOf(doc);
        if (f == null) {
            return null;
        }
        Caret caret = c.getCaret();
        if (caret == null) {
            return null;
        }
        int start = Math.min(caret.getDot(), caret.getMark());
        int end = Math.max(caret.getDot(), caret.getMark());
        String text = "";
        if (end > start) {
            try {
                text = doc.getText(start, end - start);
            } catch (BadLocationException e) {
                text = "";
            }
        }
        Element root = doc.getDefaultRootElement();
        int startLine = root.getElementIndex(start);
        int endLine = root.getElementIndex(end);
        return new Ide.Selection(f.getAbsolutePath(), text,
                startLine, start - root.getElement(startLine).getStartOffset(),
                endLine, end - root.getElement(endLine).getStartOffset());
    }
}
