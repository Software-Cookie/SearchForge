package dev.searchforge.api.websearch;

import dev.searchforge.api.SearchForgeProperties;
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
import java.util.Map;

// Live internet search: /api/web?q=house&type=web|images&page=1 and /api/web/suggest?q=hou
@RestController
@RequestMapping("/api/web")
public class WebSearchController {

    static final int MAX_PAGE = 10;
    private static final CacheControl BROWSER_CACHE =
            CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate();

    private final WebSearchService service;
    private final SearchForgeProperties props;

    public WebSearchController(WebSearchService service, SearchForgeProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping
    public ResponseEntity<WebSearchResponse> search(@RequestParam("q") String q,
                                                    @RequestParam(defaultValue = "web") String type,
                                                    @RequestParam(defaultValue = "1") int page) {
        String query = q.strip();
        if (query.isEmpty() || query.length() > props.maxQueryLength()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "q must be 1-" + props.maxQueryLength() + " characters");
        }
        if (!type.equals("web") && !type.equals("images")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type must be web or images");
        }
        if (page < 1 || page > MAX_PAGE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be 1-" + MAX_PAGE);
        }
        return ResponseEntity.ok().cacheControl(BROWSER_CACHE)
                .body(service.search(query, type, page));
    }

    @GetMapping("/suggest")
    public ResponseEntity<Map<String, Object>> suggest(@RequestParam("q") String q) {
        List<String> s = q.length() > props.maxQueryLength() ? List.of() : service.suggest(q);
        return ResponseEntity.ok().cacheControl(BROWSER_CACHE)
                .body(Map.of("query", q, "suggestions", s));
    }

    @GetMapping("/info")
    public Map<String, String> info() {
        return Map.of("provider", service.providerName());
    }
}
