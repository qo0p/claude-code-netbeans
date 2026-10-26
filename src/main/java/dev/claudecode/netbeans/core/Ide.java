package dev.claudecode.netbeans.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * What the protocol layer needs from the IDE. The NetBeans module implements
 * this; tests implement it with a fake. Methods are called on worker threads,
 * never on the Swing event thread.
 */
public interface Ide {

    /** A text selection. Lines and characters are 0-based. */
    record Selection(String filePath, String text, int startLine, int startCharacter,
                     int endLine, int endCharacter) {
        public boolean isEmpty() {
            return startLine == endLine && startCharacter == endCharacter;
        }
    }

    record EditorTab(String filePath, boolean active, boolean dirty) {
    }

    /** Outcome of an openDiff review. {@code contents} is set when accepted. */
    record DiffResult(boolean accepted, String contents) {
    }

    List<String> workspaceFolders();

    /** Current selection in the focused editor, or null if no file editor is focused. */
    Selection currentSelection() throws Exception;

    List<EditorTab> openEditors() throws Exception;

    void openFile(String filePath, String startText, String endText,
                  boolean selectToEndOfLine, boolean makeFrontmost) throws Exception;

    /** Shows a diff for review. Completes when the user accepts or rejects. */
    CompletableFuture<DiffResult> openDiff(String oldFilePath, String newFilePath,
                                           String newContents, String tabName);

    boolean closeTab(String tabName);

    int closeAllDiffTabs();

    /** Null if the document is not known to the IDE. */
    Boolean isDirty(String filePath) throws Exception;

    /** Returns true if something was saved. */
    boolean save(String filePath) throws Exception;

    /** Diagnostics in the shape [{uri, diagnostics:[...]}]. May be empty. */
    List<Map<String, Object>> diagnostics(String fileUri) throws Exception;
}
