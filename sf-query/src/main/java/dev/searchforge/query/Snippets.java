package dev.searchforge.query;

import dev.searchforge.indexer.analysis.Analyzer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Picks the window of WINDOW tokens containing the most distinct query terms (sliding
// window, O(n)), and returns it as plain-text segments flagged hit/not-hit. The UI renders
// segments as text nodes, so page content can never inject HTML.
public final class Snippets {

    public record Segment(String text, boolean hit) {
    }

    static final int WINDOW = 32;

    private Snippets() {
    }

    public static List<Segment> extract(String text, Collection<String> queryTerms) {
        List<Analyzer.Token> tokens = Analyzer.tokenize(text);
        if (tokens.isEmpty()) {
            return List.of();
        }
        String[] termAt = new String[tokens.size()];
        for (int i = 0; i < tokens.size(); i++) {
            String term = Analyzer.termOf(tokens.get(i).surface());
            termAt[i] = term != null && queryTerms.contains(term) ? term : null;
        }
        int best = bestWindowStart(termAt);
        int end = Math.min(tokens.size(), best + WINDOW);
        int from = tokens.get(best).start();
        int to = tokens.get(end - 1).end();

        List<Segment> out = new ArrayList<>();
        StringBuilder plain = new StringBuilder(best > 0 ? "… " : "");
        int cursor = from;
        for (int i = best; i < end; i++) {
            if (termAt[i] == null) {
                continue;
            }
            Analyzer.Token t = tokens.get(i);
            plain.append(text, cursor, t.start());
            if (!plain.isEmpty()) {
                out.add(new Segment(plain.toString(), false));
                plain.setLength(0);
            }
            out.add(new Segment(text.substring(t.start(), t.end()), true));
            cursor = t.end();
        }
        plain.append(text, cursor, to);
        if (end < tokens.size()) {
            plain.append(" …");
        }
        if (!plain.isEmpty()) {
            out.add(new Segment(plain.toString(), false));
        }
        return out;
    }

    // Start of the window with the most distinct matched terms (ties: more matches, earlier).
    private static int bestWindowStart(String[] termAt) {
        Map<String, Integer> inWindow = new HashMap<>();
        int matches = 0;
        int bestStart = 0;
        int bestDistinct = -1;
        int bestMatches = -1;
        for (int i = 0; i < termAt.length; i++) {
            if (termAt[i] != null) {
                inWindow.merge(termAt[i], 1, Integer::sum);
                matches++;
            }
            int start = i - WINDOW + 1;
            if (start > 0 && termAt[start - 1] != null) {
                String gone = termAt[start - 1];
                if (inWindow.merge(gone, -1, Integer::sum) == 0) {
                    inWindow.remove(gone);
                }
                matches--;
            }
            int s = Math.max(0, start);
            if (inWindow.size() > bestDistinct
                    || (inWindow.size() == bestDistinct && matches > bestMatches)) {
                bestDistinct = inWindow.size();
                bestMatches = matches;
                bestStart = s;
            }
        }
        // Start 4 tokens before the first hit for context. Moving the start forward up to the
        // first hit cannot drop any hit, since none come before it.
        if (bestDistinct > 0) {
            int firstHit = bestStart;
            while (termAt[firstHit] == null) {
                firstHit++;
            }
            bestStart = Math.max(bestStart, firstHit - 4);
        }
        return bestStart;
    }
}
