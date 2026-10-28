# Claude Code for NetBeans

A NetBeans module that brings **Claude Code** into Apache NetBeans, like the official VS Code extension.

## Chat window

**Tools → Claude Code Chat** (or **Ctrl+Esc**) opens a Claude Code chat docked on the right:

- Answers stream in as Claude writes them, with formatting and code blocks.
- Each step Claude takes (reading files, searching, running commands) is listed as it happens.
- Claude knows which projects are open in NetBeans (names, folders, main project, Maven/Gradle/Ant) and can read and edit files in all of them. The chat starts in the project of the file you're editing; if you open or close projects during a chat, Claude is told with your next message.
- A live line at the bottom shows what Claude is doing right now (waiting, thinking, preparing or running a tool, writing) with a running timer. It turns red if Claude Code goes quiet for more than 30 seconds.
- Thinking steps appear as "Thought for 4s · ~200 tokens". Claude Code doesn't currently pass on the thinking text itself; if it does, click the line to read it.
- **Edits open as a NetBeans diff.** Accept or Reject in the diff tab or in the chat. *Accept all edits* switches to editing without asking.
- Commands and other actions ask first: **Allow**, **Always allow** (for this conversation), or **Deny**.
- The mode menu switches between **Ask before edits**, **Edit automatically**, and **Plan mode**. In plan mode Claude proposes a plan and waits for your approval before touching code.
- Your current selection (or open file) is sent along with each message while the checkbox above the message box is ticked. *Send Selection to Claude Code* (Ctrl+Alt+K) puts an `@file#L10-20` reference into the message box.
- **Stop** (or Esc) interrupts Claude. **New chat** starts over. **Resume** continues the last conversation.

The chat runs the `claude` CLI in the background, so you need Claude Code installed and signed in (run `claude` once in a terminal to log in). If NetBeans can't find it, set `-J-Dclaude.code.cli=/full/path/to/claude` (see Settings).

## Terminal integration

If you prefer Claude Code in a terminal, the module also connects a terminal session to NetBeans, the same way the VS Code and JetBrains extensions do:

- **Diff review** – when Claude edits a file, the change opens in a NetBeans diff tab. Click **Accept** (Ctrl+Enter) or **Reject** (Esc). Closing the tab rejects.
- **Selection context** – whatever you select in the editor is shared with Claude automatically (the CLI shows `⧉ N lines selected`).
- **@-mentions** – *Send Selection to Claude Code* (editor right-click menu, or **Ctrl+Alt+K**) inserts `@File.java#L10-20` into the Claude prompt.
- **Start Claude Code in Terminal** (Tools menu, **Ctrl+Alt+Shift+T**) – opens a terminal in the current project with `claude` already connected.
- Claude can also open files, list open editors and projects, check for unsaved changes, and save documents.

## Build and install (quick way)

Quit NetBeans, then run:

```
./update-plugin.sh
```

It finds Maven and your NetBeans user folder, builds the plugin, and installs it. Start NetBeans again afterwards. Use the same command after every update.

## Build

Requires JDK 17+ and Maven 3.9+.

```
mvn package
```

If your Maven settings point at a company repository you can't reach, build against Maven Central with:

```
mvn -U -s build-settings.xml -gs build-settings.xml package
```

The installable module is `target/claude-code-netbeans-<version>.nbm`. The chat window header shows the installed version and when it was built.

## Install

NetBeans → **Tools → Plugins → Downloaded → Add Plugins…** → choose the `.nbm` → **Install**. Restart when asked.

Requires NetBeans 22 or newer and the Claude Code CLI (`npm install -g @anthropic-ai/claude-code`, or the native installer).

## Use

**Option A:** Tools → **Start Claude Code in Terminal**. A terminal opens in your project and Claude connects to NetBeans.

**Option B:** In any terminal (including NetBeans' own *Window → Terminal*), `cd` into an open project and run `claude`, then type `/ide` and pick **NetBeans**.

**Tools → Claude Code Status** shows the port, connected sessions, and a ready-to-paste command.

## Settings

Add to `netbeans_default_options` in `etc/netbeans.conf` if needed:

| Option | Purpose |
|---|---|
| `-J-Dclaude.code.cli=/full/path/to/claude` | `claude` isn't on the PATH NetBeans sees (common on macOS when NetBeans starts from the Dock). |
| `-J-Dclaude.terminal="wezterm start --"` | Linux: use a specific terminal. The shell command is appended. |

## How it works

On startup the module starts a WebSocket server on `127.0.0.1` (random port) and writes `~/.claude/ide/<port>.lock` with the port, open project folders and a random auth token. Claude Code reads that file, connects with the token, and talks MCP (JSON-RPC) to it. Connections without the token are refused with HTTP 401. The lock file is owner-readable only and is removed when NetBeans exits.

```
src/main/java/dev/claudecode/netbeans/
├── chat/                    the chat window UI (ChatPanel), plain Swing
├── ChatTopComponent         docks the chat in NetBeans; diffs, editor context
├── core/                    plain JDK, no NetBeans dependencies
│   ├── ClaudeSession        drives `claude -p --input-format stream-json`
│   ├── Markdown, ProposedEdit, ClaudeCli
│   ├── ClaudeIdeServer      MCP handling + the 11 IDE tools
│   ├── WebSocketServer      RFC 6455 server, loopback only, token-checked
│   ├── LockFile             ~/.claude/ide/<port>.lock
│   ├── Json, Ide, ToolException
├── ClaudeCodeService        starts/stops the server with the IDE
├── NetBeansIde              tool implementations on NetBeans APIs
├── DiffTopComponent         Accept/Reject diff tab
├── SelectionTracker         pushes editor selection to Claude
├── TerminalLauncher         opens claude in an OS terminal
└── actions/                 Start, Send Selection, Status
```

## Limitations

- `getDiagnostics` returns no errors: NetBeans has no public API for reading the editor's error list.
- The diff tab is review-only; edit the file after accepting if you want to tweak Claude's change.
- *Start Claude Code in Terminal* uses an external terminal window. To run inside NetBeans, use Option B in *Window → Terminal*.
- The chat's *Resume* continues the conversation, but earlier messages aren't redrawn in the window.

## Troubleshooting

- **`/ide` doesn't list NetBeans** – run `claude` from inside a folder of a project that is open in NetBeans, and check that `~/.claude/ide/` contains a `.lock` file.
- **Terminal opens but `claude: command not found`** – set `-J-Dclaude.code.cli` (see Settings).
- Logs: *View → IDE Log*, look for `ClaudeIdeServer`.
