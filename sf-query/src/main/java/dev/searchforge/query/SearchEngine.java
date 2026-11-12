package dev.searchforge.query;

import dev.searchforge.indexer.Index;
import dev.searchforge.indexer.IntList;
import dev.searchforge.indexer.PostingList;
import dev.searchforge.indexer.analysis.Analyzer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

// Query execution: parse -> candidate docs per clause (union of options; phrases checked
// with positions) -> galloping intersection, smallest clause first -> exclusions ->
// BM25F + PageRank scoring into a bounded min-heap (top-K in O(N log K)) -> snippets.
// Thread-safe: all per-query state is local.
public final class SearchEngine {

    public static final int MAX_RESULT_WINDOW = 1_000;
    private static final int DEADLINE_CHECK_EVERY = 256;
    private static final int[] EMPTY = new int[0];

    private record Scored(int doc, double score) {
    }

    private final Index index;
    private final Bm25 bm25;
    private final Autocomplete autocomplete;
    private final SpellCorrector speller;

    public SearchEngine(Index index) {
        this.index = index;
        this.bm25 = new Bm25(index);
        this.autocomplete = new Autocomplete(index.vocabulary());
        this.speller = new SpellCorrector(index.vocabulary());
    }

    public Index index() {
        return index;
    }

    public List<String> suggest(String prefix, int limit) {
        return autocomplete.suggest(prefix, limit);
    }

    public SearchResult search(String query, int page, int size, long deadlineNanos) {
        if (page < 1 || size < 1 || (long) page * size > MAX_RESULT_WINDOW) {
            throw new IllegalArgumentException("page * size must be between 1 and "
                    + MAX_RESULT_WINDOW);
        }
        long start = System.nanoTime();
        ParsedQuery pq = ParsedQuery.parse(query);
        Map<String, PostingList> lists = new HashMap<>();
        int[] matches = pq.isEmpty() ? EMPTY : match(pq, lists, deadlineNanos);
        List<Scored> top = topK(matches, pq.positiveTerms(), lists, page * size, deadlineNanos);

        List<SearchResult.Hit> hits = new ArrayList<>();
        HashSet<String> terms = new HashSet<>(pq.positiveTerms());
        for (int i = (page - 1) * size; i < Math.min(top.size(), page * size); i++) {
            Scored s = top.get(i);
            Index.StoredDoc d = index.doc(s.doc());
            String title = d.title().isBlank() ? d.url() : d.title();
            hits.add(new SearchResult.Hit(d.url(), title, Math.round(s.score() * 1e4) / 1e4,
                    Snippets.extract(d.text(), terms)));
        }
        // Stopwords are never "corrected". Any other word whose exact spelling is not in the
        // vocabulary gets a suggestion if a close, known word exists (results still show).
        String didYouMean = speller.didYouMean(pq.words(), w -> Analyzer.termOf(w) == null);
        long tookMs = (System.nanoTime() - start) / 1_000_000;
        return new SearchResult(query, matches.length, tookMs, page, size, hits, didYouMean);
    }

    private int[] match(ParsedQuery pq, Map<String, PostingList> lists, long deadline) {
        List<int[]> perClause = new ArrayList<>();
        for (ParsedQuery.Clause c : pq.clauses()) {
            int[] docs = EMPTY;
            for (ParsedQuery.Phrase p : c.options()) {
                docs = DocSets.union(docs, phraseDocs(p, lists, deadline));
            }
            if (docs.length == 0) {
                return EMPTY;
            }
            perClause.add(docs);
        }
        perClause.sort(Comparator.comparingInt(a -> a.length));
        int[] result = perClause.get(0);
        for (int i = 1; i < perClause.size() && result.length > 0; i++) {
            result = DocSets.intersect(result, perClause.get(i));
        }
        for (ParsedQuery.Phrase p : pq.excluded()) {
            result = DocSets.subtract(result, phraseDocs(p, lists, deadline));
        }
        return result;
    }

    private PostingList list(String term, Map<String, PostingList> lists) {
        return lists.computeIfAbsent(term, index::postings);
    }

    private int[] phraseDocs(ParsedQuery.Phrase p, Map<String, PostingList> lists,
                             long deadline) {
        PostingList[] pls = new PostingList[p.terms().size()];
        for (int i = 0; i < pls.length; i++) {
            pls[i] = list(p.terms().get(i), lists);
            if (pls[i] == null) {
                return EMPTY;
            }
        }
        if (pls.length == 1) {
            return pls[0].docIds();
        }
        int[] candidates = pls[0].docIds();
        for (int i = 1; i < pls.length; i++) {
            candidates = DocSets.intersect(candidates, pls[i].docIds());
        }
        IntList out = new IntList(Math.max(1, candidates.length));
        for (int c = 0; c < candidates.length; c++) {
            if (c % DEADLINE_CHECK_EVERY == 0) {
                checkDeadline(deadline);
            }
            if (phraseAt(candidates[c], pls, p.offsets())) {
                out.add(candidates[c]);
            }
        }
        return out.toArray();
    }

    private static boolean phraseAt(int doc, PostingList[] pls, int[] offsets) {
        int[][] pos = new int[pls.length][];
        for (int i = 0; i < pls.length; i++) {
            pos[i] = pls[i].positions(Arrays.binarySearch(pls[i].docIds(), doc));
        }
        for (int p : pos[0]) {
            boolean all = true;
            for (int i = 1; i < pls.length && all; i++) {
                all = Arrays.binarySearch(pos[i], p + offsets[i]) >= 0;
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    private List<Scored> topK(int[] docs, List<String> terms, Map<String, PostingList> lists,
                              int k, long deadline) {
        Comparator<Scored> worstFirst = Comparator.comparingDouble(Scored::score)
                .thenComparing(Comparator.comparingInt(Scored::doc).reversed());
        PriorityQueue<Scored> heap = new PriorityQueue<>(k + 1, worstFirst);
        PostingList[] pls = new PostingList[terms.size()];
        int[] cursor = new int[terms.size()];
        for (int t = 0; t < pls.length; t++) {
            pls[t] = list(terms.get(t), lists);
        }
        int[] tf = new int[3];
        for (int i = 0; i < docs.length; i++) {
            if (i % DEADLINE_CHECK_EVERY == 0) {
                checkDeadline(deadline);
            }
            int doc = docs[i];
            double score = bm25.prior(doc);
            for (int t = 0; t < pls.length; t++) {
                if (pls[t] == null) {
                    continue;
                }
                int[] ids = pls[t].docIds();
                cursor[t] = DocSets.gallop(ids, cursor[t], doc);
                if (cursor[t] < ids.length && ids[cursor[t]] == doc) {
                    for (int f = 0; f < 3; f++) {
                        tf[f] = pls[t].tf(cursor[t], f);
                    }
                    score += bm25.termScore(doc, pls[t].docFreq(), tf);
                }
            }
            heap.add(new Scored(doc, score));
            if (heap.size() > k) {
                heap.poll();
            }
        }
        List<Scored> out = new ArrayList<>(heap);
        out.sort(worstFirst.reversed());
        return out;
    }

    private static void checkDeadline(long deadlineNanos) {
        if (System.nanoTime() - deadlineNanos > 0) {
            throw new QueryTimeoutException();
        }
    }
}
