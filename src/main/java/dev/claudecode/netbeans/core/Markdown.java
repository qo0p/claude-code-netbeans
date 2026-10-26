package dev.claudecode.netbeans.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts the Markdown Claude writes into the HTML 3.2 subset that Swing's
 * HTML renderer understands. Code blocks wrap instead of scrolling.
 */
public final class Markdown {

    private static final Pattern ORDERED = Pattern.compile("^\\s*(\\d+)[.)]\\s+(.*)$");
    private static final Pattern UNORDERED = Pattern.compile("^\\s*[-*+]\\s+(.*)$");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern RULE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__");
    private static final Pattern ITALIC = Pattern.compile("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])|(?<![\\w_])_(?!\\s)(.+?)(?<!\\s)_(?![\\w_])");

    private Markdown() {
    }

    public static String toHtml(String md) {
        if (md == null) {
            return "";
        }
        String[] lines = md.replace("\r\n", "\n").split("\n", -1);
        StringBuilder out = new StringBuilder();
        StringBuilder para = new StringBuilder();
        String list = null; // "ul" or "ol"
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                flush(out, para);
                list = closeList(out, list);
                String fence = trimmed.substring(0, 3);
                StringBuilder code = new StringBuilder();
                i++;
                boolean first = true;
                while (i < lines.length && !lines[i].trim().startsWith(fence)) {
                    if (!first) {
                        code.append("<br>");
                    }
                    first = false;
                    code.append(codeLine(lines[i]));
                    i++;
                }
                i++; // closing fence (or end of a still-streaming block)
                out.append("<div class=\"code\">").append(code.length() == 0 ? "&nbsp;" : code).append("</div>");
                continue;
            }
            if (trimmed.isEmpty()) {
                flush(out, para);
                list = closeList(out, list);
                i++;
                continue;
            }
            Matcher h = HEADING.matcher(trimmed);
            if (h.matches()) {
                flush(out, para);
                list = closeList(out, list);
                int level = Math.min(h.group(1).length() + 1, 5);
                out.append("<h").append(level).append('>').append(inline(h.group(2)))
                        .append("</h").append(level).append('>');
                i++;
                continue;
            }
            if (RULE.matcher(trimmed).matches()) {
                flush(out, para);
                list = closeList(out, list);
                out.append("<hr>");
                i++;
                continue;
            }
            if (trimmed.startsWith("|") && i + 1 < lines.length && TABLE_SEPARATOR.matcher(lines[i + 1]).matches()) {
                flush(out, para);
                list = closeList(out, list);
                out.append("<table class=\"tbl\" cellspacing=\"0\" cellpadding=\"3\"><tr>");
                for (String cell : cells(trimmed)) {
                    out.append("<th align=\"left\">").append(inline(cell)).append("</th>");
                }
                out.append("</tr>");
                i += 2;
                while (i < lines.length && lines[i].trim().startsWith("|")) {
                    out.append("<tr>");
                    for (String cell : cells(lines[i].trim())) {
                        out.append("<td>").append(inline(cell)).append("</td>");
                    }
                    out.append("</tr>");
                    i++;
                }
                out.append("</table>");
                continue;
            }
            if (trimmed.startsWith(">")) {
                flush(out, para);
                list = closeList(out, list);
                StringBuilder quote = new StringBuilder();
                while (i < lines.length && lines[i].trim().startsWith(">")) {
                    if (quote.length() > 0) {
                        quote.append(' ');
                    }
                    quote.append(lines[i].trim().substring(1).trim());
                    i++;
                }
                out.append("<div class=\"quote\">").append(inline(quote.toString())).append("</div>");
                continue;
            }
            Matcher ul = UNORDERED.matcher(line);
            Matcher ol = ORDERED.matcher(line);
            if (ul.matches() || ol.matches()) {
                flush(out, para);
                String kind = ul.matches() ? "ul" : "ol";
                if (!kind.equals(list)) {
                    list = closeList(out, list);
                    out.append('<').append(kind).append('>');
                    list = kind;
                }
                String item = ul.matches() ? ul.group(1) : ol.group(2);
                StringBuilder text = new StringBuilder(item);
                i++;
                // continuation lines indented under the item
                while (i < lines.length && !lines[i].trim().isEmpty() && lines[i].startsWith("  ")
                        && !UNORDERED.matcher(lines[i]).matches() && !ORDERED.matcher(lines[i]).matches()) {
                    text.append(' ').append(lines[i].trim());
                    i++;
                }
                out.append("<li>").append(inline(text.toString())).append("</li>");
                continue;
            }
            list = closeList(out, list);
            if (para.length() > 0) {
                para.append("<br>");
            }
            para.append(inline(trimmed));
            i++;
        }
        flush(out, para);
        closeList(out, list);
        return out.toString();
    }

    private static void flush(StringBuilder out, StringBuilder para) {
        if (para.length() > 0) {
            out.append("<p>").append(para).append("</p>");
            para.setLength(0);
        }
    }

    private static String closeList(StringBuilder out, String list) {
        if (list != null) {
            out.append("</").append(list).append('>');
        }
        return null;
    }

    private static String[] cells(String row) {
        String r = row.trim();
        if (r.startsWith("|")) {
            r = r.substring(1);
        }
        if (r.endsWith("|")) {
            r = r.substring(0, r.length() - 1);
        }
        String[] c = r.split("\\|", -1);
        for (int k = 0; k < c.length; k++) {
            c[k] = c[k].trim();
        }
        return c;
    }

    /** Escapes a code line, keeping indentation but letting long lines wrap. */
    public static String codeLine(String line) {
        String expanded = line.replace("\t", "    ");
        int lead = 0;
        while (lead < expanded.length() && expanded.charAt(lead) == ' ') {
            lead++;
        }
        return "&nbsp;".repeat(lead) + escape(expanded.substring(lead));
    }

    /** Inline formatting; text inside `code spans` is left alone. */
    static String inline(String s) {
        StringBuilder out = new StringBuilder();
        String[] parts = s.split("`", -1);
        for (int k = 0; k < parts.length; k++) {
            boolean code = k % 2 == 1 && k < parts.length - 1;
            if (code) {
                out.append("<code>").append(escape(parts[k])).append("</code>");
            } else {
                String text = k % 2 == 1 ? "`" + parts[k] : parts[k]; // unmatched backtick
                out.append(formatText(escape(text)));
            }
        }
        return out.toString();
    }

    private static String formatText(String escaped) {
        String t = LINK.matcher(escaped).replaceAll(m ->
                Matcher.quoteReplacement("<a href=\"" + m.group(2) + "\">" + m.group(1) + "</a>"));
        t = BOLD.matcher(t).replaceAll(m ->
                Matcher.quoteReplacement("<b>" + (m.group(1) != null ? m.group(1) : m.group(2)) + "</b>"));
        t = ITALIC.matcher(t).replaceAll(m ->
                Matcher.quoteReplacement("<i>" + (m.group(1) != null ? m.group(1) : m.group(2)) + "</i>"));
        return t;
    }

    public static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&': sb.append("&amp;"); break;
                case '"': sb.append("&quot;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
