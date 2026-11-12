package dev.searchforge.indexer;

import dev.searchforge.core.codec.VByte;

import java.nio.ByteBuffer;

// The postings of one term, decoded lazily in three stages so that a query only pays for
// what it uses: docIds (for intersection), term frequencies (for scoring), positions
// (for phrase queries). Not thread-safe; create one per query.
public final class PostingList {

    private final ByteBuffer buf;
    private final int df;
    private final int docsStart;
    private final int tfsStart;
    private final int posStart;
    private int[] docIds;
    private int[] tfs;
    private int[] posOffsets;
    private int[] positions;

    PostingList(ByteBuffer record) {
        this.buf = record;
        ByteBuffer b = record.duplicate();
        this.df = VByte.read(b);
        int docBytes = VByte.read(b);
        int tfBytes = VByte.read(b);
        this.docsStart = b.position();
        this.tfsStart = docsStart + docBytes;
        this.posStart = tfsStart + tfBytes;
    }

    public int docFreq() {
        return df;
    }

    public int[] docIds() {
        if (docIds == null) {
            docIds = VByte.readDeltas(buf.duplicate().position(docsStart), df);
        }
        return docIds;
    }

    // Term frequency of the i-th posting in field 0 (title), 1 (headings) or 2 (body).
    public int tf(int i, int field) {
        if (tfs == null) {
            ByteBuffer b = buf.duplicate().position(tfsStart);
            tfs = new int[df * 3];
            for (int k = 0; k < tfs.length; k++) {
                tfs[k] = VByte.read(b);
            }
        }
        return tfs[i * 3 + field];
    }

    // Positions of the i-th posting, ascending, in the shared title|headings|body space.
    public int[] positions(int i) {
        if (positions == null) {
            decodePositions();
        }
        int from = posOffsets[i];
        int to = posOffsets[i + 1];
        int[] out = new int[to - from];
        System.arraycopy(positions, from, out, 0, out.length);
        return out;
    }

    private void decodePositions() {
        posOffsets = new int[df + 1];
        for (int i = 0; i < df; i++) {
            posOffsets[i + 1] = posOffsets[i] + tf(i, 0) + tf(i, 1) + tf(i, 2);
        }
        positions = new int[posOffsets[df]];
        ByteBuffer b = buf.duplicate().position(posStart);
        for (int i = 0; i < df; i++) {
            int prev = 0;
            for (int p = posOffsets[i]; p < posOffsets[i + 1]; p++) {
                prev += VByte.read(b);
                positions[p] = prev;
            }
        }
    }
}
