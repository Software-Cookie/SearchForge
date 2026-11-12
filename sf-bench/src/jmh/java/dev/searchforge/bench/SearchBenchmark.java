package dev.searchforge.bench;

import dev.searchforge.indexer.Index;
import dev.searchforge.query.SearchEngine;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

// Latency of search, autocomplete and spelling on a real index.
// Run: .\gradlew.bat :sf-bench:jmh -Psearchforge.index=data/index/<version>
// (defaults to data/index/demo). Results: sf-bench/build/results/jmh/results.json
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class SearchBenchmark {

    @Param({"crawler politeness", "inverted index compression", "\"page rank\"",
        "bm25 OR tfidf"})
    public String query;

    private SearchEngine engine;

    @Setup
    public void setUp() throws IOException {
        String dir = System.getProperty("searchforge.index", "data/index/demo");
        engine = new SearchEngine(Index.open(Path.of(dir)));
    }

    @Benchmark
    public Object search() {
        return engine.search(query, 1, 10, System.nanoTime() + 1_000_000_000L);
    }

    @Benchmark
    public Object autocomplete() {
        return engine.suggest(query.substring(0, Math.min(4, query.length())), 8);
    }

    @Benchmark
    public Object didYouMean() {
        return engine.search("crawlr pagernak", 1, 10, System.nanoTime() + 1_000_000_000L);
    }
}
