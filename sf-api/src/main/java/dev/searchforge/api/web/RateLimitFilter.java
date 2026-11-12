package dev.searchforge.api.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.searchforge.api.SearchForgeProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

// Per-IP token bucket on /api/*. Buckets live in a bounded Caffeine cache, so a flood of
// distinct IPs cannot exhaust memory. State is per instance (fine for one instance; see
// ADR-002 for what changes with several).
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private final Cache<String, TokenBucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .maximumSize(50_000)
            .build();
    private final SearchForgeProperties props;
    private final Counter rejected;

    public RateLimitFilter(SearchForgeProperties props, MeterRegistry registry) {
        this.props = props;
        this.rejected = Counter.builder("searchforge.ratelimit.rejected").register(registry);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String ip = ClientIp.from(req, props.trustedProxyHops());
        TokenBucket bucket = buckets.get(ip, k -> new TokenBucket(props.rateLimitBurst(),
                props.rateLimitPerSecond(), System::nanoTime));
        if (!bucket.tryConsume()) {
            rejected.increment();
            res.setStatus(429);
            res.setHeader("Retry-After", "1");
            res.setContentType("application/problem+json");
            res.getWriter().write("{\"status\":429,\"title\":\"Too Many Requests\","
                    + "\"detail\":\"Too many searches. Wait a second and try again.\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
