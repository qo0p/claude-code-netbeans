package dev.claudecode.netbeans.chat;

import java.io.File;
import java.util.concurrent.CompletableFuture;

/** What the chat panel needs from the IDE. Called on the Swing thread unless noted. */
public interface ChatHost {

    /** Selection or open file in the editor. Lines are 1-based; text is empty with no selection. */
    record EditorContext(String filePath, String selectedText, int startLine, int endLine) {
        public boolean hasSelection() {
            return selectedText != null && !selectedText.isEmpty();
        }
    }

    /** A project open in the IDE. {@code kind} is e.g. "Maven", "Gradle", "Ant" or null. */
    record ProjectInfo(String name, String path, boolean main, String kind) {
    }

    /** A diff shown to the user. The future completes with true when accepted. */
    interface DiffReview {
        CompletableFuture<Boolean> result();

        void accept();

        void reject();
    }

    File workingDirectory();

    /** Projects currently open in the IDE, main project first. */
    java.util.List<ProjectInfo> openProjects();

    /** True while the IDE is still opening projects (for example right after startup). */
    default boolean projectsLoading() {
        return false;
    }

    EditorContext editorContext();

    /** Current text of a file as the user sees it (unsaved edits included); "" if it doesn't exist. May be called off the Swing thread. */
    String currentContents(String filePath) throws Exception;

    DiffReview openDiff(String filePath, String oldContents, String newContents, String title);

    void openFile(String filePath, int line);

    void openUrl(String url);

    String loadSetting(String key);

    void saveSetting(String key, String value);
}
