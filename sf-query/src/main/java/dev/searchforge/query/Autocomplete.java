package dev.searchforge.query;

import dev.searchforge.indexer.Index;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

// Prefix trie where every node caches its top-K completions. Words are inserted in
// descending document-frequency order, so the first K words to pass through a node are
// exactly its K most frequent completions: lookup is O(prefix length), no subtree walk.
public final class Autocomplete {

    public static final int TOP_K = 8;
    static final int MAX_WORDS = 50_000;

    private static final class Node {
        char[] keys = new char[0];
        Node[] kids = new Node[0];
        int[] top = new int[0];

        Node child(char c) {
            for (int i = 0; i < keys.length; i++) {
                if (keys[i] == c) {
                    return kids[i];
                }
            }
            return null;
        }

        Node addChild(char c) {
            Node n = new Node();
            keys = Arrays.copyOf(keys, keys.length + 1);
            kids = Arrays.copyOf(kids, kids.length + 1);
            keys[keys.length - 1] = c;
            kids[kids.length - 1] = n;
            return n;
        }

        void offer(int word) {
            if (top.length < TOP_K) {
                top = Arrays.copyOf(top, top.length + 1);
                top[top.length - 1] = word;
            }
        }
    }

    private final Node root = new Node();
    private final String[] words;

    // vocabulary must be sorted by descending document frequency (as vocab.tsv is).
    public Autocomplete(List<Index.VocabEntry> vocabulary) {
        int n = Math.min(MAX_WORDS, vocabulary.size());
        words = new String[n];
        for (int w = 0; w < n; w++) {
            String word = vocabulary.get(w).word();
            words[w] = word;
            Node node = root;
            node.offer(w);
            for (int i = 0; i < word.length(); i++) {
                Node next = node.child(word.charAt(i));
                node = next == null ? node.addChild(word.charAt(i)) : next;
                node.offer(w);
            }
        }
    }

    public List<String> completeWord(String prefix, int limit) {
        if (prefix.isEmpty()) {
            return List.of();
        }
        Node node = root;
        for (int i = 0; i < prefix.length() && node != null; i++) {
            node = node.child(prefix.charAt(i));
        }
        if (node == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, node.top.length); i++) {
            out.add(words[node.top[i]]);
        }
        return out;
    }

    // Completes the last word of a query, keeping what came before it.
    public List<String> suggest(String query, int limit) {
        String q = query.toLowerCase(Locale.ROOT).stripLeading();
        if (q.isEmpty() || Character.isWhitespace(q.charAt(q.length() - 1))) {
            return List.of();
        }
        int cut = q.lastIndexOf(' ') + 1;
        String head = q.substring(0, cut);
        List<String> out = new ArrayList<>();
        for (String w : completeWord(q.substring(cut), limit)) {
            out.add(head + w);
        }
        return out;
    }
}
