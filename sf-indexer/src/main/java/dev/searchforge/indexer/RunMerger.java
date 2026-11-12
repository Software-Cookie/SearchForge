package dev.searchforge.indexer;

import dev.searchforge.core.codec.VByte;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

// External merge sort for postings (the "shuffle" of MapReduce-style index construction):
// spill() writes the in-memory buffer as a sorted run; merge() k-way merges all runs with a
// min-heap and writes the final compressed postings and dictionary.
// Run entry stream per term: docId, tfTitle, tfHead, tfBody, then tfSum positions.
final class RunMerger {

    private final Path tmpDir;
    private final List<Path> runs = new ArrayList<>();

    RunMerger(Path tmpDir) throws IOException {
        this.tmpDir = Files.createDirectories(tmpDir);
    }

    int runCount() {
        return runs.size();
    }

    void spill(Map<String, IntList> buffer) throws IOException {
        Path run = tmpDir.resolve(String.format("run-%05d.tmp", runs.size()));
        List<String> terms = new ArrayList<>(buffer.keySet());
        terms.sort(null);
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(run), 1 << 16))) {
            for (String term : terms) {
                IntList ints = buffer.get(term);
                out.writeUTF(term);
                VByte.write(out, ints.size());
                for (int i = 0; i < ints.size(); i++) {
                    VByte.write(out, ints.get(i));
                }
            }
        }
        runs.add(run);
    }

    // Merges every run; returns {termCount, postingsBytes}.
    long[] merge(Path postingsFile, Path dictFile) throws IOException {
        PriorityQueue<RunReader> heap = new PriorityQueue<>(
                Comparator.comparing((RunReader r) -> r.term).thenComparingInt(r -> r.index));
        List<RunReader> readers = new ArrayList<>();
        try {
            for (int i = 0; i < runs.size(); i++) {
                RunReader r = new RunReader(runs.get(i), i);
                readers.add(r);
                if (r.next()) {
                    heap.add(r);
                }
            }
            return writeMerged(heap, postingsFile, dictFile);
        } finally {
            for (RunReader r : readers) {
                r.close();
            }
            for (Path run : runs) {
                Files.deleteIfExists(run);
            }
            Files.deleteIfExists(tmpDir);
        }
    }

    private static long[] writeMerged(PriorityQueue<RunReader> heap, Path postingsFile,
                                      Path dictFile) throws IOException {
        long termCount = 0;
        long offset = 0;
        Path dictTmp = dictFile.resolveSibling(dictFile.getFileName() + ".body");
        try (OutputStream post = new BufferedOutputStream(Files.newOutputStream(postingsFile),
                1 << 16);
             DataOutputStream dict = new DataOutputStream(new BufferedOutputStream(
                     Files.newOutputStream(dictTmp), 1 << 16))) {
            while (!heap.isEmpty()) {
                String term = heap.peek().term;
                IntList merged = new IntList(64);
                // Runs hold increasing docId ranges, so concatenating in run order stays sorted.
                while (!heap.isEmpty() && heap.peek().term.equals(term)) {
                    RunReader r = heap.poll();
                    merged.addAll(r.ints);
                    if (r.next()) {
                        heap.add(r);
                    }
                }
                byte[] record = encodeTerm(merged);
                post.write(record);
                dict.writeUTF(term);
                VByte.write(dict, docFreq(merged));
                dict.writeLong(offset);
                dict.writeInt(record.length);
                offset += record.length;
                termCount++;
            }
        }
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(dictFile), 1 << 16))) {
            out.writeInt(Math.toIntExact(termCount));
            Files.copy(dictTmp, out);
        }
        Files.delete(dictTmp);
        return new long[] {termCount, offset};
    }

    static int docFreq(IntList entries) {
        int df = 0;
        int i = 0;
        while (i < entries.size()) {
            int tfSum = entries.get(i + 1) + entries.get(i + 2) + entries.get(i + 3);
            i += 4 + tfSum;
            df++;
        }
        return df;
    }

    static byte[] encodeTerm(IntList e) {
        ByteArrayOutputStream docs = new ByteArrayOutputStream();
        ByteArrayOutputStream tfs = new ByteArrayOutputStream();
        ByteArrayOutputStream pos = new ByteArrayOutputStream();
        int df = 0;
        int prevDoc = 0;
        int i = 0;
        while (i < e.size()) {
            int doc = e.get(i);
            VByte.write(docs, doc - prevDoc);
            prevDoc = doc;
            int tfSum = 0;
            for (int f = 1; f <= 3; f++) {
                VByte.write(tfs, e.get(i + f));
                tfSum += e.get(i + f);
            }
            int prevPos = 0;
            for (int p = 0; p < tfSum; p++) {
                int position = e.get(i + 4 + p);
                VByte.write(pos, position - prevPos);
                prevPos = position;
            }
            i += 4 + tfSum;
            df++;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(docs.size() + tfs.size()
                + pos.size() + 12);
        VByte.write(out, df);
        VByte.write(out, docs.size());
        VByte.write(out, tfs.size());
        out.writeBytes(docs.toByteArray());
        out.writeBytes(tfs.toByteArray());
        out.writeBytes(pos.toByteArray());
        return out.toByteArray();
    }

    private static final class RunReader implements Closeable {
        final int index;
        final DataInputStream in;
        String term;
        IntList ints;

        RunReader(Path file, int index) throws IOException {
            this.index = index;
            this.in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file),
                    1 << 16));
        }

        boolean next() throws IOException {
            try {
                term = in.readUTF();
            } catch (EOFException e) {
                return false;
            }
            int n = VByte.read(in);
            ints = new IntList(n);
            for (int i = 0; i < n; i++) {
                ints.add(VByte.read(in));
            }
            return true;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
