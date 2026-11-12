package dev.searchforge.api;

import dev.searchforge.indexer.Index;
import dev.searchforge.query.SearchEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;

// Loads the index once at startup. A missing or broken index fails startup on purpose:
// the health check then never turns green, so a bad deploy is never sent traffic.
@Configuration(proxyBeanMethods = false)
public class EngineConfig {

    private static final Logger LOG = LoggerFactory.getLogger(EngineConfig.class);

    @Bean
    public SearchEngine searchEngine(SearchForgeProperties props) throws IOException {
        if (!Files.isRegularFile(props.indexDir().resolve("meta.properties"))) {
            throw new IllegalStateException("No index found at "
                    + props.indexDir().toAbsolutePath() + ". Build one first (README: 'Run "
                    + "locally') or set SEARCHFORGE_INDEX_DIR.");
        }
        long start = System.nanoTime();
        Index index = Index.open(props.indexDir(), props.memoryMap());
        SearchEngine engine = new SearchEngine(index);
        LOG.info("Loaded index {}: {} docs, {} terms, {} vocabulary words in {} ms",
                props.indexDir(), index.docCount(), index.termCount(), index.vocabulary().size(),
                (System.nanoTime() - start) / 1_000_000);
        return engine;
    }
}
