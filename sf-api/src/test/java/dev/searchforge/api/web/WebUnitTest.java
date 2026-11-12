package dev.searchforge.api.web;

import dev.searchforge.api.SearchForgeProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebUnitTest {

    @Test
    void tokenBucketAllowsBurstThenRefills() {
        AtomicLong now = new AtomicLong();
        TokenBucket b = new TokenBucket(2, 10, now::get);
        assertThat(b.tryConsume()).isTrue();
        assertThat(b.tryConsume()).isTrue();
        assertThat(b.tryConsume()).isFalse();
        now.addAndGet(100_000_000); // 100 ms = 1 token at 10/s
        assertThat(b.tryConsume()).isTrue();
        assertThat(b.tryConsume()).isFalse();
        now.addAndGet(10_000_000_000L); // long idle: capped at capacity
        assertThat(b.tryConsume()).isTrue();
        assertThat(b.tryConsume()).isTrue();
        assertThat(b.tryConsume()).isFalse();
    }

    @Test
    void clientIpTrustsOnlyTheConfiguredProxies() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.1");
        assertThat(ClientIp.from(req, 1)).isEqualTo("10.0.0.1");
        req.addHeader("X-Forwarded-For", "6.6.6.6, 5.5.5.5, 1.2.3.4");
        assertThat(ClientIp.from(req, 0)).isEqualTo("10.0.0.1");
        assertThat(ClientIp.from(req, 1)).isEqualTo("1.2.3.4");
        assertThat(ClientIp.from(req, 2)).isEqualTo("5.5.5.5");
        assertThat(ClientIp.from(req, 9)).isEqualTo("6.6.6.6");

        MockHttpServletRequest odd = new MockHttpServletRequest();
        odd.setRemoteAddr("10.0.0.2");
        odd.addHeader("X-Forwarded-For", "1.1.1.1, " + "x".repeat(100));
        assertThat(ClientIp.from(odd, 1)).isEqualTo("10.0.0.2");
        MockHttpServletRequest blank = new MockHttpServletRequest();
        blank.setRemoteAddr("10.0.0.3");
        blank.addHeader("X-Forwarded-For", " ");
        assertThat(ClientIp.from(blank, 1)).isEqualTo("10.0.0.3");
    }

    @Test
    void securityHeadersAndStaticCaching() throws Exception {
        SecurityHeadersFilter f = new SecurityHeadersFilter();
        MockHttpServletResponse asset = run(f, "/assets/index-abc123.js", "https");
        assertThat(asset.getHeader("Cache-Control")).contains("immutable");
        assertThat(asset.getHeader("Strict-Transport-Security")).isNotNull();
        assertThat(asset.getHeader("Content-Security-Policy")).contains("frame-ancestors 'none'");
        MockHttpServletResponse index = run(f, "/", null);
        assertThat(index.getHeader("Cache-Control")).isEqualTo("no-cache");
        assertThat(index.getHeader("Strict-Transport-Security")).isNull();
        assertThat(run(f, "/api/search", null).getHeader("Cache-Control")).isNull();
    }

    @Test
    void rateLimitFilterReturnsProblemJsonWith429() throws Exception {
        SearchForgeProperties props = new SearchForgeProperties(Path.of("x"), false, 200, 500,
                1, 1, 10, 0);
        RateLimitFilter f = new RateLimitFilter(props, new SimpleMeterRegistry());
        assertThat(run(f, "/api/search", null).getStatus()).isEqualTo(200);
        MockHttpServletResponse limited = run(f, "/api/search", null);
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isEqualTo("1");
        assertThat(limited.getContentAsString()).contains("Too many searches");
        assertThat(run(f, "/index.html", null).getStatus()).isEqualTo(200); // not limited
    }

    @Test
    void propertiesRejectNonsense() {
        assertThatThrownBy(() -> new SearchForgeProperties(null, true, 200, 500, 10, 20, 10, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SearchForgeProperties(Path.of("x"), true, 0, 500, 10, 20,
                10, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static MockHttpServletResponse run(jakarta.servlet.Filter f, String uri,
                                               String proto) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRemoteAddr("7.7.7.7");
        if (proto != null) {
            req.addHeader("X-Forwarded-Proto", proto);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, new MockFilterChain());
        return res;
    }
}
