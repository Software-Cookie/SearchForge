package dev.searchforge.indexer;

import dev.searchforge.core.page.PageRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexTest {

    @TempDir
    Path dir;

    static List<PageRecord> corpus() {
        List<PageRecord> pages = new ArrayList<>();
        pages.add(new PageRecord("https://d/0", "Java Streams", "Collectors",
                "A stream is a sequence of elements supporting aggregate operations.", List.of(),
                0));
        pages.add(new PageRecord("https://d/1", "Python lists", "Comprehensions",
                "List comprehensions build lists. Streams are Java, lists are Python.",
                List.of(), 0));
        pages.add(new PageRecord("https://d/2", "", "", "", List.of(), 0));
        for (int i = 3; i < 60; i++) {
            pages.add(new PageRecord("https://d/" + i, "Page " + i, "",
                    "filler words number " + i + " streaming data pipeline", List.of(), 0));
        }
        return pages;
    }

    private static int[] linksFor(int i) {
        return i == 0 ? new int[0] : new int[] {0}; // everyone links to doc 0
    }

    private Index build(Path out, long budget) throws IOException {
        List<PageRecord> pages = corpus();
        try (IndexBuilder b = new IndexBuilder(out, budget)) {
            for (int i = 0; i < pages.size(); i++) {
                assertThat(b.add(pages.get(i), linksFor(i))).isEqualTo(i);
            }
            IndexBuilder.Stats stats = b.finish();
            assertThat(stats.docs()).isEqualTo(60);
            assertThat(stats.compressionRatio()).isPositive();
            if (budget < 1000) {
                assertThat(stats.runs()).isGreaterThan(1);
            } else {
                assertThat(stats.runs()).isEqualTo(1);
            }
        }
        assertThat(out.resolve("tmp")).doesNotExist();
        return Index.open(out, false);
    }

    @Test
    void buildsAndReadsBackPostingsNormsDocsAndPageRank() throws IOException {
        Index idx = build(dir.resolve("one"), 64L * 1024 * 1024);
        assertThat(idx.docCount()).isEqualTo(60);
        assertThat(idx.termCount()).isGreaterThan(10);
        assertThat(idx.docFreq("stream")).isEqualTo(59); // stream + streaming, not doc 2
        assertThat(idx.docFreq("nosuchterm")).isZero();
        assertThat(idx.postings("nosuchterm")).isNull();

        PostingList java = idx.postings("java");
        assertThat(java.docIds()).containsExactly(0, 1);
        assertThat(java.tf(0, 0)).isEqualTo(1); // title of doc 0
        assertThat(java.tf(1, 2)).isEqualTo(1); // body of doc 1
        assertThat(java.positions(0)).containsExactly(0);

        PostingList list = idx.postings("list");
        assertThat(list.docIds()).containsExactly(1);
        assertThat(list.tf(0, 0) + list.tf(0, 1) + list.tf(0, 2)).isEqualTo(4);
        int[] pos = list.positions(0);
        assertThat(pos).hasSize(4).isSorted();

        assertThat(idx.doc(1).url()).isEqualTo("https://d/1");
        assertThat(idx.doc(1).title()).isEqualTo("Python lists");
        assertThat(idx.doc(1).text()).startsWith("List comprehensions");
        assertThat(idx.doc(59).url()).isEqualTo("https://d/59");
        assertThatThrownBy(() -> idx.doc(60)).isInstanceOf(IllegalArgumentException.class);

        assertThat(idx.fieldLength(0, 0)).isEqualTo(2);
        assertThat(idx.fieldLength(2, 2)).isZero();
        assertThat(idx.avgFieldLength(2)).isGreaterThan(1);
        assertThat(idx.pageRank(0)).isGreaterThan(idx.pageRank(5) * 10);
        assertThat(idx.meta().getProperty("docCount")).isEqualTo("60");
        assertThat(idx.vocabulary()).extracting(Index.VocabEntry::word)
                .contains("streaming", "java", "comprehensions").doesNotContain("the", "3");
        assertThat(idx.vocabulary().get(0).docFreq()).isGreaterThanOrEqualTo(57);
    }

    @Test
    void multiRunExternalMergeGivesTheSameIndexAsOneRun() throws IOException {
        Index one = build(dir.resolve("one"), 64L * 1024 * 1024);
        Index many = build(dir.resolve("many"), 200); // spills after nearly every doc
        assertThat(Files.mismatch(dir.resolve("one/postings.bin"),
                dir.resolve("many/postings.bin"))).isEqualTo(-1L);
        assertThat(Files.mismatch(dir.resolve("one/terms.dict"),
                dir.resolve("many/terms.dict"))).isEqualTo(-1L);
        assertThat(many.postings("stream").docIds()).containsExactly(
                one.postings("stream").docIds());
    }

    @Test
    void memoryMappedOpenWorks() throws IOException {
        // Outside @TempDir: on Windows a mapped file cannot be deleted while mapped.
        Path out = Files.createTempDirectory(Path.of("build"), "mmap-index");
        build(out.resolve("idx"), 64L * 1024 * 1024);
        Index mapped = Index.open(out.resolve("idx"));
        assertThat(mapped.postings("java").docIds()).containsExactly(0, 1);
        assertThat(mapped.doc(0).title()).isEqualTo("Java Streams");
    }

    @Test
    void rejectsUnknownFormatVersion() throws IOException {
        build(dir.resolve("v"), 64L * 1024 * 1024);
        Path meta = dir.resolve("v").resolve(IndexFiles.META);
        Files.writeString(meta, Files.readString(meta)
                .replace("formatVersion=1", "formatVersion=99"));
        assertThatThrownBy(() -> Index.open(dir.resolve("v"), false))
                .isInstanceOf(IOException.class).hasMessageContaining("Unsupported");
    }

    @Test
    void intListGrowsAndChecksBounds() {
        IntList l = new IntList(1);
        for (int i = 0; i < 100; i++) {
            l.add(i);
        }
        assertThat(l.size()).isEqualTo(100);
        assertThat(l.toArray()[99]).isEqualTo(99);
        assertThatThrownBy(() -> l.get(100)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThat(new IntList().size()).isZero();
    }
}
