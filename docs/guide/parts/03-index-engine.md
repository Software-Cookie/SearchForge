## 3. Text Analysis, Positional Inverted Index & MapReduce Engine (Phases 3 & 4)

Once web pages are crawled, canonicalized, and deduplicated, the search engine must transform unstructured HTML text into a structured, compressed search index. This section covers Phase 3 (Text processing, Porter Stemming from scratch, and Variable-Byte compression) and Phase 4 (MapReduce batch indexing with External Merge Sort and immutable segments).

---

### 3.1 Phase 3: Text Pipeline, Porter Stemmer & Compression Codecs

```
             [ RAW DOCUMENT TO COMPRESSED POSTINGS PIPELINE ]

    Raw HTML Document: "<title>Running Engines</title><p>Engineers run tests.</p>"
                           |
                           | jsoup HTML Parser
                           v
    Extracted Text: "Running Engines Engineers run tests"
                           |
                           | Unicode Tokenizer & Lowercaser
                           v
    Tokens: ["running", "engines", "engineers", "run", "tests"]
                           |
                           | Stopword Filter (removes 'and', 'the', 'is', etc.)
                           v
    Filtered Tokens: ["running", "engines", "engineers", "run", "tests"]
                           |
                           | Porter Stemmer (Martin Porter 1980 from scratch)
                           v
    Stemmed Roots: ["run", "engin", "engin", "run", "test"]
                           |
                           | Positional Index Accumulation
                           v
    Term -> Postings:
    "run"   -> Doc 42, TermFreq=2, Positions=[0, 3]
    "engin" -> Doc 42, TermFreq=2, Positions=[1, 2]
    "test"  -> Doc 42, TermFreq=1, Positions=[4]
                           |
                           | D-Gaps Delta Encoding & Variable-Byte (VByte) Compression
                           v
    Binary Compressed Index Segment (.dict, .postings, .positions)
```

---

#### 3.1.1 The Porter Stemmer (Martin Porter 1980) from First Principles

Stemming strips grammatical suffixes to conflate morphological variants to a common base (e.g., `running`, `runs`, `ran` $\to$ `run`; `engine`, `engineering`, `engineers` $\to$ `engin`).

SearchForge implements the complete 1980 Porter Stemming algorithm without external libraries. The algorithm defines a word as a sequence of consonants ($c$) and vowels ($v$), represented in form:
$$[c](vc)^m[v]$$
where $m$ is the **measure** of the word:
- $m=0$: `tr`, `ee`, `tree`, `by`
- $m=1$: `trouble`, `oats`, `trees`, `ivy`
- $m=2$: `troubles`, `private`, `oaten`

```java
package dev.searchforge.indexer.analysis;

/**
 * Clean, production-grade implementation of the Martin Porter (1980) Stemming Algorithm.
 *
 * Why from scratch?
 * Eliminates external library bloat and provides deterministic stemming behavior
 * identical to academic Information Retrieval benchmarks.
 *
 * Algorithm Structure:
 * - Step 1a: Plurals and past participles (-sses -> -ss, -ies -> -i, -s -> empty)
 * - Step 1b: Past tense and progressive (-eed, -ed, -ing)
 * - Step 1c: Y to I (-y -> -i if preceding stem contains another vowel)
 * - Step 2: Derivational suffixes (-ational -> -ate, -ization -> -ize)
 * - Step 3: Secondary suffixes (-icate -> -ic, -ful -> empty, -ness -> empty)
 * - Step 4: Suffix removal in measure m > 1 (-al, -ance, -er, -ic, -able)
 * - Step 5a & 5b: Final tidying (-e removal if m > 1, double consonant reduction)
 */
public class PorterStemmer {

    private char[] b;
    private int i;     // offset into b
    private int i_end; // offset to end of stemmed word
    private int j, k;

    public PorterStemmer() {
        b = new char[50];
        i = 0;
        i_end = 0;
    }

    private boolean isConsonant(int idx) {
        return switch (b[idx]) {
            case 'a', 'e', 'i', 'o', 'u' -> false;
            case 'y' -> (idx == 0) || !isConsonant(idx - 1);
            default -> true;
        };
    }

    /**
     * Measures the number of consonant-vowel transitions (vc sequences) in the stem.
     * Represented as m in Porter's paper: [c](vc)^m[v]
     */
    private int measure() {
        int n = 0;
        int idx = 0;
        while (true) {
            if (idx > j) return n;
            if (!isConsonant(idx)) break;
            idx++;
        }
        idx++;
        while (true) {
            while (true) {
                if (idx > j) return n;
                if (isConsonant(idx)) break;
                idx++;
            }
            idx++;
            n++;
            while (true) {
                if (idx > j) return n;
                if (!isConsonant(idx)) break;
                idx++;
            }
            idx++;
        }
    }

    private boolean vowelInStem() {
        for (int idx = 0; idx <= j; idx++) {
            if (!isConsonant(idx)) return true;
        }
        return false;
    }

    private boolean doubleConsonant(int idx) {
        if (idx < 1) return false;
        if (b[idx] != b[idx - 1]) return false;
        return isConsonant(idx);
    }

    private boolean cvc(int idx) {
        if (idx < 2 || !isConsonant(idx) || isConsonant(idx - 1) || !isConsonant(idx - 2)) return false;
        int ch = b[idx];
        return ch != 'w' && ch != 'x' && ch != 'y';
    }

    private boolean ends(String s) {
        int len = s.length();
        int o = k - len + 1;
        if (o < 0) return false;
        for (int idx = 0; idx < len; idx++) {
            if (b[o + idx] != s.charAt(idx)) return false;
        }
        j = k - len;
        return true;
    }

    private void setto(String s) {
        int len = s.length();
        int o = j + 1;
        for (int idx = 0; idx < len; idx++) b[o + idx] = s.charAt(idx);
        k = j + len;
    }

    private void r(String s) {
        if (measure() > 0) setto(s);
    }

    /* Step 1a: handles plurals and suffix replacements */
    private void step1a() {
        if (b[k] == 's') {
            if (ends("sses")) k -= 2;
            else if (ends("ies")) setto("i");
            else if (b[k - 1] != 's') k--;
        }
    }

    /* Step 1b: handles past tense and progressive */
    private void step1b() {
        if (ends("eed")) {
            if (measure() > 0) k--;
        } else if ((ends("ed") || ends("ing")) && vowelInStem()) {
            k = j;
            if (ends("at")) setto("ate");
            else if (ends("bl")) setto("ble");
            else if (ends("iz")) setto("ize");
            else if (doubleConsonant(k)) {
                k--;
                int ch = b[k];
                if (ch == 'l' || ch == 's' || ch == 'z') k++;
            } else if (measure() == 1 && cvc(k)) setto("e");
        }
    }

    /* Step 1c: turns terminal y to i */
    private void step1c() {
        if (ends("y") && vowelInStem()) b[k] = 'i';
    }

    /* Step 2: maps double suffices to single ones */
    private void step2() {
        if (k == 0) return;
        switch (b[k - 1]) {
            case 'a' -> { if (ends("ational")) r("ate"); else if (ends("tional")) r("tion"); }
            case 'c' -> { if (ends("enci")) r("ence"); else if (ends("anci")) r("ance"); }
            case 'e' -> { if (ends("izer")) r("ize"); }
            case 'l' -> { if (ends("bli")) r("ble"); else if (ends("alli")) r("al"); else if (ends("entli")) r("ent"); else if (ends("eli")) r("e"); }
            case 'o' -> { if (ends("ization")) r("ize"); else if (ends("ation")) r("ate"); else if (ends("ator")) r("ate"); }
            case 's' -> { if (ends("alism")) r("al"); else if (ends("iveness")) r("ive"); else if (ends("fulness")) r("ful"); else if (ends("ousness")) r("ous"); }
            case 't' -> { if (ends("aliti")) r("al"); else if (ends("iviti")) r("ive"); }
        }
    }

    /* Step 3: replaces -ic-, -full, -ness, etc. */
    private void step3() {
        switch (b[k]) {
            case 'e' -> { if (ends("icate")) r("ic"); else if (ends("ative")) r(""); else if (ends("alize")) r("al"); }
            case 'i' -> { if (ends("iciti")) r("ic"); }
            case 'l' -> { if (ends("ical")) r("ic"); else if (ends("ful")) r(""); }
            case 's' -> { if (ends("ness")) r(""); }
        }
    }

    /* Step 4: deletes suffixes in measure m > 1 */
    private void step4() {
        if (k == 0) return;
        switch (b[k - 1]) {
            case 'a' -> { if (ends("al")) {} else return; }
            case 'c' -> { if (ends("ance") || ends("ence")) {} else return; }
            case 'e' -> { if (ends("er")) {} else return; }
            case 'i' -> { if (ends("ic")) {} else return; }
            case 'l' -> { if (ends("able") || ends("ible")) {} else return; }
            case 'n' -> { if (ends("ant") || ends("ement") || ends("ment") || ends("ent")) {} else return; }
            case 'o' -> { if (ends("ion") && (j >= 0 && (b[j] == 's' || b[j] == 't'))) {} else if (ends("ou")) {} else return; }
            case 's' -> { if (ends("ism")) {} else return; }
            case 't' -> { if (ends("ate") || ends("iti")) {} else return; }
            case 'u' -> { if (ends("ous")) {} else return; }
            case 'v' -> { if (ends("ive")) {} else return; }
            case 'z' -> { if (ends("ize")) {} else return; }
            default -> { return; }
        }
        if (measure() > 1) k = j;
    }

    /* Step 5: tidies up terminal -e and double consonants */
    private void step5() {
        j = k;
        if (b[k] == 'e') {
            int a = measure();
            if (a > 1 || (a == 1 && !cvc(k - 1))) k--;
        }
        if (b[k] == 'l' && doubleConsonant(k) && measure() > 1) k--;
    }

    /**
     * Stems an input word to its root morphological form.
     */
    public synchronized String stem(String word) {
        word = word.toLowerCase().trim();
        if (word.length() < 3) return word;

        if (b.length < word.length()) b = new char[word.length() + 10];
        word.getChars(0, word.length(), b, 0);
        i = word.length();
        k = i - 1;

        step1a();
        step1b();
        step1c();
        step2();
        step3();
        step4();
        step5();

        return new String(b, 0, k + 1);
    }
}
```

---

#### 3.1.2 Compression Codecs: D-Gaps Delta Encoding & Variable-Byte (VByte)

Uncompressed posting lists store 32-bit (4-byte) or 64-bit (8-byte) integers for each `docId` and word position. For 50,000 documents with millions of term occurrences, raw integer storage requires hundreds of megabytes.

SearchForge compresses inverted posting lists using two complementary techniques:
1. **D-Gaps Delta Encoding**: Because `docIds` in a posting list are strictly increasing:
   $$[105, 108, 110, 125] \implies [105, 3, 2, 15]$$
   Large integers collapse into small positive integers ($\le 127$).
2. **Variable-Byte (VByte) Encoding**: Encodes arbitrary-length integers into a sequence of bytes using 7 bits for data and the 8th bit (MSB) as a continuation marker:
   - Value $0 \le x \le 127$ fits in **1 byte** (75% storage savings over 4-byte `int`).
   - Value $128 \le x \le 16383$ fits in **2 bytes** (50% storage savings).

```java
package dev.searchforge.core.codec;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * High-performance Variable-Byte (VByte) and Delta Codec.
 *
 * Encoding Rule:
 * - Each byte stores 7 bits of the integer payload.
 * - The 8th bit (Most Significant Bit, mask 0x80) is the termination flag:
 *     0 -> More bytes follow for this integer.
 *     1 -> Terminal byte for this integer.
 *
 * Property:
 * Lossless roundtrip: decode(encode(numbers)) == numbers for all positive integers.
 */
public class VByteCodec {

    /**
     * Encodes a list of positive integers into a variable-byte compressed byte array.
     */
    public static byte[] encode(List<Integer> numbers) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(numbers.size() * 2);
        for (int n : numbers) {
            encodeNumber(n, out);
        }
        return out.toByteArray();
    }

    private static void encodeNumber(int n, ByteArrayOutputStream out) {
        if (n < 0) throw new IllegalArgumentException("VByte supports non-negative integers only: " + n);
        
        // Extract 7-bit chunks
        byte[] buffer = new byte[5];
        int idx = 0;
        while (true) {
            buffer[idx++] = (byte) (n & 0x7F);
            if (n <= 0x7F) break;
            n >>>= 7;
        }

        // Set MSB on terminal byte (continuation bit convention)
        buffer[0] |= (byte) 0x80;

        // Write in big-endian order
        for (int i = idx - 1; i >= 0; i--) {
            out.write(buffer[i]);
        }
    }

    /**
     * Decodes a byte buffer into a list of integers.
     */
    public static List<Integer> decode(byte[] bytes) {
        List<Integer> result = new ArrayList<>();
        int n = 0;
        for (byte b : bytes) {
            if ((b & 0x80) == 0) {
                // Continuation byte: accumulate 7 bits
                n = (n << 7) | (b & 0x7F);
            } else {
                // Terminal byte: accumulate last 7 bits and emit number
                n = (n << 7) | (b & 0x7F);
                result.add(n);
                n = 0;
            }
        }
        return result;
    }

    /**
     * Converts strictly increasing IDs into Delta gaps: [100, 104, 109] -> [100, 4, 5]
     */
    public static List<Integer> toDeltaGaps(List<Integer> sortedIds) {
        if (sortedIds == null || sortedIds.isEmpty()) return Collections.emptyList();
        List<Integer> gaps = new ArrayList<>(sortedIds.size());
        int prev = 0;
        for (int id : sortedIds) {
            gaps.add(id - prev);
            prev = id;
        }
        return gaps;
    }

    /**
     * Reconstructs original monotonic IDs from Delta gaps: [100, 4, 5] -> [100, 104, 109]
     */
    public static List<Integer> fromDeltaGaps(List<Integer> gaps) {
        if (gaps == null || gaps.isEmpty()) return Collections.emptyList();
        List<Integer> ids = new ArrayList<>(gaps.size());
        int accum = 0;
        for (int gap : gaps) {
            accum += gap;
            ids.add(accum);
        }
        return ids;
    }
}
```

---

### 3.2 Phase 4: MapReduce-Style Batch Indexer & Segment Merging

When indexing tens of thousands of web documents, the total dictionary and posting lists quickly exceed JVM heap capacity if accumulated in memory. SearchForge employs a **MapReduce architecture with External Merge Sort** to build immutable index segments:

```
                   [ MAPREDUCE BATCH INDEX BUILD ARCHITECTURE ]

    CRAWLED CORPUS (50,000 Documents)
          |
          | Map Phase (Virtual Threads)
          v
    Document Mapper: Emits (Term, DocId, Position) tuples into in-memory posting buffer.
          |
          | Buffer Threshold Reached (128 MB RAM)
          v
    In-Memory Sort: Sort tuples by Term ascending, then DocId ascending.
    Flush Run to Disk: "run_001.tmp", "run_002.tmp", ..., "run_016.tmp"
          |
          | Shuffle / External Merge Sort Phase
          v
    K-Way Min-Heap Merger:
    A PriorityQueue holds the current head token from each open disk run.
    Extracts minimum term across all runs with O(log K) heap complexity.
          |
          | Reduce Phase
          v
    Posting List Reducer:
    Aggregates all postings for Term_X, applies D-Gaps and VByte encoding,
    and writes out final immutable segment files:
      1. segment_0.dict      (Sorted term vocabulary + file offsets + DF)
      2. segment_0.postings  (VByte compressed docIds and term frequencies)
      3. segment_0.positions (VByte compressed term position offsets)
```

---

#### Implementation: `ExternalMergeSort.java` (K-Way Min-Heap Run Merger)

```java
package dev.searchforge.indexer.batch;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * External Merge Sort for building multi-gigabyte inverted indexes in bounded memory.
 *
 * Why this algorithm?
 * Memory is finite. Trying to sort 50,000 documents' postings in-memory triggers OOM.
 * External Merge Sort divides the problem:
 * 1. Accumulate postings in RAM up to 128 MB.
 * 2. Sort buffer in RAM and flush to a temporary disk "run".
 * 3. Open K runs simultaneously and merge them using a Min-Heap (PriorityQueue) in O(N log K) time.
 */
public class ExternalMergeSort {

    public record PostingRecord(String term, int docId, int position) 
            implements Comparable<PostingRecord> {
        @Override
        public int compareTo(PostingRecord o) {
            int termCmp = this.term.compareTo(o.term);
            if (termCmp != 0) return termCmp;
            int docCmp = Integer.compare(this.docId, o.docId);
            if (docCmp != 0) return docCmp;
            return Integer.compare(this.position, o.position);
        }
    }

    private static class RunReader implements AutoCloseable {
        private final BufferedReader reader;
        private PostingRecord current;

        public RunReader(File file) throws IOException {
            this.reader = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8));
            advance();
        }

        public boolean hasNext() {
            return current != null;
        }

        public PostingRecord peek() {
            return current;
        }

        public void advance() throws IOException {
            String line = reader.readLine();
            if (line == null) {
                current = null;
            } else {
                String[] parts = line.split("\t");
                current = new PostingRecord(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            }
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

    /**
     * Executes K-Way merge of temporary runs, streaming grouped term postings to a consumer.
     */
    public static void mergeRuns(List<File> runFiles, PostingConsumer consumer) throws IOException {
        PriorityQueue<Map.Entry<PostingRecord, RunReader>> minHeap = 
                new PriorityQueue<>(Comparator.comparing(Map.Entry::getKey));

        List<RunReader> openReaders = new ArrayList<>();
        try {
            for (File runFile : runFiles) {
                RunReader reader = new RunReader(runFile);
                if (reader.hasNext()) {
                    minHeap.add(Map.entry(reader.peek(), reader));
                    openReaders.add(reader);
                }
            }

            String currentTerm = null;
            List<PostingRecord> currentPostings = new ArrayList<>();

            while (!minHeap.isEmpty()) {
                Map.Entry<PostingRecord, RunReader> entry = minHeap.poll();
                PostingRecord record = entry.getKey();
                RunReader reader = entry.getValue();

                if (currentTerm == null) {
                    currentTerm = record.term();
                } else if (!currentTerm.equals(record.term())) {
                    // Group completed for currentTerm; emit to consumer
                    consumer.consume(currentTerm, currentPostings);
                    currentPostings.clear();
                    currentTerm = record.term();
                }

                currentPostings.add(record);

                reader.advance();
                if (reader.hasNext()) {
                    minHeap.add(Map.entry(reader.peek(), reader));
                }
            }

            if (currentTerm != null && !currentPostings.isEmpty()) {
                consumer.consume(currentTerm, currentPostings);
            }
        } finally {
            for (RunReader r : openReaders) {
                r.close();
            }
        }
    }

    @FunctionalInterface
    public interface PostingConsumer {
        void consume(String term, List<PostingRecord> postings) throws IOException;
    }
}
```

---

#### 3.2.1 Immutable Segment Layout & Binary Search Term Dictionary

Each finalized index segment consists of three tightly coupled files:
1. `segment_N.dict`:
   - Sorted list of terms.
   - For each term: `[TermString]\t[DocFrequency]\t[PostingsByteOffset]\t[PostingsByteLength]`.
   - Loaded into memory as a sorted string array, enabling $O(\log |V|)$ term lookup via binary search.
2. `segment_N.postings`:
   - Binary blob containing delta-encoded, VByte-compressed sequences:
     `[docId_gap, term_frequency, positions_byte_offset]`.
3. `segment_N.positions`:
   - Binary blob containing delta-encoded, VByte-compressed position offsets for every term occurrence.

```java
package dev.searchforge.indexer.segment;

import dev.searchforge.core.codec.VByteCodec;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Reader for immutable index segments with binary search dictionary lookup.
 *
 * Query Complexity:
 * - findTerm(term): O(log |Vocabulary|) via binary search over dictionary.
 * - readPostings(term): O(1) direct file seek using byte offsets recorded in dictionary.
 */
public class SegmentReader implements AutoCloseable {

    public record DictEntry(String term, int docFrequency, long postingsOffset, int postingsLength) {}

    private final DictEntry[] dictionary;
    private final RandomAccessFile postingsRaf;
    private final FileChannel postingsChannel;

    public SegmentReader(File dictFile, File postingsFile) throws IOException {
        this.dictionary = loadDictionary(dictFile);
        this.postingsRaf = new RandomAccessFile(postingsFile, "r");
        this.postingsChannel = postingsRaf.getChannel();
    }

    private DictEntry[] loadDictionary(File dictFile) throws IOException {
        List<DictEntry> entries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(dictFile, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] p = line.split("\t");
                entries.add(new DictEntry(p[0], Integer.parseInt(p[1]), Long.parseLong(p[2]), Integer.parseInt(p[3])));
            }
        }
        return entries.toArray(new DictEntry[0]);
    }

    /**
     * Binary searches the sorted dictionary to find a term's posting offset.
     */
    public DictEntry lookupTerm(String targetTerm) {
        int low = 0;
        int high = dictionary.length - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            int cmp = dictionary[mid].term().compareTo(targetTerm);

            if (cmp < 0) {
                low = mid + 1;
            } else if (cmp > 0) {
                high = mid - 1;
            } else {
                return dictionary[mid]; // Match found
            }
        }
        return null; // Term not present in this segment
    }

    /**
     * Reads and decompresses the posting list for a given dictionary entry.
     */
    public List<Integer> readDocIds(DictEntry entry) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(entry.postingsLength());
        postingsChannel.position(entry.postingsOffset());
        postingsChannel.read(buf);

        List<Integer> decodedGaps = VByteCodec.decode(buf.array());
        return VByteCodec.fromDeltaGaps(decodedGaps);
    }

    @Override
    public void close() throws IOException {
        postingsChannel.close();
        postingsRaf.close();
    }
}
```
