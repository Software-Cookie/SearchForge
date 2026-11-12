package dev.searchforge.query;

import dev.searchforge.indexer.Index;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.constraints.AlphaChars;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class QueryPartsTest {

    @Test
    void parsesAndOrPhrasesAndExclusions() {
        ParsedQuery q = ParsedQuery.parse(
                "Java OR kotlin \"stream of data\" -android -\"old api\"");
        assertThat(q.clauses()).hasSize(2);
        assertThat(q.clauses().get(0).options()).extracting(p -> p.terms())
                .containsExactly(List.of("java"), List.of("kotlin"));
        ParsedQuery.Phrase phrase = q.clauses().get(1).options().get(0);
        assertThat(phrase.terms()).containsExactly("stream", "data");
        assertThat(phrase.offsets()).containsExactly(0, 2); // "of" leaves a gap
        assertThat(phrase.isSingleTerm()).isFalse();
        assertThat(q.excluded()).extracting(p -> p.terms())
                .containsExactly(List.of("android"), List.of("old", "api"));
        assertThat(q.words()).containsExactly("java", "kotlin", "stream", "of", "data");
        assertThat(q.positiveTerms()).containsExactly("java", "kotlin", "stream", "data");
    }

    @Test
    void handlesEdgeCases() {
        assertThat(ParsedQuery.parse("the of and").isEmpty()).isTrue();
        assertThat(ParsedQuery.parse(null).isEmpty()).isTrue();
        assertThat(ParsedQuery.parse("OR java").clauses()).hasSize(1);
        assertThat(ParsedQuery.parse("\"unclosed phrase").clauses().get(0).options().get(0)
                .terms()).containsExactly("unclos", "phrase");
        assertThat(ParsedQuery.parse("e-mail").clauses().get(0).options().get(0).terms())
                .containsExactly("e", "mail");
        assertThat(ParsedQuery.parse("- -- x").clauses()).hasSize(1);
        String many = "w ".repeat(40);
        assertThat(ParsedQuery.parse(many).clauses()).hasSize(ParsedQuery.MAX_CLAUSES);
    }

    @Property
    void setOperationsMatchNaiveVersions(
            @ForAll @Size(max = 300) List<@IntRange(min = 0, max = 2000) Integer> a,
            @ForAll @Size(max = 300) List<@IntRange(min = 0, max = 2000) Integer> b) {
        TreeSet<Integer> sa = new TreeSet<>(a);
        TreeSet<Integer> sb = new TreeSet<>(b);
        int[] x = sa.stream().mapToInt(Integer::intValue).toArray();
        int[] y = sb.stream().mapToInt(Integer::intValue).toArray();
        TreeSet<Integer> and = new TreeSet<>(sa);
        and.retainAll(sb);
        TreeSet<Integer> or = new TreeSet<>(sa);
        or.addAll(sb);
        TreeSet<Integer> minus = new TreeSet<>(sa);
        minus.removeAll(sb);
        assertThat(DocSets.intersect(x, y)).containsExactly(toArray(and));
        assertThat(DocSets.union(x, y)).containsExactly(toArray(or));
        assertThat(DocSets.subtract(x, y)).containsExactly(toArray(minus));
    }

    private static int[] toArray(TreeSet<Integer> s) {
        return s.stream().mapToInt(Integer::intValue).toArray();
    }

    @Test
    void gallopFindsLowerBound() {
        int[] arr = {1, 3, 5, 7, 9, 11, 13};
        assertThat(DocSets.gallop(arr, 0, 0)).isZero();
        assertThat(DocSets.gallop(arr, 0, 8)).isEqualTo(4);
        assertThat(DocSets.gallop(arr, 2, 13)).isEqualTo(6);
        assertThat(DocSets.gallop(arr, 0, 99)).isEqualTo(7);
        assertThat(DocSets.gallop(arr, 7, 1)).isEqualTo(7);
    }

    @Test
    void snippetsHighlightTheDensestWindow() {
        String text = "Intro words here. " + "filler ".repeat(60)
                + "Java streams make stream processing easy. " + "tail ".repeat(60);
        List<Snippets.Segment> s = Snippets.extract(text, Set.of("java", "stream"));
        String joined = s.stream().map(Snippets.Segment::text).collect(Collectors.joining());
        assertThat(joined).startsWith("… ").endsWith(" …").contains("Java streams make");
        assertThat(s).filteredOn(Snippets.Segment::hit).extracting(Snippets.Segment::text)
                .containsExactly("Java", "streams", "stream");
        assertThat(Snippets.extract("", Set.of("x"))).isEmpty();

        List<Snippets.Segment> noHit = Snippets.extract("short text only", Set.of("zzz"));
        assertThat(noHit).containsExactly(new Snippets.Segment("short text only", false));
        List<Snippets.Segment> first = Snippets.extract("java at start", Set.of("java"));
        assertThat(first.get(0)).isEqualTo(new Snippets.Segment("java", true));
    }

    @Test
    void autocompleteReturnsMostFrequentCompletions() {
        Autocomplete ac = new Autocomplete(List.of(new Index.VocabEntry("java", 90),
                new Index.VocabEntry("javascript", 80), new Index.VocabEntry("jvm", 50),
                new Index.VocabEntry("jakarta", 10), new Index.VocabEntry("python", 70)));
        assertThat(ac.completeWord("ja", 10)).containsExactly("java", "javascript", "jakarta");
        assertThat(ac.completeWord("j", 2)).containsExactly("java", "javascript");
        assertThat(ac.completeWord("x", 5)).isEmpty();
        assertThat(ac.completeWord("", 5)).isEmpty();
        assertThat(ac.suggest("learn Jav", 5)).containsExactly("learn java", "learn javascript");
        assertThat(ac.suggest("java ", 5)).isEmpty();
        assertThat(ac.suggest("   ", 5)).isEmpty();
        List<Index.VocabEntry> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(new Index.VocabEntry("word" + i, 100 - i));
        }
        assertThat(new Autocomplete(many).completeWord("w", 100)).hasSize(Autocomplete.TOP_K);
    }

    @Test
    void spellCorrectorPrefersCloserThenMoreFrequentWords() {
        SpellCorrector sc = new SpellCorrector(List.of(new Index.VocabEntry("python", 50),
                new Index.VocabEntry("pythons", 5), new Index.VocabEntry("java", 40),
                new Index.VocabEntry("javascript", 30), new Index.VocabEntry("java", 1)));
        assertThat(sc.correct("pyhton")).isEqualTo("python");
        assertThat(sc.correct("jvaa")).isNull(); // distance 2 is too far for a 4-letter word
        assertThat(sc.correct("javs")).isEqualTo("java");
        assertThat(sc.correct("python")).isNull();
        assertThat(sc.correct("ab")).isNull();
        assertThat(sc.correct("pyth0n")).isNull();
        assertThat(sc.correct("zzzzzzzz")).isNull();
        assertThat(sc.didYouMean(List.of("learn", "pyhton"), w -> w.equals("learn")))
                .isEqualTo("learn python");
        assertThat(sc.didYouMean(List.of("python"), w -> false)).isNull();
        assertThat(new SpellCorrector(List.of()).correct("anything")).isNull();
    }

    @Property(tries = 50)
    void bkTreeFindsTheSameBestDistanceAsBruteForce(
            @ForAll @Size(min = 1, max = 60) List<@AlphaChars @StringLength(min = 3, max = 9)
                    String> vocab,
            @ForAll @AlphaChars @StringLength(min = 3, max = 9) String query) {
        List<Index.VocabEntry> entries = new ArrayList<>();
        for (int i = 0; i < vocab.size(); i++) {
            entries.add(new Index.VocabEntry(vocab.get(i).toLowerCase(), vocab.size() - i));
        }
        SpellCorrector sc = new SpellCorrector(entries);
        String q = query.toLowerCase();
        String got = sc.correct(q);
        int max = q.length() <= 4 ? 1 : 2;
        int bruteBest = entries.stream().mapToInt(e -> SpellCorrector.levenshtein(q, e.word()))
                .min().orElse(99);
        if (entries.stream().anyMatch(e -> e.word().equals(q))) {
            assertThat(got).isNull();
        } else if (bruteBest <= max) {
            assertThat(SpellCorrector.levenshtein(q, got)).isEqualTo(bruteBest);
        } else {
            assertThat(got).isNull();
        }
    }

    @Test
    void levenshteinBasics() {
        assertThat(SpellCorrector.levenshtein("kitten", "sitting")).isEqualTo(3);
        assertThat(SpellCorrector.levenshtein("", "abc")).isEqualTo(3);
        assertThat(SpellCorrector.levenshtein("same", "same")).isZero();
    }

    @Test
    void relevanceMetrics() {
        List<String> ranked = List.of("a", "b", "c", "d");
        assertThat(RelevanceMetrics.precisionAtK(ranked, Set.of("a", "c"), 4)).isEqualTo(0.5);
        assertThat(RelevanceMetrics.precisionAtK(ranked, Set.of("a"), 10)).isEqualTo(0.1);
        assertThat(RelevanceMetrics.ndcgAtK(List.of("a", "b"), Map.of("a", 3, "b", 1), 10))
                .isCloseTo(1.0, within(1e-9));
        assertThat(RelevanceMetrics.ndcgAtK(List.of("b", "a"), Map.of("a", 3, "b", 1), 10))
                .isLessThan(1.0).isGreaterThan(0.0);
        assertThat(RelevanceMetrics.ndcgAtK(ranked, Map.of(), 10)).isZero();
    }
}
