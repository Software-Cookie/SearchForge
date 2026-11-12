package dev.searchforge.api;

import dev.searchforge.core.page.PageRecord;
import dev.searchforge.indexer.IndexBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

// Builds one small index for all API tests (once per JVM).
final class TestIndex {

    private static Path dir;

    private TestIndex() {
    }

    static synchronized Path dir() {
        if (dir == null) {
            try {
                Path d = Files.createTempDirectory("sf-api-test").resolve("index");
                try (IndexBuilder b = new IndexBuilder(d, 1 << 20)) {
                    for (PageRecord p : pages()) {
                        b.add(p, new int[0]);
                    }
                    b.finish();
                }
                dir = d;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return dir;
    }

    private static List<PageRecord> pages() {
        return List.of(
                new PageRecord("https://docs.example/java-streams", "Java Streams", "",
                        "Java streams process <script>alert(1)</script> sequences of elements.",
                        List.of(), 0),
                new PageRecord("https://docs.example/python", "Python Lists", "",
                        "Python list comprehensions build lists quickly.", List.of(), 0),
                new PageRecord("https://docs.example/kotlin", "Kotlin", "",
                        "Kotlin coroutines and Java interop.", List.of(), 0));
    }
}
