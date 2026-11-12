package dev.searchforge.query;

import dev.searchforge.indexer.Index;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

// "Did you mean" via a Burkhard-Keller tree over the vocabulary with Levenshtein distance.
// The triangle inequality lets a lookup skip every subtree whose edge label is outside
// [d - maxDist, d + maxDist], so only a small part of the tree is visited.
// Nodes live in parallel arrays (first-child / next-sibling) to keep the heap small.
public final class SpellCorrector {

    static final int MAX_WORDS = 100_000;

    private final String[] words;
    private final int[] dfs;
    private final int[] edge;
    private final int[] firstChild;
    private final int[] nextSibling;
    private final Map<String, Integer> docFreq = new HashMap<>();
    private int size;

    public SpellCorrector(List<Index.VocabEntry> vocabulary) {
        int n = Math.min(MAX_WORDS, vocabulary.size());
        words = new String[n];
        dfs = new int[n];
        edge = new int[n];
        firstChild = new int[n];
        nextSibling = new int[n];
        Arrays.fill(firstChild, -1);
        Arrays.fill(nextSibling, -1);
        for (int i = 0; i < n; i++) {
            Index.VocabEntry e = vocabulary.get(i);
            if (docFreq.putIfAbsent(e.word(), e.docFreq()) == null) {
                insert(e.word(), e.docFreq());
            }
        }
    }

    private void insert(String word, int df) {
        int id = size++;
        words[id] = word;
        dfs[id] = df;
        if (id == 0) {
            return;
        }
        int node = 0;
        while (true) {
            int d = levenshtein(word, words[node]);
            int child = firstChild[node];
            while (child != -1 && edge[child] != d) {
                child = nextSibling[child];
            }
            if (child == -1) {
                edge[id] = d;
                nextSibling[id] = firstChild[node];
                firstChild[node] = id;
                return;
            }
            node = child;
        }
    }

    // Best correction for one word, or null if the word is known or nothing is close.
    public String correct(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        if (size == 0 || docFreq.containsKey(w) || w.length() < 3 || hasDigit(w)) {
            return null;
        }
        int maxDist = w.length() <= 4 ? 1 : 2;
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        int bestDf = -1;
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        stack.push(0);
        while (!stack.isEmpty()) {
            int node = stack.pop();
            int d = levenshtein(w, words[node]);
            if (d <= maxDist && (d < bestDist || (d == bestDist && dfs[node] > bestDf))) {
                best = words[node];
                bestDist = d;
                bestDf = dfs[node];
            }
            for (int c = firstChild[node]; c != -1; c = nextSibling[c]) {
                if (edge[c] >= d - maxDist && edge[c] <= d + maxDist) {
                    stack.push(c);
                }
            }
        }
        return best;
    }

    // Rewrites a query, correcting only words that known rejects; null when nothing changes.
    public String didYouMean(List<String> queryWords, Predicate<String> known) {
        StringBuilder sb = new StringBuilder();
        boolean changed = false;
        for (String w : queryWords) {
            String c = known.test(w) ? null : correct(w);
            changed |= c != null;
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(c == null ? w : c);
        }
        return changed ? sb.toString() : null;
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    private static boolean hasDigit(String w) {
        return w.chars().anyMatch(Character::isDigit);
    }
}
