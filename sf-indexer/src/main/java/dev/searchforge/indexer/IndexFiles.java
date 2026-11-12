package dev.searchforge.indexer;

// On-disk layout of one immutable index directory (format version 1).
//
//   meta.properties  counts, average field lengths, build stats
//   terms.dict       [int n] then n x (UTF term, VByte df, long offset, int length), sorted
//   postings.bin     per term: VByte df, VByte docBytes, VByte tfBytes, then
//                      docIds as VByte d-gaps | (tfTitle, tfHead, tfBody) VByte triples |
//                      per doc: positions as VByte d-gaps
//   docs.bin         per doc: url, title, stored text (int length + UTF-8 bytes each)
//   docs.idx         [int n] then n longs: offset of each record in docs.bin
//   norms.bin        [int n] then n x (int titleLen, int headLen, int bodyLen, float pageRank)
//   vocab.tsv        surface word TAB document frequency (for autocomplete and spelling)
public final class IndexFiles {

    public static final int FORMAT_VERSION = 1;
    public static final String META = "meta.properties";
    public static final String DICT = "terms.dict";
    public static final String POSTINGS = "postings.bin";
    public static final String DOCS = "docs.bin";
    public static final String DOCS_INDEX = "docs.idx";
    public static final String NORMS = "norms.bin";
    public static final String VOCAB = "vocab.tsv";

    // Gap between fields in the shared position space, so phrases never span fields.
    public static final int FIELD_GAP = 50;
    // Stored text per document, used for snippets.
    public static final int MAX_STORED_CHARS = 20_000;

    private IndexFiles() {
    }
}
