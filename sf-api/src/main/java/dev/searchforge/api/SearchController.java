package dev.searchforge.api;

import dev.searchforge.indexer.Index;
import dev.searchforge.query.SearchEngine;
import dev.searchforge.query.SearchResult;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api")
public class SearchController {

    public record SuggestResponse(String query, List<String> suggestions) {
    }

    public record StatsResponse(int documents, int terms, int vocabulary, String indexCreatedAt) {
    }

    static final int MAX_PAGE = 100;
    static final int MAX_SIZE = 50;
    private static final CacheControl BROWSER_CACHE =
            CacheControl.maxAge(Duration.ofMinutes(1)).cachePrivate();

    private final SearchService service;
    private final SearchForgeProperties props;

    public SearchController(SearchService service, SearchForgeProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/search")
    public ResponseEntity<SearchResult> search(@RequestParam("q") String q,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "10") int size) {
        String query = validQuery(q);
        if (page < 1 || page > MAX_PAGE || size < 1 || size > MAX_SIZE
                || page * size > SearchEngine.MAX_RESULT_WINDOW) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "page must be 1-" + MAX_PAGE + ", size 1-" + MAX_SIZE
                            + ", and page x size at most " + SearchEngine.MAX_RESULT_WINDOW);
        }
        return ResponseEntity.ok().cacheControl(BROWSER_CACHE)
                .body(service.search(query, page, size));
    }

    @GetMapping("/suggest")
    public ResponseEntity<SuggestResponse> suggest(@RequestParam("q") String q) {
        String query = q.length() > props.maxQueryLength() ? "" : q;
        return ResponseEntity.ok().cacheControl(BROWSER_CACHE)
                .body(new SuggestResponse(q, query.isBlank() ? List.of()
                        : service.suggest(query)));
    }

    @GetMapping("/stats")
    public StatsResponse stats() {
        Index index = service.engine().index();
        return new StatsResponse(index.docCount(), index.termCount(), index.vocabulary().size(),
                index.meta().getProperty("createdAt", ""));
    }

    private String validQuery(String q) {
        String query = q.strip();
        if (query.isEmpty() || query.length() > props.maxQueryLength()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "q must be 1-" + props.maxQueryLength() + " characters");
        }
        return query;
    }
}
