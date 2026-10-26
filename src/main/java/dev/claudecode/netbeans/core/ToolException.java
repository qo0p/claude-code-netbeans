package dev.claudecode.netbeans.core;

/** A tool failure that is reported back to Claude Code as an MCP tool error. */
public final class ToolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ToolException(String message) {
        super(message);
    }
}
