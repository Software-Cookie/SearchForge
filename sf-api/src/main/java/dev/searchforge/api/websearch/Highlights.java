package dev.searchforge.api.websearch;

import dev.searchforge.query.Snippets.Segment;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// Turns upstream snippets into plain-text segments. HTML is parsed, never passed on: text
// inside strong, b, em, mark or Wikipedia "searchmatch" spans becomes a highlighted segment.
public final class Highlights {

    static final int MAX_CHARS = 300;

    private Highlights() {
    }

    public static List<Segment> fromHtml(String html) {
        List<Segment> out = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return out;
        }
        walk(Jsoup.parseBodyFragment(html).body(), false, out);
        return trim(merge(out));
    }

    // Plain text: highlight whole words (plus short endings, "house" -> "houses").
    public static List<Segment> fromPlain(String text, String query) {
        List<Segment> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String clean = text.strip().replaceAll("\\s+", " ");
        Set<String> words = Arrays.stream(query.toLowerCase(Locale.ROOT)
                        .split("[^\\p{L}\\p{N}]+"))
                .filter(w -> w.length() > 1)
                .collect(Collectors.toSet());
        if (words.isEmpty()) {
            out.add(new Segment(clean, false));
            return trim(out);
        }
        String alternation = words.stream().map(Pattern::quote).collect(Collectors.joining("|"));
        Matcher m = Pattern.compile("(?iu)\\b(?:" + alternation + ")\\w{0,3}\\b").matcher(clean);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                out.add(new Segment(clean.substring(last, m.start()), false));
            }
            out.add(new Segment(m.group(), true));
            last = m.end();
        }
        if (last < clean.length()) {
            out.add(new Segment(clean.substring(last), false));
        }
        return trim(out);
    }

    // Visible text of an HTML fragment (titles from some sources contain markup).
    public static String plain(String html) {
        return html == null ? "" : Jsoup.parseBodyFragment(html).text();
    }

    private static void walk(Node node, boolean hit, List<Segment> out) {
        for (Node child : node.childNodes()) {
            if (child instanceof TextNode t) {
                if (!t.text().isEmpty()) {
                    out.add(new Segment(t.text(), hit));
                }
            } else if (child instanceof Element e) {
                boolean mark = switch (e.normalName()) {
                    case "strong", "b", "em", "mark" -> true;
                    case "span" -> e.hasClass("searchmatch");
                    default -> false;
                };
                walk(e, hit || mark, out);
            }
        }
    }

    private static List<Segment> merge(List<Segment> in) {
        List<Segment> out = new ArrayList<>();
        for (Segment s : in) {
            if (!out.isEmpty() && out.getLast().hit() == s.hit()) {
                out.set(out.size() - 1, new Segment(out.getLast().text() + s.text(), s.hit()));
            } else {
                out.add(s);
            }
        }
        return out;
    }

    private static List<Segment> trim(List<Segment> in) {
        List<Segment> out = new ArrayList<>();
        int used = 0;
        for (Segment s : in) {
            String text = s.text().replaceAll("\\s+", " ");
            if (used + text.length() > MAX_CHARS) {
                int cut = text.lastIndexOf(' ', Math.max(0, MAX_CHARS - used));
                out.add(new Segment((cut > 0 ? text.substring(0, cut) : "") + " …", false));
                return out;
            }
            out.add(new Segment(text, s.hit()));
            used += text.length();
        }
        return out;
    }
}
