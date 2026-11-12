package dev.searchforge.core.page;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageStoreTest {

    @TempDir
    Path dir;

    @Test
    void roundTripsRecordsIncludingUnicodeAndLargeBodies() throws IOException {
        PageRecord a = new PageRecord("https://a.com/", "Título ☕", "H1 H2", "body text",
                List.of("https://a.com/x", "https://b.com/"), 42L);
        PageRecord b = new PageRecord("https://b.com/", null, null, "x".repeat(200_000),
                null, 7L);
        try (PageStore store = PageStore.openForAppend(dir)) {
            store.append(a);
            store.append(b);
            assertThat(store.count()).isEqualTo(2);
        }
        List<PageRecord> back = PageStore.readAll(dir);
        assertThat(back).containsExactly(a, b);
        assertThat(back.get(1).title()).isEmpty();
        assertThat(back.get(1).outlinks()).isEmpty();
    }

    @Test
    void resumesAfterTornWrite() throws IOException {
        try (PageStore store = PageStore.openForAppend(dir)) {
            store.append(page("https://a.com/1"));
            store.append(page("https://a.com/2"));
        }
        Path file = dir.resolve(PageStore.FILE_NAME);
        long size = Files.size(file);
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(size - 3); // simulate a crash mid-record
        }
        assertThat(PageStore.readAll(dir)).hasSize(1);

        try (PageStore store = PageStore.openForAppend(dir)) {
            assertThat(store.count()).isEqualTo(1);
            store.append(page("https://a.com/3"));
            store.flush();
        }
        assertThat(PageStore.readAll(dir)).extracting(PageRecord::url)
                .containsExactly("https://a.com/1", "https://a.com/3");
    }

    @Test
    void detectsCorruptedPayloadByChecksum() throws IOException {
        try (PageStore store = PageStore.openForAppend(dir)) {
            store.append(page("https://a.com/1"));
        }
        Path file = dir.resolve(PageStore.FILE_NAME);
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.seek(12);
            raf.write(0x55);
        }
        assertThat(PageStore.readAll(dir)).isEmpty();
    }

    @Test
    void missingStoreReadsAsEmpty() throws IOException {
        assertThat(PageStore.readAll(dir.resolve("nothing"))).isEmpty();
    }

    private static PageRecord page(String url) {
        return new PageRecord(url, "t", "h", "b", List.of(), 1L);
    }
}
