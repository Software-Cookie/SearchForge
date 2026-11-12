package dev.searchforge.query;

import dev.searchforge.core.page.PageRecord;
import dev.searchforge.indexer.Index;
import dev.searchforge.indexer.IndexBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchEngineTest {

    @TempDir
    static Path dir;

    static SearchEngine engine;

    private static final long FAR = Long.MAX_VALUE / 4;

    @BeforeAll
    static void buildIndex() throws IOException {
        List<PageRecord> pages = List.of(
                page("https://d/streams", "Java Streams Tutorial", "Collectors",
                        "Java streams process sequences of elements. A stream pipeline has a "
                                + "source, intermediate operations and a terminal operation."),
                page("https://d/lists", "Python Lists", "Comprehensions",
                        "Python list comprehensions are concise. Java has streams instead."),
                page("https://d/android", "Android Java", "",
                        "Java on Android uses streams of data and the Android runtime."),
                page("https://d/kotlin", "Kotlin Coroutines", "",
                        "Kotlin coroutines handle data streams asynchronously."),
                page("https://d/data", "Streams of data", "",
                        "Moving streams of data is what pipelines do all day."),
                page("https://d/notitle", "", "", "Untitled java page."));
        try (IndexBuilder b = new IndexBuilder(dir.resolve("idx"), 1 << 20)) {
            for (int i = 0; i < pages.size(); i++) {
                b.add(pages.get(i), i == 0 ? new int[0] : new int[] {0});
            }
            b.finish();
        }
        engine = new SearchEngine(Index.open(dir.resolve("idx"), false));
    }

    private static PageRecord page(String url, String title, String head, String body) {
        return new PageRecord(url, title, head, body, List.of(), 0);
    }

    private static List<String> urls(SearchResult r) {
        return r.results().stream().map(SearchResult.Hit::url).toList();
    }

    @Test
    void andQueryRanksTitleMatchesFirst() {
        SearchResult r = engine.search("java streams", 1, 10, FAR);
        assertThat(r.totalHits()).isEqualTo(3);
        assertThat(urls(r).get(0)).isEqualTo("https://d/streams");
        assertThat(r.results().get(0).score()).isGreaterThan(r.results().get(2).score());
        assertThat(r.results().get(0).snippet()).anyMatch(Snippets.Segment::hit);
        assertThat(r.didYouMean()).isNull();
        assertThat(r.query()).isEqualTo("java streams");
    }

    @Test
    void orPhraseAndExclusion() {
        assertThat(urls(engine.search("kotlin OR python", 1, 10, FAR)))
                .containsExactlyInAnyOrder("https://d/kotlin", "https://d/lists");
        assertThat(urls(engine.search("\"streams of data\"", 1, 10, FAR)))
                .containsExactlyInAnyOrder("https://d/android", "https://d/data");
        assertThat(urls(engine.search("\"data streams\"", 1, 10, FAR)))
                .containsExactly("https://d/kotlin");
        assertThat(urls(engine.search("java -android", 1, 10, FAR)))
                .doesNotContain("https://d/android").contains("https://d/streams");
        assertThat(urls(engine.search("java -\"android runtime\"", 1, 10, FAR)))
                .doesNotContain("https://d/android");
        assertThat(engine.search("java nosuchword", 1, 10, FAR).totalHits()).isZero();
        assertThat(engine.search("\"java nosuchword\"", 1, 10, FAR).totalHits()).isZero();
    }

    @Test
    void pagesThroughResultsAndUsesUrlWhenTitleMissing() {
        SearchResult p1 = engine.search("java", 1, 2, FAR);
        SearchResult p2 = engine.search("java", 2, 2, FAR);
        assertThat(p1.totalHits()).isEqualTo(4);
        assertThat(p1.results()).hasSize(2);
        assertThat(p2.results()).hasSize(2);
        assertThat(urls(p1)).doesNotContainAnyElementsOf(urls(p2));
        assertThat(engine.search("java", 3, 2, FAR).results()).isEmpty();
        assertThat(engine.search("untitled", 1, 10, FAR).results().get(0).title())
                .isEqualTo("https://d/notitle");
        assertThat(engine.search("java", 9, 2, FAR).results()).isEmpty();
    }

    @Test
    void suggestsCorrectionsAndCompletions() {
        SearchResult r = engine.search("kotiln coroutines", 1, 10, FAR);
        assertThat(r.totalHits()).isZero();
        assertThat(r.didYouMean()).isEqualTo("kotlin coroutines");
        // "pythn" has no hits; "streem" matches nothing either: both get corrected
        assertThat(engine.search("pythn streem", 1, 10, FAR).didYouMean())
                .isEqualTo("python stream");
        // a stopword is left alone
        assertThat(engine.search("the kotlin", 1, 10, FAR).didYouMean()).isNull();
        assertThat(engine.suggest("ja", 5)).contains("java");
        assertThat(engine.suggest("learn str", 5)).contains("learn streams");
        assertThat(engine.index().docCount()).isEqualTo(6);
    }

    @Test
    void emptyQueriesAndLimits() {
        assertThat(engine.search("the and of", 1, 10, FAR).totalHits()).isZero();
        assertThat(engine.search("", 1, 10, FAR).results()).isEmpty();
        assertThatThrownBy(() -> engine.search("java", 0, 10, FAR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.search("java", 101, 10, FAR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforcesTheDeadline() {
        long past = System.nanoTime() - 1_000_000;
        assertThatThrownBy(() -> engine.search("java", 1, 10, past))
                .isInstanceOf(QueryTimeoutException.class);
        assertThatThrownBy(() -> engine.search("\"java streams\"", 1, 10, past))
                .isInstanceOf(QueryTimeoutException.class);
    }

    @Test
    void bm25Basics() {
        Bm25 bm = new Bm25(engine.index());
        assertThat(bm.idf(1)).isGreaterThan(bm.idf(5));
        assertThat(bm.termScore(0, 1, new int[] {0, 0, 0})).isZero();
        assertThat(bm.termScore(0, 1, new int[] {1, 0, 0}))
                .isGreaterThan(bm.termScore(0, 1, new int[] {0, 0, 1}));
        assertThat(bm.prior(0)).isGreaterThan(bm.prior(1));
    }
}
