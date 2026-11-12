package dev.searchforge.indexer;

import dev.searchforge.core.page.PageRecord;
import dev.searchforge.core.page.PageStore;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

// Command line:
//   --in <crawlDir> --out <indexDir> [--memory-mb N]
//   --markdown <dir> --base-url <url> --out <indexDir>     (offline demo index)
// The output directory must not exist yet or be empty: an index is never modified in place.
public final class IndexerMain {

    private IndexerMain() {
    }

    public static void main(String[] args) throws Exception {
        System.exit(run(args, System.out));
    }

    static int run(String[] args, PrintStream out) throws IOException {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                out.println(usage());
                return 2;
            }
            opts.put(args[i].substring(2), args[i + 1]);
        }
        boolean hasInput = opts.containsKey("in") ^ opts.containsKey("markdown");
        if (args.length % 2 != 0 || !hasInput || !opts.containsKey("out")) {
            out.println(usage());
            return 2;
        }
        Path outDir = Path.of(opts.get("out"));
        if (Files.isDirectory(outDir)) {
            try (Stream<Path> s = Files.list(outDir)) {
                if (s.findAny().isPresent()) {
                    out.println("Error: " + outDir + " is not empty. Use a new directory.");
                    return 2;
                }
            }
        }
        long budget = Long.parseLong(opts.getOrDefault("memory-mb", "256")) * 1024 * 1024;
        IndexBuilder.Stats stats;
        if (opts.containsKey("markdown")) {
            List<PageRecord> pages = MarkdownCorpus.read(Path.of(opts.get("markdown")),
                    opts.getOrDefault("base-url", ""));
            stats = build(pages, outDir, budget);
        } else {
            stats = buildFromCrawl(Path.of(opts.get("in")), outDir, budget);
        }
        out.printf("docs=%d terms=%d runs=%d postingsBytes=%d rawTextBytes=%d "
                        + "compression=%.2fx seconds=%.1f%n", stats.docs(), stats.terms(),
                stats.runs(), stats.postingsBytes(), stats.rawTextBytes(),
                stats.compressionRatio(), stats.buildMillis() / 1000.0);
        return 0;
    }

    static IndexBuilder.Stats build(List<PageRecord> pages, Path outDir, long budget)
            throws IOException {
        Map<String, Integer> ids = new HashMap<>();
        for (PageRecord p : pages) {
            ids.putIfAbsent(p.url(), ids.size());
        }
        try (IndexBuilder builder = new IndexBuilder(outDir, budget)) {
            for (PageRecord p : pages) {
                addIfNew(builder, p, ids);
            }
            return builder.finish();
        }
    }

    // Two streaming passes over the page store: (1) assign docIds, (2) index with link graph.
    static IndexBuilder.Stats buildFromCrawl(Path crawlDir, Path outDir, long budget)
            throws IOException {
        Map<String, Integer> ids = new HashMap<>();
        PageStore.readAll(crawlDir, p -> ids.putIfAbsent(p.url(), ids.size()));
        if (ids.isEmpty()) {
            throw new IOException("No pages found in " + crawlDir);
        }
        try (IndexBuilder builder = new IndexBuilder(outDir, budget)) {
            IOException[] failure = new IOException[1];
            PageStore.readAll(crawlDir, p -> {
                if (failure[0] == null) {
                    try {
                        addIfNew(builder, p, ids);
                    } catch (IOException e) {
                        failure[0] = e;
                    }
                }
            });
            if (failure[0] != null) {
                throw failure[0];
            }
            return builder.finish();
        }
    }

    private static void addIfNew(IndexBuilder builder, PageRecord p, Map<String, Integer> ids)
            throws IOException {
        if (ids.get(p.url()) != builder.docCount()) {
            return; // a repeated URL: keep the first copy only
        }
        int[] links = p.outlinks().stream()
                .map(ids::get)
                .filter(id -> id != null)
                .mapToInt(Integer::intValue)
                .distinct()
                .toArray();
        builder.add(p, links);
    }

    static String usage() {
        return "Usage: --in <crawlDir> --out <indexDir> [--memory-mb N]\n"
                + "   or: --markdown <dir> --base-url <url> --out <indexDir>";
    }
}
