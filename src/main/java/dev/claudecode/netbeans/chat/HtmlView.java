package dev.claudecode.netbeans.chat;

import dev.claudecode.netbeans.core.Markdown;
import java.awt.Color;
import java.awt.Font;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JEditorPane;
import javax.swing.UIManager;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.DefaultCaret;

/** Read-only, selectable HTML text that wraps to its container's width and follows the IDE theme. */
class HtmlView extends JEditorPane {

    private static final long serialVersionUID = 1L;

    HtmlView(Consumer<String> linkHandler) {
        setContentType("text/html");
        setEditable(false);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder());
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        Font f = UIManager.getFont("Label.font");
        if (f != null) {
            setFont(f.deriveFont(Font.PLAIN));
        }
        setForeground(Theme.fg());
        if (getCaret() instanceof DefaultCaret) {
            ((DefaultCaret) getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        }
        if (linkHandler != null) {
            addHyperlinkListener(e -> {
                if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                    String target = e.getURL() != null ? e.getURL().toString() : e.getDescription();
                    if (target != null) {
                        linkHandler.accept(target);
                    }
                }
            });
        }
    }

    void setMarkdown(String markdown) {
        setBodyHtml(Markdown.toHtml(markdown));
    }

    void setBodyHtml(String body) {
        setText("<html><head><style>" + Theme.css() + "</style></head><body>" + body + "</body></html>");
    }

    /** Colors and CSS derived from the current look and feel, so dark themes work. */
    static final class Theme {
        private Theme() {
        }

        static Color fg() {
            Color c = UIManager.getColor("Label.foreground");
            return c != null ? c : Color.BLACK;
        }

        static Color bg() {
            Color c = UIManager.getColor("Panel.background");
            return c != null ? c : Color.WHITE;
        }

        static boolean dark() {
            Color b = bg();
            return (b.getRed() * 299 + b.getGreen() * 587 + b.getBlue() * 114) / 1000 < 128;
        }

        static Color mix(Color a, Color b, double t) {
            return new Color(
                    (int) Math.round(a.getRed() * (1 - t) + b.getRed() * t),
                    (int) Math.round(a.getGreen() * (1 - t) + b.getGreen() * t),
                    (int) Math.round(a.getBlue() * (1 - t) + b.getBlue() * t));
        }

        static Color dim() {
            return mix(fg(), bg(), 0.45);
        }

        static Color subtleBg() {
            return mix(bg(), fg(), dark() ? 0.08 : 0.05);
        }

        static Color codeBg() {
            return mix(bg(), fg(), dark() ? 0.12 : 0.07);
        }

        static Color border() {
            return mix(bg(), fg(), 0.22);
        }

        static Color accent() {
            return dark() ? new Color(0xD9, 0x8A, 0x6C) : new Color(0xC1, 0x5F, 0x3C);
        }

        static Color error() {
            return dark() ? new Color(0xF2, 0x7A, 0x7A) : new Color(0xB0, 0x2A, 0x2A);
        }

        static Color ok() {
            return dark() ? new Color(0x7F, 0xC8, 0x8A) : new Color(0x2E, 0x7D, 0x32);
        }

        static Color link() {
            return dark() ? new Color(0x8A, 0xB4, 0xF8) : new Color(0x1A, 0x5F, 0xB4);
        }

        static String hex(Color c) {
            return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
        }

        static String css() {
            return "body{color:" + hex(fg()) + ";margin:0;padding:0}"
                    + "p{margin-top:0;margin-bottom:6px}"
                    + "h2,h3,h4,h5{margin-top:4px;margin-bottom:4px}"
                    + "h2{font-size:120%}h3{font-size:110%}h4,h5{font-size:100%}"
                    + "ul,ol{margin-top:0;margin-bottom:6px;margin-left-ltr:18;margin-right-rtl:18}"
                    + "li{margin-bottom:2px}"
                    + "code{font-family:Monospaced;background:" + hex(codeBg()) + "}"
                    + ".code{font-family:Monospaced;background:" + hex(codeBg()) + ";padding:6px;margin-bottom:6px}"
                    + ".quote{color:" + hex(dim()) + ";margin-left:8px;margin-bottom:6px}"
                    + ".dim{color:" + hex(dim()) + "}"
                    + ".err{color:" + hex(error()) + "}"
                    + ".ok{color:" + hex(ok()) + "}"
                    + ".accent{color:" + hex(accent()) + "}"
                    + "a{color:" + hex(link()) + "}"
                    + "table.tbl{margin-bottom:6px}"
                    + "th,td{border:1px solid " + hex(border()) + ";padding:3px}"
                    + "hr{color:" + hex(border()) + "}";
        }
    }
}
