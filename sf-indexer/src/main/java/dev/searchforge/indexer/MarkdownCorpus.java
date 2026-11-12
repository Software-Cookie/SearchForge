package dev.searchforge.indexer;

import dev.searchforge.core.page.PageRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

// Turns a folder of Markdown files into one page per section (# to #### heading).
// Used to build an offline demo index (e.g. from docs/guide/parts) with no crawling at all.
public final class MarkdownCorpus {

    private static final Pattern HEADING = Pattern.compile("^(#{1,4})\\s+(.+)$");
    private static final Pattern SUB_HEADING = Pattern.compile("^#{5,6}\\s+(.+)$");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^]]*)]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^]]*)]\\([^)]*\\)");
    private static final Pattern TAG = Pattern.compile("<[^>]{1,200}>");
    private static final Pattern MARKUP = Pattern.compile("[`*_>|#~]+");
    // ASCII-art diagrams and table rules: "+-----+", "=====", "---->", box-drawing chars.
    private static final Pattern DRAWING = Pattern.compile("[-+=.]{3,}>?|[\\u2500-\\u257F]+");
    private static final int MIN_BODY_CHARS = 40;

    private MarkdownCorpus() {
    }

    public static List<PageRecord> read(Path dir, String baseUrl) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList();
        }
        List<PageRecord> pages = new ArrayList<>();
        for (Path f : files) {
            pages.addAll(sections(f, baseUrl));
        }
        return pages;
    }

    static List<PageRecord> sections(Path file, String baseUrl) throws IOException {
        List<PageRecord> out = new ArrayList<>();
        String name = file.getFileName().toString();
        String title = null;
        StringBuilder body = new StringBuilder();
        StringBuilder subs = new StringBuilder();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            var h = HEADING.matcher(line);
            if (h.matches()) {
                emit(out, baseUrl + name, title, subs, body);
                title = clean(h.group(2));
                body.setLength(0);
                subs.setLength(0);
                continue;
            }
            var sub = SUB_HEADING.matcher(line);
            if (sub.matches()) {
                subs.append(clean(sub.group(1))).append(". ");
            }
            if (!line.strip().startsWith("```")) {
                body.append(clean(line)).append(' ');
            }
        }
        emit(out, baseUrl + name, title, subs, body);
        return out;
    }

    private static void emit(List<PageRecord> out, String fileUrl, String title,
                             StringBuilder subs, StringBuilder body) {
        String text = body.toString().replaceAll("\\s+", " ").strip();
        if (title == null || text.length() < MIN_BODY_CHARS) {
            return;
        }
        String url = fileUrl + "#" + slug(title);
        if (out.stream().anyMatch(p -> p.url().equals(url))) {
            return;
        }
        out.add(new PageRecord(url, title, subs.toString().strip(), text, List.of(), 0L));
    }

    static String clean(String markdown) {
        String s = IMAGE.matcher(markdown).replaceAll("$1");
        s = LINK.matcher(s).replaceAll("$1");
        s = TAG.matcher(s).replaceAll(" ");
        s = DRAWING.matcher(s).replaceAll(" ");
        s = MARKUP.matcher(s).replaceAll(" ");
        return s.replace("$", "").replace("\\", "").strip();
    }

    // GitHub-style heading anchor.
    static String slug(String heading) {
        return heading.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{Nd}\\s-]", "")
                .strip()
                .replaceAll("\\s", "-");
    }
}
