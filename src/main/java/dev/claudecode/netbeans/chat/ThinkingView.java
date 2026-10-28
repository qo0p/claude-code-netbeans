package dev.claudecode.netbeans.chat;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * One thinking step in the transcript: "Thinking… 3s" while it happens,
 * "Thought for 3s" afterwards. When the CLI provides the thinking text,
 * clicking the line expands it.
 */
final class ThinkingView extends JPanel {

    private static final long serialVersionUID = 1L;

    private final JLabel header = new JLabel();
    private final HtmlView body = new HtmlView(null);
    private final StringBuilder thoughts = new StringBuilder();
    private final transient Runnable relayout;
    private final long start = System.currentTimeMillis();
    private long end;
    private int tokens;
    private boolean expanded;

    ThinkingView(Runnable relayout) {
        super(new BorderLayout(0, 4));
        this.relayout = relayout;
        setOpaque(false);
        header.setForeground(HtmlView.Theme.dim());
        body.setVisible(false);
        body.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 2, 0, 0, HtmlView.Theme.border()),
                BorderFactory.createEmptyBorder(0, 8, 0, 0)));
        add(header, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (thoughts.length() > 0) {
                    expanded = !expanded;
                    renderBody();
                    refresh();
                    relayout.run();
                }
            }
        });
        refresh();
    }

    boolean isFinished() {
        return end != 0;
    }

    void addText(String text) {
        boolean first = thoughts.length() == 0;
        thoughts.append(text);
        if (first) {
            header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            header.setToolTipText("Show or hide Claude's thinking");
        }
        if (expanded) {
            renderBody();
            relayout.run();
        }
        refresh();
    }

    void setTokens(int tokens) {
        this.tokens = tokens;
        refresh();
    }

    void finish() {
        if (end == 0) {
            end = System.currentTimeMillis();
            refresh();
        }
    }

    /** Updates the running time while thinking is in progress. */
    void tick() {
        if (end == 0) {
            refresh();
        }
    }

    private void renderBody() {
        body.setVisible(expanded);
        if (expanded) {
            body.setBodyHtml("<div class=\"dim\">" + dev.claudecode.netbeans.core.Markdown.toHtml(thoughts.toString()) + "</div>");
        }
    }

    private void refresh() {
        long ms = (end == 0 ? System.currentTimeMillis() : end) - start;
        StringBuilder sb = new StringBuilder("<html>");
        if (thoughts.length() > 0) {
            sb.append(expanded ? "&#9662; " : "&#9656; ");
        } else {
            sb.append("<span style='color:").append(HtmlView.Theme.hex(HtmlView.Theme.accent())).append("'>&#10043;</span> ");
        }
        sb.append(end == 0 ? "<i>Thinking…</i> " : "Thought for ");
        sb.append(ActivityRow.duration(Math.max(ms, 1000)));
        if (tokens > 0) {
            sb.append(" · ~").append(tokens).append(" tokens");
        }
        sb.append("</html>");
        header.setText(sb.toString());
    }
}
