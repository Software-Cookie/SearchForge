package dev.searchforge.query;

import dev.searchforge.indexer.analysis.Analyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Query syntax:  java streams        both words (AND)
//                java OR kotlin      either word
//                "stream api"        exact phrase
//                java -android       exclude a word (or -"a phrase")
// A word that splits into several tokens (e-mail, c3po) is treated as a phrase.
public record ParsedQuery(List<Clause> clauses, List<Phrase> excluded, List<String> words) {

    // A sequence of terms with positions relative to the first one (stopword gaps kept).
    public record Phrase(List<String> terms, int[] offsets) {
        public boolean isSingleTerm() {
            return terms.size() == 1;
        }
    }

    // A document matches a clause if it matches any of the options.
    public record Clause(List<Phrase> options) {
    }

    public static final int MAX_CLAUSES = 16;
    private static final Pattern TOKEN = Pattern.compile("-?\"[^\"]*\"?|\\S+");

    public boolean isEmpty() {
        return clauses.isEmpty();
    }

    public static ParsedQuery parse(String query) {
        List<Clause> clauses = new ArrayList<>();
        List<Phrase> excluded = new ArrayList<>();
        List<String> words = new ArrayList<>();
        boolean pendingOr = false;
        Matcher m = TOKEN.matcher(query == null ? "" : query);
        while (m.find()) {
            String raw = m.group();
            if (raw.equals("OR")) {
                pendingOr = !clauses.isEmpty();
                continue;
            }
            boolean negative = raw.startsWith("-") && raw.length() > 1;
            String text = negative ? raw.substring(1) : raw;
            text = text.replace("\"", " ");
            Phrase phrase = phrase(text);
            if (phrase == null) {
                continue; // only stopwords or punctuation
            }
            if (negative) {
                excluded.add(phrase);
                pendingOr = false;
                continue;
            }
            Analyzer.tokenize(text).forEach(t -> words.add(t.surface()));
            if (pendingOr) {
                clauses.get(clauses.size() - 1).options().add(phrase);
                pendingOr = false;
            } else if (clauses.size() < MAX_CLAUSES) {
                List<Phrase> options = new ArrayList<>();
                options.add(phrase);
                clauses.add(new Clause(options));
            }
        }
        return new ParsedQuery(clauses, excluded, words);
    }

    private static Phrase phrase(String text) {
        List<Analyzer.Term> terms = Analyzer.terms(text, 0);
        if (terms.isEmpty()) {
            return null;
        }
        List<String> t = new ArrayList<>();
        int[] offsets = new int[terms.size()];
        int first = terms.get(0).position();
        for (int i = 0; i < terms.size(); i++) {
            t.add(terms.get(i).term());
            offsets[i] = terms.get(i).position() - first;
        }
        return new Phrase(t, offsets);
    }

    // Distinct positive terms (used for scoring and highlighting).
    public List<String> positiveTerms() {
        List<String> out = new ArrayList<>();
        for (Clause c : clauses) {
            for (Phrase p : c.options()) {
                for (String t : p.terms()) {
                    if (!out.contains(t)) {
                        out.add(t);
                    }
                }
            }
        }
        return out;
    }
}
