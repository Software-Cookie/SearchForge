package dev.searchforge.indexer.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// Text pipeline shared by the indexer and the query side, so both agree on terms:
// Unicode letter/digit tokens -> lowercase -> stopword removal -> Porter stem.
// Positions count every token (stopwords included), so phrase gaps stay consistent.
public final class Analyzer {

    public static final int MAX_TOKEN_LENGTH = 40;

    // A raw token: lowercase surface form, char offsets [start, end) and token position.
    public record Token(String surface, int start, int end, int position) {
    }

    // An index term with its position.
    public record Term(String term, int position) {
    }

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "about", "above", "after", "again", "against", "all", "am", "an", "and", "any",
            "are", "as", "at", "be", "because", "been", "before", "being", "below", "between",
            "both", "but", "by", "can", "did", "do", "does", "doing", "down", "during", "each",
            "few", "for", "from", "further", "had", "has", "have", "having", "he", "her", "here",
            "hers", "herself", "him", "himself", "his", "how", "i", "if", "in", "into", "is",
            "it", "its", "itself", "just", "me", "more", "most", "my", "myself", "no", "nor",
            "not", "now", "of", "off", "on", "once", "only", "or", "other", "our", "ours",
            "ourselves", "out", "over", "own", "same", "she", "should", "so", "some", "such",
            "than", "that", "the", "their", "theirs", "them", "themselves", "then", "there",
            "these", "they", "this", "those", "through", "to", "too", "under", "until", "up",
            "very", "was", "we", "were", "what", "when", "where", "which", "while", "who",
            "whom", "why", "will", "with", "you", "your", "yours", "yourself", "yourselves");

    private Analyzer() {
    }

    public static boolean isStopWord(String lowercaseWord) {
        return STOP_WORDS.contains(lowercaseWord);
    }

    public static List<Token> tokenize(String text) {
        List<Token> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        int n = text.length();
        int i = 0;
        int pos = 0;
        while (i < n) {
            int cp = text.codePointAt(i);
            if (!Character.isLetterOrDigit(cp)) {
                i += Character.charCount(cp);
                continue;
            }
            int start = i;
            while (i < n && Character.isLetterOrDigit(text.codePointAt(i))) {
                i += Character.charCount(text.codePointAt(i));
            }
            if (i - start <= MAX_TOKEN_LENGTH) {
                out.add(new Token(text.substring(start, i).toLowerCase(Locale.ROOT), start, i,
                        pos++));
            }
        }
        return out;
    }

    // Index terms for text, positions starting at firstPosition.
    public static List<Term> terms(String text, int firstPosition) {
        List<Term> out = new ArrayList<>();
        for (Token t : tokenize(text)) {
            String term = termOf(t.surface());
            if (term != null) {
                out.add(new Term(term, firstPosition + t.position()));
            }
        }
        return out;
    }

    // Term for one lowercase surface word, or null if it is a stopword.
    public static String termOf(String surface) {
        if (STOP_WORDS.contains(surface)) {
            return null;
        }
        return PorterStemmer.stem(surface);
    }
}
