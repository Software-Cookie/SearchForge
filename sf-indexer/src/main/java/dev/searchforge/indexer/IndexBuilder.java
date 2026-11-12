package dev.searchforge.indexer;

import dev.searchforge.core.graph.PageRank;
import dev.searchforge.core.page.PageRecord;
import dev.searchforge.indexer.analysis.Analyzer;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

// Builds one immutable index directory. Postings are buffered in memory up to a budget,
// then spilled as sorted runs and k-way merged at the end (see RunMerger).
public final class IndexBuilder implements Closeable {

    public record Stats(int docs, long terms, long postingsBytes, long rawTextBytes,
                        int runs, long buildMillis) {
        public double compressionRatio() {
            return postingsBytes == 0 ? 0 : (double) rawTextBytes / postingsBytes;
        }
    }

    private static final int TERM_OVERHEAD_BYTES = 96;
    private static final int MAX_VOCAB_ENTRIES = 2_000_000;
    private static final int VOCAB_KEEP = 100_000;

    private final Path outDir;
    private final long memoryBudgetBytes;
    private final RunMerger runs;
    private final DataOutputStream docsOut;
    private final List<Long> docOffsets = new ArrayList<>();
    private final IntList norms = new IntList(1024);
    private final List<int[]> links = new ArrayList<>();
    private final Map<String, Integer> vocab = new HashMap<>();
    private final long startMillis = System.currentTimeMillis();
    private Map<String, IntList> buffer = new HashMap<>();
    private long bufferedBytes;
    private long docsBytes;
    private long rawTextBytes;
    private final long[] fieldTotals = new long[3];

    public IndexBuilder(Path outDir, long memoryBudgetBytes) throws IOException {
        this.outDir = Files.createDirectories(outDir);
        this.memoryBudgetBytes = memoryBudgetBytes;
        this.runs = new RunMerger(outDir.resolve("tmp"));
        OutputStream os = Files.newOutputStream(outDir.resolve(IndexFiles.DOCS));
        this.docsOut = new DataOutputStream(new BufferedOutputStream(os, 1 << 16));
    }

    public int docCount() {
        return docOffsets.size();
    }

    // Adds a document; outLinks are docIds this page links to (for PageRank).
    public int add(PageRecord page, int[] outLinks) throws IOException {
        int docId = docOffsets.size();
        Map<String, int[]> tf = new LinkedHashMap<>();
        Map<String, IntList> positions = new HashMap<>();
        int next = 0;
        String[] fields = {page.title(), page.headings(), page.body()};
        for (int f = 0; f < 3; f++) {
            List<Analyzer.Token> tokens = Analyzer.tokenize(fields[f]);
            int termsInField = 0;
            for (Analyzer.Token t : tokens) {
                String term = Analyzer.termOf(t.surface());
                if (term == null) {
                    continue;
                }
                tf.computeIfAbsent(term, k -> new int[3])[f]++;
                positions.computeIfAbsent(term, k -> new IntList(4)).add(next + t.position());
                termsInField++;
            }
            norms.add(termsInField);
            fieldTotals[f] += termsInField;
            next += tokens.size() + IndexFiles.FIELD_GAP;
            rawTextBytes += fields[f].getBytes(StandardCharsets.UTF_8).length;
        }
        norms.add(0); // pageRank placeholder, filled in finish()
        for (Map.Entry<String, int[]> e : tf.entrySet()) {
            IntList list = buffer.get(e.getKey());
            if (list == null) {
                list = new IntList(8);
                buffer.put(e.getKey(), list);
                bufferedBytes += TERM_OVERHEAD_BYTES;
            }
            int[] counts = e.getValue();
            IntList pos = positions.get(e.getKey());
            list.add(docId);
            list.add(counts[0]);
            list.add(counts[1]);
            list.add(counts[2]);
            list.addAll(pos);
            bufferedBytes += 4L * (4 + pos.size());
        }
        countVocabulary(page);
        writeStoredDoc(page);
        links.add(outLinks == null ? new int[0] : outLinks);
        if (bufferedBytes >= memoryBudgetBytes) {
            flushBuffer();
        }
        return docId;
    }

    private void countVocabulary(PageRecord page) {
        Set<String> words = new HashSet<>();
        for (String text : new String[] {page.title(), page.headings(), page.body()}) {
            for (Analyzer.Token t : Analyzer.tokenize(text)) {
                String w = t.surface();
                if (w.length() >= 2 && w.length() <= 30 && !Analyzer.isStopWord(w)
                        && Character.isLetter(w.codePointAt(0))) {
                    words.add(w);
                }
            }
        }
        for (String w : words) {
            if (vocab.size() < MAX_VOCAB_ENTRIES || vocab.containsKey(w)) {
                vocab.merge(w, 1, Integer::sum);
            }
        }
    }

    private void writeStoredDoc(PageRecord page) throws IOException {
        docOffsets.add(docsBytes);
        String text = page.body().isEmpty() ? page.headings() : page.body();
        if (text.length() > IndexFiles.MAX_STORED_CHARS) {
            text = text.substring(0, IndexFiles.MAX_STORED_CHARS);
        }
        for (String s : new String[] {page.url(), page.title(), text}) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            docsOut.writeInt(b.length);
            docsOut.write(b);
            docsBytes += 4 + b.length;
        }
    }

    private void flushBuffer() throws IOException {
        if (!buffer.isEmpty()) {
            runs.spill(buffer);
        }
        buffer = new HashMap<>();
        bufferedBytes = 0;
    }

    public Stats finish() throws IOException {
        flushBuffer();
        int runCount = runs.runCount();
        long[] merged = runs.merge(outDir.resolve(IndexFiles.POSTINGS),
                outDir.resolve(IndexFiles.DICT));
        docsOut.close();
        int n = docOffsets.size();
        PageRank.Result pr = PageRank.compute(links.toArray(new int[0][]), 0.85, 1e-9, 100);
        writeDocIndex(n);
        writeNorms(n, pr.ranks());
        writeVocab(n);
        Stats stats = new Stats(n, merged[0], merged[1], rawTextBytes, runCount,
                System.currentTimeMillis() - startMillis);
        writeMeta(stats, pr);
        return stats;
    }

    private void writeDocIndex(int n) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(outDir.resolve(IndexFiles.DOCS_INDEX))))) {
            out.writeInt(n);
            for (long off : docOffsets) {
                out.writeLong(off);
            }
        }
    }

    private void writeNorms(int n, double[] ranks) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(outDir.resolve(IndexFiles.NORMS))))) {
            out.writeInt(n);
            for (int d = 0; d < n; d++) {
                out.writeInt(norms.get(d * 4));
                out.writeInt(norms.get(d * 4 + 1));
                out.writeInt(norms.get(d * 4 + 2));
                out.writeFloat((float) ranks[d]);
            }
        }
    }

    private void writeVocab(int docs) throws IOException {
        int minDf = docs >= 1_000 ? 2 : 1;
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(vocab.entrySet());
        entries.removeIf(e -> e.getValue() < minDf);
        entries.sort(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()));
        try (BufferedWriter w = Files.newBufferedWriter(outDir.resolve(IndexFiles.VOCAB))) {
            for (int i = 0; i < Math.min(VOCAB_KEEP, entries.size()); i++) {
                w.write(entries.get(i).getKey() + "\t" + entries.get(i).getValue() + "\n");
            }
        }
    }

    private void writeMeta(Stats s, PageRank.Result pr) throws IOException {
        Properties p = new Properties();
        int n = Math.max(1, s.docs());
        p.setProperty("formatVersion", String.valueOf(IndexFiles.FORMAT_VERSION));
        p.setProperty("docCount", String.valueOf(s.docs()));
        p.setProperty("termCount", String.valueOf(s.terms()));
        p.setProperty("avgTitleLength", String.valueOf((double) fieldTotals[0] / n));
        p.setProperty("avgHeadingsLength", String.valueOf((double) fieldTotals[1] / n));
        p.setProperty("avgBodyLength", String.valueOf((double) fieldTotals[2] / n));
        p.setProperty("rawTextBytes", String.valueOf(s.rawTextBytes()));
        p.setProperty("postingsBytes", String.valueOf(s.postingsBytes()));
        p.setProperty("runs", String.valueOf(s.runs()));
        p.setProperty("buildMillis", String.valueOf(s.buildMillis()));
        p.setProperty("pageRankIterations", String.valueOf(pr.iterations()));
        p.setProperty("createdAt", Instant.now().toString());
        try (OutputStream out = Files.newOutputStream(outDir.resolve(IndexFiles.META))) {
            p.store(out, "SearchForge index");
        }
    }

    @Override
    public void close() throws IOException {
        docsOut.close();
    }
}
