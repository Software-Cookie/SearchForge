package dev.searchforge.indexer;

import dev.searchforge.core.codec.VByte;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

// Read-only view of an index directory. The dictionary and per-document norms live on the
// heap (small); postings and stored documents are memory-mapped, so the OS page cache, not
// the JVM heap, holds them. That is what lets a 50k-page index run in a 512 MB container.
// Thread-safe: every read uses an independent slice of the shared buffers.
public final class Index {

    public record StoredDoc(int id, String url, String title, String text) {
    }

    public record VocabEntry(String word, int docFreq) {
    }

    private final Properties meta;
    private final String[] terms;
    private final int[] dfs;
    private final long[] offsets;
    private final int[] lengths;
    private final int[] norms;
    private final float[] pageRanks;
    private final long[] docOffsets;
    private final ByteBuffer postings;
    private final ByteBuffer docs;
    private final List<VocabEntry> vocabulary;
    private final double[] avgLengths;

    private Index(Path dir, boolean memoryMap) throws IOException {
        meta = new Properties();
        try (InputStream in = Files.newInputStream(dir.resolve(IndexFiles.META))) {
            meta.load(in);
        }
        int version = Integer.parseInt(meta.getProperty("formatVersion", "0"));
        if (version != IndexFiles.FORMAT_VERSION) {
            throw new IOException("Unsupported index format " + version + " in " + dir);
        }
        try (DataInputStream in = input(dir.resolve(IndexFiles.DICT))) {
            int n = in.readInt();
            terms = new String[n];
            dfs = new int[n];
            offsets = new long[n];
            lengths = new int[n];
            for (int i = 0; i < n; i++) {
                terms[i] = in.readUTF();
                dfs[i] = VByte.read(in);
                offsets[i] = in.readLong();
                lengths[i] = in.readInt();
            }
        }
        try (DataInputStream in = input(dir.resolve(IndexFiles.NORMS))) {
            int n = in.readInt();
            norms = new int[n * 3];
            pageRanks = new float[n];
            for (int d = 0; d < n; d++) {
                norms[d * 3] = in.readInt();
                norms[d * 3 + 1] = in.readInt();
                norms[d * 3 + 2] = in.readInt();
                pageRanks[d] = in.readFloat();
            }
        }
        try (DataInputStream in = input(dir.resolve(IndexFiles.DOCS_INDEX))) {
            int n = in.readInt();
            docOffsets = new long[n + 1];
            for (int d = 0; d < n; d++) {
                docOffsets[d] = in.readLong();
            }
        }
        postings = load(dir.resolve(IndexFiles.POSTINGS), memoryMap);
        docs = load(dir.resolve(IndexFiles.DOCS), memoryMap);
        docOffsets[docOffsets.length - 1] = docs.capacity();
        vocabulary = readVocab(dir.resolve(IndexFiles.VOCAB));
        avgLengths = new double[] {avg("avgTitleLength"), avg("avgHeadingsLength"),
            avg("avgBodyLength")};
    }

    // memoryMap=false reads files onto the heap (handy for tests on Windows, where a
    // mapped file cannot be deleted until the mapping is garbage collected).
    public static Index open(Path dir, boolean memoryMap) throws IOException {
        return new Index(dir, memoryMap);
    }

    public static Index open(Path dir) throws IOException {
        return open(dir, true);
    }

    public int docCount() {
        return pageRanks.length;
    }

    public int termCount() {
        return terms.length;
    }

    public Properties meta() {
        return meta;
    }

    public double avgFieldLength(int field) {
        return avgLengths[field];
    }

    public int fieldLength(int doc, int field) {
        return norms[doc * 3 + field];
    }

    public float pageRank(int doc) {
        return pageRanks[doc];
    }

    public int docFreq(String term) {
        int i = Arrays.binarySearch(terms, term);
        return i < 0 ? 0 : dfs[i];
    }

    // Postings for term, or null if the term does not occur.
    public PostingList postings(String term) {
        int i = Arrays.binarySearch(terms, term);
        if (i < 0) {
            return null;
        }
        return new PostingList(postings.slice(Math.toIntExact(offsets[i]), lengths[i]));
    }

    public StoredDoc doc(int id) {
        if (id < 0 || id >= docCount()) {
            throw new IllegalArgumentException("No document " + id);
        }
        int from = Math.toIntExact(docOffsets[id]);
        ByteBuffer b = docs.slice(from, Math.toIntExact(docOffsets[id + 1] - from));
        return new StoredDoc(id, readString(b), readString(b), readString(b));
    }

    public List<VocabEntry> vocabulary() {
        return vocabulary;
    }

    private double avg(String key) {
        return Math.max(1.0, Double.parseDouble(meta.getProperty(key, "1")));
    }

    private static String readString(ByteBuffer b) {
        int len = b.getInt();
        byte[] bytes = new byte[len];
        b.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static DataInputStream input(Path file) throws IOException {
        return new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16));
    }

    private static ByteBuffer load(Path file, boolean memoryMap) throws IOException {
        long size = Files.size(file);
        if (size > Integer.MAX_VALUE) {
            throw new IOException(file + " is larger than 2 GB; split the index");
        }
        if (!memoryMap) {
            return ByteBuffer.wrap(Files.readAllBytes(file)).asReadOnlyBuffer();
        }
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            return ch.map(FileChannel.MapMode.READ_ONLY, 0, size);
        }
    }

    private static List<VocabEntry> readVocab(Path file) throws IOException {
        List<VocabEntry> out = new ArrayList<>();
        if (!Files.exists(file)) {
            return out;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int tab = line.indexOf('\t');
            if (tab > 0) {
                out.add(new VocabEntry(line.substring(0, tab),
                        Integer.parseInt(line.substring(tab + 1).strip())));
            }
        }
        return List.copyOf(out);
    }
}
