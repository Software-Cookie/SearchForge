package dev.searchforge.indexer;

import dev.searchforge.core.page.PageRecord;
import dev.searchforge.core.page.PageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexerMainTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);

    @Test
    void indexesACrawlWithLinksAndSkipsRepeatedUrls() throws IOException {
        Path crawl = dir.resolve("crawl");
        try (PageStore store = PageStore.openForAppend(crawl)) {
            store.append(new PageRecord("https://a/", "Home", "", "home page about search",
                    List.of("https://a/x", "https://elsewhere/"), 1));
            store.append(new PageRecord("https://a/x", "X", "", "x page links home",
                    List.of("https://a/"), 1));
            store.append(new PageRecord("https://a/", "Home again", "", "dup", List.of(), 2));
        }
        Path index = dir.resolve("index");
        int code = IndexerMain.run(new String[] {"--in", crawl.toString(), "--out",
            index.toString(), "--memory-mb", "1"}, out);
        assertThat(code).isZero();
        assertThat(buf.toString(StandardCharsets.UTF_8)).contains("docs=2", "compression=");
        Index idx = Index.open(index, false);
        assertThat(idx.docCount()).isEqualTo(2);
        assertThat(idx.doc(0).title()).isEqualTo("Home");
        assertThat(idx.pageRank(0) + idx.pageRank(1)).isBetween(0.99f, 1.01f);

        // the output directory is never reused
        assertThat(IndexerMain.run(new String[] {"--in", crawl.toString(), "--out",
            index.toString()}, out)).isEqualTo(2);
    }

    @Test
    void indexesMarkdownSections() throws IOException {
        Path md = Files.createDirectories(dir.resolve("md"));
        Files.writeString(md.resolve("01-guide.md"), """
                # SearchForge Guide
                Intro text that is long enough to become its own searchable section here.
                ## The Crawl Engine!
                The crawler uses a **frontier** with [politeness](https://x.y) rules and
                `robots.txt` parsing. ![diagram](d.png) <b>bold</b> +-----+ ====> ┌──┐
                ##### Sub heading
                ```java
                class Frontier {}
                ```
                ## Tiny
                too short
                ## The Crawl Engine!
                duplicate heading with enough text to pass the minimum length check.
                """);
        Files.writeString(md.resolve("notes.txt"), "ignored");
        List<PageRecord> pages = MarkdownCorpus.read(md, "https://github.com/o/r/blob/main/");
        assertThat(pages).extracting(PageRecord::url).containsExactly(
                "https://github.com/o/r/blob/main/01-guide.md#searchforge-guide",
                "https://github.com/o/r/blob/main/01-guide.md#the-crawl-engine");
        PageRecord crawl = pages.get(1);
        assertThat(crawl.title()).isEqualTo("The Crawl Engine!");
        assertThat(crawl.headings()).isEqualTo("Sub heading.");
        assertThat(crawl.body()).contains("frontier", "politeness", "diagram", "class Frontier")
                .doesNotContain("**", "https://x.y", "<b>", "```", "---", "==", "┌");

        int code = IndexerMain.run(new String[] {"--markdown", md.toString(), "--base-url",
            "https://example/", "--out", dir.resolve("demo").toString()}, out);
        assertThat(code).isZero();
        assertThat(Index.open(dir.resolve("demo"), false).docFreq("frontier")).isEqualTo(1);
    }

    @Test
    void validatesArguments() throws IOException {
        assertThat(IndexerMain.run(new String[] {}, out)).isEqualTo(2);
        assertThat(IndexerMain.run(new String[] {"--in", "a"}, out)).isEqualTo(2);
        assertThat(IndexerMain.run(new String[] {"--in", "a", "--markdown", "b", "--out", "c"},
                out)).isEqualTo(2);
        assertThat(IndexerMain.run(new String[] {"in", "a", "--out", "c"}, out)).isEqualTo(2);
        assertThat(IndexerMain.run(new String[] {"--in", "a", "--out"}, out)).isEqualTo(2);
        assertThat(buf.toString(StandardCharsets.UTF_8)).contains("Usage");
        assertThatThrownBy(() -> IndexerMain.run(new String[] {"--in",
            dir.resolve("empty").toString(), "--out", dir.resolve("o").toString()}, out))
                .isInstanceOf(IOException.class).hasMessageContaining("No pages");
    }
}
