package dev.claudecode.netbeans.chat;

import dev.claudecode.netbeans.core.ClaudeCli;
import dev.claudecode.netbeans.core.ClaudeSession;
import dev.claudecode.netbeans.core.Json;
import dev.claudecode.netbeans.core.Markdown;
import dev.claudecode.netbeans.core.ProposedEdit;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.DefaultEditorKit;

/**
 * The Claude Code chat panel: a conversation with the Claude Code CLI,
 * with edits reviewed as diffs and other actions approved inline.
 * All methods must be called on the Swing event thread.
 */
public final class ChatPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    public enum Mode {
        ASK("default", "Ask before edits"),
        AUTO("acceptEdits", "Edit automatically"),
        PLAN("plan", "Plan mode");

        final String cli;
        final String label;

        Mode(String cli, String label) {
            this.cli = cli;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final transient ChatHost host;
    private final StackPanel transcript = new StackPanel(10);
    private final JScrollPane scroll;
    private final JTextArea input = new HintTextArea("Ask Claude…  (Enter to send, Shift+Enter for a new line)");
    private final JButton sendButton = new JButton("Send");
    private final JButton stopButton = new JButton("Stop");
    private final JButton newButton = new JButton("New chat");
    private final JButton resumeButton = new JButton("Resume");
    private final JComboBox<Mode> modeBox = new JComboBox<>(Mode.values());
    private final JCheckBox contextBox = new JCheckBox();
    private final JLabel status = new JLabel(" ");
    private final Timer contextTimer;
    private final Timer renderTimer;

    private transient ClaudeSession session;
    private File sessionDir;
    private boolean busy;
    private boolean changingMode;
    private transient ChatHost.EditorContext context;
    private HtmlView streaming;
    private final StringBuilder streamText = new StringBuilder();
    private final Map<String, ToolLine> toolLines = new HashMap<>();
    private final Set<String> alwaysAllow = new HashSet<>();
    private final List<Card> openCards = new ArrayList<>();

    public ChatPanel(ChatHost host) {
        this.host = host;
        setLayout(new BorderLayout());

        // header
        JLabel title = new JLabel("Claude Code");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.setForeground(HtmlView.Theme.accent());
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        tools.setOpaque(false);
        modeBox.setToolTipText("How Claude handles changes to your files");
        newButton.setToolTipText("Start a new conversation");
        resumeButton.setToolTipText("Continue the previous conversation");
        tools.add(modeBox);
        tools.add(newButton);
        tools.add(resumeButton);
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, HtmlView.Theme.border()),
                BorderFactory.createEmptyBorder(4, 8, 4, 4)));
        header.add(title, BorderLayout.WEST);
        header.add(tools, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        // transcript
        transcript.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        transcript.setBackground(HtmlView.Theme.bg());
        scroll = new JScrollPane(transcript, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        add(scroll, BorderLayout.CENTER);

        // composer
        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.setRows(3);
        input.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "claude-send");
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
                DefaultEditorKit.insertBreakAction);
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "claude-stop");
        input.getActionMap().put("claude-send", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                send();
            }
        });
        input.getActionMap().put("claude-stop", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                stop();
            }
        });
        JScrollPane inputScroll = new JScrollPane(input);
        inputScroll.setBorder(BorderFactory.createLineBorder(HtmlView.Theme.border()));

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));
        sendButton.setAlignmentX(0.5f);
        stopButton.setAlignmentX(0.5f);
        Dimension bsize = new Dimension(Math.max(sendButton.getPreferredSize().width,
                stopButton.getPreferredSize().width), sendButton.getPreferredSize().height);
        sendButton.setMaximumSize(bsize);
        stopButton.setMaximumSize(bsize);
        buttons.add(sendButton);
        buttons.add(Box.createVerticalStrut(4));
        buttons.add(stopButton);
        stopButton.setEnabled(false);
        stopButton.setToolTipText("Stop Claude (Esc)");

        contextBox.setSelected(true);
        contextBox.setFocusable(false);
        contextBox.setToolTipText("Send your current editor selection, or the open file's name, along with the message");
        status.setForeground(HtmlView.Theme.dim());

        JPanel composer = new JPanel(new BorderLayout(6, 4));
        composer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, HtmlView.Theme.border()),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        composer.add(contextBox, BorderLayout.NORTH);
        composer.add(inputScroll, BorderLayout.CENTER);
        composer.add(buttons, BorderLayout.EAST);
        composer.add(status, BorderLayout.SOUTH);
        add(composer, BorderLayout.SOUTH);

        sendButton.addActionListener(e -> send());
        stopButton.addActionListener(e -> stop());
        newButton.addActionListener(e -> newChat());
        resumeButton.addActionListener(e -> resume());
        modeBox.addActionListener(e -> {
            Mode m = (Mode) modeBox.getSelectedItem();
            host.saveSetting("mode", m.name());
            if (!changingMode && session != null && session.isAlive()) {
                session.setPermissionMode(m.cli);
            }
        });
        try {
            String saved = host.loadSetting("mode");
            if (saved != null) {
                changingMode = true;
                modeBox.setSelectedItem(Mode.valueOf(saved));
            }
        } catch (IllegalArgumentException ignored) {
        } finally {
            changingMode = false;
        }
        resumeButton.setEnabled(host.loadSetting("lastSessionId") != null);

        renderTimer = new Timer(60, e -> renderStreaming());
        renderTimer.setRepeats(false);
        contextTimer = new Timer(500, e -> refreshContext());
        contextTimer.start();
        refreshContext();
        showWelcome();
    }

    // ------------------------------------------------------------------ public API

    public void focusInput() {
        input.requestFocusInWindow();
    }

    /** Adds text (e.g. an @-mention) at the caret of the message box. */
    public void insertText(String text) {
        input.replaceSelection(text);
        focusInput();
    }

    public boolean isConnected() {
        return session != null && session.isAlive();
    }

    public void shutdown() {
        contextTimer.stop();
        renderTimer.stop();
        stopSession();
    }

    // ------------------------------------------------------------------ actions

    void send() {
        String text = input.getText().trim();
        if (text.isEmpty() || busy) {
            return;
        }
        refreshContext();
        ChatHost.EditorContext ctx = contextBox.isEnabled() && contextBox.isSelected() ? context : null;
        if (!ensureSession(null)) {
            return;
        }
        input.setText("");
        addUserMessage(text, ctx);
        session.sendUserMessage(withContext(text, ctx));
        setBusy(true);
        status.setText("Thinking…");
    }

    void stop() {
        if (session != null && busy) {
            cancelOpenCards("Cancelled");
            session.interrupt();
            status.setText("Stopping…");
        }
    }

    void newChat() {
        stopSession();
        clearTranscript();
        showWelcome();
        focusInput();
    }

    void resume() {
        String id = host.loadSetting("lastSessionId");
        if (id == null) {
            return;
        }
        stopSession();
        clearTranscript();
        addInfo("Continuing your previous conversation. Earlier messages aren't shown here, but Claude remembers them.");
        ensureSession(id);
        focusInput();
    }

    private boolean ensureSession(String resumeId) {
        if (session != null && session.isAlive()) {
            return true;
        }
        File exe = ClaudeCli.find();
        if (exe == null) {
            addError("Couldn't find the `claude` command.\n\nInstall Claude Code (`npm install -g @anthropic-ai/claude-code`), "
                    + "or tell NetBeans where it is by adding `-J-Dclaude.code.cli=/full/path/to/claude` to "
                    + "`netbeans_default_options` in `etc/netbeans.conf`, then restart NetBeans.");
            return false;
        }
        sessionDir = host.workingDirectory();
        Mode mode = (Mode) modeBox.getSelectedItem();
        ClaudeSession[] holder = new ClaudeSession[1];
        holder[0] = new ClaudeSession(ClaudeSession.buildCommand(ClaudeCli.command(exe), mode.cli, resumeId),
                sessionDir, ClaudeCli.environment(exe), new Events(holder));
        try {
            holder[0].start();
        } catch (IOException e) {
            addError("Couldn't start Claude Code (" + exe + "): " + e.getMessage());
            return false;
        }
        session = holder[0];
        addInfo("Working in " + sessionDir.getName() + "  (" + sessionDir.getAbsolutePath() + ")");
        return true;
    }

    private void stopSession() {
        cancelOpenCards("Cancelled");
        ClaudeSession s = session;
        session = null;
        setBusy(false);
        streaming = null;
        if (s != null) {
            Thread t = new Thread(s::stop, "claude-session-stop");
            t.setDaemon(true);
            t.start();
        }
    }

    private void setBusy(boolean b) {
        busy = b;
        sendButton.setEnabled(!b);
        stopButton.setEnabled(b);
    }

    private void setMode(Mode m, boolean tellCli) {
        changingMode = true;
        try {
            modeBox.setSelectedItem(m);
        } finally {
            changingMode = false;
        }
        if (tellCli && session != null) {
            session.setPermissionMode(m.cli);
        }
    }

    // ------------------------------------------------------------------ context

    private void refreshContext() {
        ChatHost.EditorContext ctx;
        try {
            ctx = host.editorContext();
        } catch (RuntimeException e) {
            ctx = null;
        }
        context = ctx;
        if (ctx == null) {
            contextBox.setText("No file open in the editor");
            contextBox.setEnabled(false);
            return;
        }
        contextBox.setEnabled(true);
        String name = new File(ctx.filePath()).getName();
        contextBox.setText(ctx.hasSelection()
                ? "Include selection: " + name + (ctx.startLine() == ctx.endLine()
                        ? " line " + ctx.startLine() : " lines " + ctx.startLine() + "–" + ctx.endLine())
                : "Include open file: " + name);
    }

    static String withContext(String text, ChatHost.EditorContext ctx) {
        if (ctx == null) {
            return text;
        }
        if (ctx.hasSelection()) {
            return "<ide_selection>The user selected lines " + ctx.startLine() + " to " + ctx.endLine()
                    + " from " + ctx.filePath() + ":\n" + ctx.selectedText()
                    + "\n\nThis may or may not be related to the current task.</ide_selection>\n\n" + text;
        }
        return "<ide_opened_file>The user opened the file " + ctx.filePath()
                + " in the IDE. This may or may not be related to the current task.</ide_opened_file>\n\n" + text;
    }

    // ------------------------------------------------------------------ events from the CLI

    /** Forwards CLI events to the Swing thread, ignoring ones from a session that was replaced. */
    private final class Events implements ClaudeSession.Listener {
        private final ClaudeSession[] owner;

        Events(ClaudeSession[] owner) {
            this.owner = owner;
        }

        private void ui(Runnable r) {
            SwingUtilities.invokeLater(() -> {
                if (session != null && session == owner[0]) {
                    r.run();
                }
            });
        }

        @Override
        public void onInit(String sessionId, String model) {
            ui(() -> {
                if (sessionId != null) {
                    host.saveSetting("lastSessionId", sessionId);
                    resumeButton.setEnabled(true);
                }
                if (!busy) {
                    status.setText(model != null ? "Ready · " + model : "Ready");
                }
            });
        }

        @Override
        public void onTextDelta(String text) {
            ui(() -> {
                if (streaming == null) {
                    streaming = addAssistantView();
                    streamText.setLength(0);
                }
                streamText.append(text);
                if (!renderTimer.isRunning()) {
                    renderTimer.start();
                }
            });
        }

        @Override
        public void onAssistantText(String text) {
            ui(() -> {
                if (streaming == null) {
                    streaming = addAssistantView();
                }
                streamText.setLength(0);
                streamText.append(text);
                renderStreaming();
                streaming = null;
            });
        }

        @Override
        public void onToolUse(String toolUseId, String name, Map<String, Object> toolInput) {
            ui(() -> {
                finishStreaming();
                ToolLine line = new ToolLine(name, toolInput);
                toolLines.put(toolUseId, line);
                append(line);
            });
        }

        @Override
        public void onToolResult(String toolUseId, String text, boolean isError) {
            ui(() -> {
                ToolLine line = toolLines.remove(toolUseId);
                if (line != null) {
                    line.finish(text, isError);
                    relayout();
                }
            });
        }

        @Override
        public void onPermissionRequest(ClaudeSession.PermissionRequest r) {
            ui(() -> handlePermission(owner[0], r));
        }

        @Override
        public void onStatus(String s) {
            ui(() -> {
                if ("requesting".equals(s)) {
                    status.setText("Thinking…");
                } else if ("compacting".equals(s)) {
                    status.setText("Compacting the conversation…");
                } else if ("compacted".equals(s)) {
                    addInfo("Earlier messages were summarized to free up space.");
                }
            });
        }

        @Override
        public void onTurnComplete(ClaudeSession.TurnResult r) {
            ui(() -> {
                finishStreaming();
                cancelOpenCards("Cancelled");
                setBusy(false);
                if (r.interrupted()) {
                    addInfo("Stopped.");
                } else if (r.error()) {
                    addError(r.text() != null && !r.text().isBlank() ? r.text()
                            : "Claude Code reported an error (" + r.subtype() + ").");
                }
                status.setText(String.format(Locale.ROOT, "Done in %.1fs · session cost $%.2f",
                        r.durationMs() / 1000.0, r.costUsd()));
                focusInput();
            });
        }

        @Override
        public void onExit(int exitCode, String stderrTail) {
            ui(() -> {
                finishStreaming();
                cancelOpenCards("Cancelled");
                session = null;
                setBusy(false);
                StringBuilder msg = new StringBuilder("Claude Code stopped unexpectedly (exit code " + exitCode + ").");
                if (stderrTail != null && !stderrTail.isBlank()) {
                    msg.append("\n\n```\n").append(stderrTail.strip()).append("\n```");
                    String lower = stderrTail.toLowerCase(Locale.ROOT);
                    if (lower.contains("login") || lower.contains("log in") || lower.contains("auth")
                            || lower.contains("api key")) {
                        msg.append("\n\nRun `claude` once in a terminal to sign in, then send your message again.");
                    }
                }
                addError(msg.toString());
                status.setText("Not connected");
            });
        }
    }

    // ------------------------------------------------------------------ permissions

    private void handlePermission(ClaudeSession owner, ClaudeSession.PermissionRequest r) {
        if (alwaysAllow.contains(allowKey(r))) {
            owner.allow(r, null);
            return;
        }
        finishStreaming();
        if (ProposedEdit.isFileEdit(r.toolName()) && reviewEdit(owner, r)) {
            return;
        }
        if ("ExitPlanMode".equals(r.toolName())) {
            planCard(owner, r);
            return;
        }
        genericCard(owner, r);
    }

    private boolean reviewEdit(ClaudeSession owner, ClaudeSession.PermissionRequest r) {
        String path = ProposedEdit.filePath(r.input());
        if (path == null) {
            return false;
        }
        String before;
        try {
            before = host.currentContents(path);
        } catch (Exception e) {
            return false;
        }
        String after = ProposedEdit.apply(r.toolName(), r.input(), before);
        if (after == null) {
            return false;
        }
        String name = new File(path).getName();
        ChatHost.DiffReview review = host.openDiff(path, before, after, "Claude: " + name);
        int[] counts = lineChanges(before, after);
        Card card = new Card((before.isEmpty() ? "Create " : "Edit ") + name,
                "<p>Review the change in the editor tab, or decide here.</p><p class=\"dim\">"
                + Markdown.escape(shortPath(path)) + " &nbsp; <span class=\"ok\">+" + counts[0]
                + "</span> <span class=\"err\">−" + counts[1] + "</span></p>");
        card.button("Accept", true, review::accept);
        card.button("Accept all edits", false, () -> {
            setMode(Mode.AUTO, true);
            review.accept();
        });
        card.button("Reject", false, review::reject);
        card.onCancel = review::reject;
        review.result().whenComplete((ok, err) -> SwingUtilities.invokeLater(() -> {
            if (card.resolved) {
                return;
            }
            if (Boolean.TRUE.equals(ok)) {
                owner.allow(r, null);
                card.resolve("Accepted", HtmlView.Theme.ok());
            } else {
                owner.deny(r, "The user rejected this edit in NetBeans. Ask them what they would like instead.");
                card.resolve("Rejected", HtmlView.Theme.error());
            }
        }));
        append(card);
        return true;
    }

    private void planCard(ClaudeSession owner, ClaudeSession.PermissionRequest r) {
        String plan = Json.str(r.input(), "plan");
        Card card = new Card("Ready to code?", Markdown.toHtml(plan != null ? plan : "Claude has finished planning."));
        card.button("Yes, auto-accept edits", true, () -> {
            setMode(Mode.AUTO, true);
            owner.allow(r, null);
            card.resolve("Approved — editing automatically", HtmlView.Theme.ok());
        });
        card.button("Yes, ask before edits", false, () -> {
            setMode(Mode.ASK, true);
            owner.allow(r, null);
            card.resolve("Approved — asking before edits", HtmlView.Theme.ok());
        });
        card.button("Keep planning", false, () -> {
            owner.deny(r, "The user wants to keep planning. Ask what they would like to change in the plan.");
            card.resolve("Keep planning — tell Claude what to change", HtmlView.Theme.dim());
            focusInput();
        });
        card.onCancel = () -> owner.deny(r, "Cancelled by the user.");
        append(card);
    }

    private void genericCard(ClaudeSession owner, ClaudeSession.PermissionRequest r) {
        String tool = r.toolName();
        String title;
        StringBuilder body = new StringBuilder();
        if ("Bash".equals(tool)) {
            title = "Run this command?";
            body.append("<div class=\"code\">").append(codeHtml(Json.str(r.input(), "command"))).append("</div>");
            String desc = Json.str(r.input(), "description");
            if (desc != null) {
                body.append("<p class=\"dim\">").append(Markdown.escape(desc)).append("</p>");
            }
        } else if ("WebFetch".equals(tool)) {
            title = "Fetch this page?";
            body.append("<p>").append(Markdown.escape(String.valueOf(r.input().get("url")))).append("</p>");
        } else if (ProposedEdit.isFileEdit(tool)) {
            title = "Change " + new File(String.valueOf(ProposedEdit.filePath(r.input()))).getName() + "?";
            body.append("<p class=\"dim\">The change couldn't be previewed as a diff.</p>");
        } else {
            title = "Allow " + ToolLine.displayName(tool) + "?";
            String json = Json.write(r.input());
            body.append("<div class=\"code\">").append(codeHtml(json.length() > 800 ? json.substring(0, 800) + "…" : json))
                    .append("</div>");
        }
        Card card = new Card(title, body.toString());
        card.button("Allow", true, () -> {
            owner.allow(r, null);
            card.resolve("Allowed", HtmlView.Theme.ok());
        });
        card.button("Bash".equals(tool) ? "Always allow this command" : "Always allow " + ToolLine.displayName(tool),
                false, () -> {
                    alwaysAllow.add(allowKey(r));
                    owner.allow(r, null);
                    card.resolve("Allowed for this conversation", HtmlView.Theme.ok());
                });
        card.button("Deny", false, () -> {
            owner.deny(r, "The user denied this in NetBeans. Ask them how to proceed.");
            card.resolve("Denied", HtmlView.Theme.error());
        });
        card.onCancel = () -> owner.deny(r, "Cancelled by the user.");
        append(card);
    }

    private static String allowKey(ClaudeSession.PermissionRequest r) {
        return "Bash".equals(r.toolName()) ? "Bash:" + Json.str(r.input(), "command") : r.toolName();
    }

    private void cancelOpenCards(String outcome) {
        for (Card c : new ArrayList<>(openCards)) {
            Runnable cancel = c.onCancel;
            c.resolve(outcome, HtmlView.Theme.dim());
            if (cancel != null) {
                cancel.run();
            }
        }
    }

    static int[] lineChanges(String before, String after) {
        String[] a = before.split("\n", -1);
        String[] b = after.split("\n", -1);
        int start = 0;
        while (start < a.length && start < b.length && a[start].equals(b[start])) {
            start++;
        }
        int endA = a.length - 1;
        int endB = b.length - 1;
        while (endA >= start && endB >= start && a[endA].equals(b[endB])) {
            endA--;
            endB--;
        }
        return new int[]{Math.max(0, endB - start + 1), Math.max(0, endA - start + 1)};
    }

    // ------------------------------------------------------------------ transcript

    private void showWelcome() {
        HtmlView v = new HtmlView(this::openLink);
        v.setBodyHtml("<h3>Claude Code</h3>"
                + "<p class=\"dim\">Ask about your code, or ask Claude to change it. "
                + "Proposed edits open as a diff for you to accept or reject.</p>"
                + "<p class=\"dim\">Your editor selection is sent along when the box above the message field is ticked. "
                + "Use the menu at the top to let Claude edit without asking, or to plan first.</p>");
        append(v);
    }

    private void clearTranscript() {
        transcript.removeAll();
        toolLines.clear();
        openCards.clear();
        alwaysAllow.clear();
        streaming = null;
        relayout();
    }

    private void addUserMessage(String text, ChatHost.EditorContext ctx) {
        JPanel bubble = new JPanel(new BorderLayout());
        bubble.setBackground(HtmlView.Theme.subtleBg());
        bubble.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        HtmlView v = new HtmlView(this::openLink);
        StringBuilder html = new StringBuilder(Markdown.escape(text).replace("\n", "<br>"));
        if (ctx != null) {
            String name = new File(ctx.filePath()).getName();
            html.append("<br><span class=\"dim\">")
                    .append(ctx.hasSelection() ? "with selection from " + Markdown.escape(name) + ":" + ctx.startLine()
                            + (ctx.endLine() != ctx.startLine() ? "–" + ctx.endLine() : "")
                            : "with open file " + Markdown.escape(name))
                    .append("</span>");
        }
        v.setBodyHtml(html.toString());
        bubble.add(v, BorderLayout.CENTER);
        append(bubble);
        scrollToBottom();
    }

    private HtmlView addAssistantView() {
        HtmlView v = new HtmlView(this::openLink);
        append(v);
        return v;
    }

    private void renderStreaming() {
        if (streaming != null) {
            boolean pinned = atBottom();
            streaming.setMarkdown(streamText.toString());
            relayout();
            if (pinned) {
                scrollToBottom();
            }
        }
    }

    private void finishStreaming() {
        if (streaming != null) {
            renderTimer.stop();
            renderStreaming();
            streaming = null;
        }
    }

    private void addInfo(String text) {
        HtmlView v = new HtmlView(this::openLink);
        v.setBodyHtml("<span class=\"dim\">" + Markdown.escape(text) + "</span>");
        append(v);
    }

    private void addError(String markdown) {
        JPanel box = new JPanel(new BorderLayout());
        box.setOpaque(false);
        box.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, HtmlView.Theme.error()),
                BorderFactory.createEmptyBorder(2, 8, 2, 4)));
        HtmlView v = new HtmlView(this::openLink);
        v.setMarkdown(markdown);
        box.add(v, BorderLayout.CENTER);
        append(box);
    }

    private void append(JComponent c) {
        boolean pinned = atBottom();
        transcript.add(c);
        relayout();
        if (pinned || c instanceof Card) {
            scrollToBottom();
        }
    }

    private void relayout() {
        transcript.revalidate();
        transcript.repaint();
    }

    private boolean atBottom() {
        JScrollBar b = scroll.getVerticalScrollBar();
        return b.getValue() + b.getVisibleAmount() >= b.getMaximum() - 40;
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() -> {
            JScrollBar b = scroll.getVerticalScrollBar();
            b.setValue(b.getMaximum());
        }));
    }

    private void openLink(String target) {
        if (target.startsWith("nbfile:")) {
            String spec = target.substring("nbfile:".length());
            int hash = spec.lastIndexOf('#');
            int line = 0;
            if (hash > 0) {
                try {
                    line = Integer.parseInt(spec.substring(hash + 1));
                } catch (NumberFormatException ignored) {
                }
                spec = spec.substring(0, hash);
            }
            host.openFile(spec, line);
        } else {
            host.openUrl(target);
        }
    }

    String shortPath(String path) {
        if (path == null) {
            return "";
        }
        try {
            if (sessionDir != null) {
                Path base = sessionDir.toPath().toAbsolutePath().normalize();
                Path p = Path.of(path).toAbsolutePath().normalize();
                if (p.startsWith(base)) {
                    return base.relativize(p).toString();
                }
            }
        } catch (RuntimeException ignored) {
        }
        return path;
    }

    static String codeHtml(String code) {
        if (code == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String[] lines = code.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append("<br>");
            }
            sb.append(Markdown.codeLine(lines[i]));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ pieces

    /** One line per tool call: what Claude did, and whether it worked. */
    private final class ToolLine extends HtmlView {
        private static final long serialVersionUID = 1L;
        private final String name;
        private final transient Map<String, Object> toolInput;
        private String outcome = " <span class=\"dim\">…</span>";

        ToolLine(String name, Map<String, Object> toolInput) {
            super(ChatPanel.this::openLink);
            this.name = name;
            this.toolInput = toolInput;
            render();
        }

        static String displayName(String tool) {
            if (tool == null) {
                return "tool";
            }
            switch (tool) {
                case "Grep": return "Search";
                case "Glob": return "Find files";
                case "WebSearch": return "Web search";
                case "WebFetch": return "Fetch";
                case "Task": case "Agent": return "Agent";
                case "TodoWrite": return "To-dos";
                case "MultiEdit": return "Edit";
                default:
                    if (tool.startsWith("mcp__")) {
                        int i = tool.lastIndexOf("__");
                        return tool.substring(i + 2);
                    }
                    return tool;
            }
        }

        private String summary() {
            String path = Json.str(toolInput, "file_path");
            if (path == null) {
                path = Json.str(toolInput, "notebook_path");
            }
            switch (name) {
                case "Read": case "Edit": case "MultiEdit": case "Write": case "NotebookEdit":
                    return fileLink(path);
                case "Bash":
                    return "<code>" + Markdown.escape(firstLine(Json.str(toolInput, "command"), 140)) + "</code>";
                case "Grep":
                    return "<code>" + Markdown.escape(firstLine(Json.str(toolInput, "pattern"), 80)) + "</code>"
                            + (toolInput.get("path") != null ? " in " + Markdown.escape(shortPath(Json.str(toolInput, "path"))) : "");
                case "Glob":
                    return "<code>" + Markdown.escape(firstLine(Json.str(toolInput, "pattern"), 80)) + "</code>";
                case "WebSearch":
                    return Markdown.escape(firstLine(Json.str(toolInput, "query"), 120));
                case "WebFetch":
                    return Markdown.escape(firstLine(Json.str(toolInput, "url"), 120));
                case "Task": case "Agent":
                    return Markdown.escape(firstLine(Json.str(toolInput, "description"), 120));
                case "TodoWrite":
                    return todos();
                default:
                    return "";
            }
        }

        private String fileLink(String path) {
            if (path == null) {
                return "";
            }
            return "<a href=\"nbfile:" + Markdown.escape(path) + "\">" + Markdown.escape(shortPath(path)) + "</a>";
        }

        private String todos() {
            Object list = toolInput.get("todos");
            if (!(list instanceof List)) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (Object o : (List<?>) list) {
                Map<String, Object> t = Json.map(o);
                String st = Json.str(t, "status");
                String content = Markdown.escape(String.valueOf(t.get("content")));
                sb.append("<br>&nbsp;&nbsp;");
                if ("completed".equals(st)) {
                    sb.append("<span class=\"ok\">&#10003;</span> <span class=\"dim\">").append(content).append("</span>");
                } else if ("in_progress".equals(st)) {
                    sb.append("<span class=\"accent\">&#9656;</span> <b>").append(content).append("</b>");
                } else {
                    sb.append("<span class=\"dim\">&#9675;</span> ").append(content);
                }
            }
            return sb.toString();
        }

        void finish(String result, boolean isError) {
            if (isError) {
                outcome = "<br><span class=\"err\">" + Markdown.escape(firstLines(result, 3, 300)).replace("\n", "<br>") + "</span>";
            } else if ("Bash".equals(name) && result != null && !result.isBlank()) {
                String[] lines = result.strip().split("\n");
                StringBuilder sb = new StringBuilder("<div class=\"code\"><span class=\"dim\">");
                int shown = Math.min(lines.length, 6);
                for (int i = 0; i < shown; i++) {
                    if (i > 0) {
                        sb.append("<br>");
                    }
                    sb.append(Markdown.escape(lines[i].length() > 200 ? lines[i].substring(0, 200) + "…" : lines[i]));
                }
                if (lines.length > shown) {
                    sb.append("<br>… ").append(lines.length - shown).append(" more lines");
                }
                outcome = sb.append("</span></div>").toString();
            } else {
                outcome = " <span class=\"ok\">&#10003;</span>";
            }
            render();
        }

        private void render() {
            setBodyHtml("<span class=\"accent\">&#9679;</span> <b>" + Markdown.escape(displayName(name)) + "</b> "
                    + summary() + outcome);
        }
    }

    /** An inline question with buttons, e.g. permission to run a command. */
    private final class Card extends JPanel {
        private static final long serialVersionUID = 1L;
        private final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        boolean resolved;
        transient Runnable onCancel;

        Card(String title, String bodyHtml) {
            super(new BorderLayout(0, 6));
            setBackground(HtmlView.Theme.subtleBg());
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 3, 0, 0, HtmlView.Theme.accent()),
                    BorderFactory.createEmptyBorder(8, 10, 8, 8)));
            JLabel t = new JLabel(title);
            t.setFont(t.getFont().deriveFont(Font.BOLD));
            add(t, BorderLayout.NORTH);
            HtmlView body = new HtmlView(ChatPanel.this::openLink);
            body.setBodyHtml(bodyHtml);
            add(body, BorderLayout.CENTER);
            buttons.setOpaque(false);
            add(buttons, BorderLayout.SOUTH);
            openCards.add(this);
        }

        void button(String label, boolean primary, Runnable action) {
            JButton b = new JButton(label);
            if (primary) {
                b.setFont(b.getFont().deriveFont(Font.BOLD));
            }
            b.addActionListener(e -> {
                if (!resolved) {
                    action.run();
                }
            });
            buttons.add(b);
        }

        void resolve(String text, Color color) {
            if (resolved) {
                return;
            }
            resolved = true;
            onCancel = null;
            openCards.remove(this);
            remove(buttons);
            JLabel o = new JLabel(text);
            o.setForeground(color);
            add(o, BorderLayout.SOUTH);
            relayout();
        }
    }

    private static String firstLine(String s, int max) {
        if (s == null) {
            return "";
        }
        String l = s.strip();
        int nl = l.indexOf('\n');
        if (nl >= 0) {
            l = l.substring(0, nl) + " …";
        }
        return l.length() > max ? l.substring(0, max) + "…" : l;
    }

    private static String firstLines(String s, int lines, int max) {
        if (s == null) {
            return "";
        }
        String[] ls = s.strip().split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(lines, ls.length); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(ls[i]);
        }
        String r = sb.toString();
        return r.length() > max ? r.substring(0, max) + "…" : r;
    }

    /** Text area that shows a hint while empty. */
    private static final class HintTextArea extends JTextArea {
        private static final long serialVersionUID = 1L;
        private final String hint;

        HintTextArea(String hint) {
            this.hint = hint;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getText().isEmpty()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                Color c = HtmlView.Theme.dim();
                g2.setColor(c);
                g2.setFont(getFont());
                int y = getInsets().top + g2.getFontMetrics().getAscent();
                g2.drawString(hint, getInsets().left, y);
                g2.dispose();
            }
        }
    }
}
