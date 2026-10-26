package dev.claudecode.netbeans.core;

import java.util.List;
import java.util.Map;

/**
 * Works out what a file will contain after Claude's Edit, MultiEdit or Write
 * tool runs, so the change can be shown as a diff before it is allowed.
 */
public final class ProposedEdit {

    private ProposedEdit() {
    }

    /** True for tools whose effect can be previewed as a diff of one file. */
    public static boolean isFileEdit(String toolName) {
        return "Edit".equals(toolName) || "MultiEdit".equals(toolName) || "Write".equals(toolName);
    }

    public static String filePath(Map<String, Object> input) {
        return Json.str(input, "file_path");
    }

    /**
     * Applies the edit to {@code current}. Returns null if the edit can't be
     * applied (text to replace not found), in which case the caller should fall
     * back to a plain approval prompt.
     */
    public static String apply(String toolName, Map<String, Object> input, String current) {
        switch (toolName) {
            case "Write":
                return Json.str(input, "content");
            case "Edit":
                return replace(current, Json.str(input, "old_string"), Json.str(input, "new_string"),
                        Json.bool(input, "replace_all", false));
            case "MultiEdit": {
                String text = current;
                Object edits = input.get("edits");
                if (!(edits instanceof List)) {
                    return null;
                }
                for (Object e : (List<?>) edits) {
                    Map<String, Object> m = Json.map(e);
                    text = replace(text, Json.str(m, "old_string"), Json.str(m, "new_string"),
                            Json.bool(m, "replace_all", false));
                    if (text == null) {
                        return null;
                    }
                }
                return text;
            }
            default:
                return null;
        }
    }

    private static String replace(String text, String oldString, String newString, boolean all) {
        if (text == null || oldString == null || newString == null) {
            return null;
        }
        if (oldString.isEmpty()) {
            return text.isEmpty() ? newString : null;
        }
        int at = text.indexOf(oldString);
        if (at < 0) {
            return null;
        }
        if (all) {
            return text.replace(oldString, newString);
        }
        return text.substring(0, at) + newString + text.substring(at + oldString.length());
    }
}
