package dev.searchforge.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

// Bound from the "searchforge.*" keys in application.yml (env vars override them).
// trustedProxyHops: how many proxies in front of the app append to X-Forwarded-For
// (0 = ignore the header, e.g. locally; 1 = Render's proxy).
@ConfigurationProperties("searchforge")
public record SearchForgeProperties(
        Path indexDir,
        boolean memoryMap,
        int maxQueryLength,
        int queryTimeoutMs,
        int rateLimitPerSecond,
        int rateLimitBurst,
        int cacheSize,
        int trustedProxyHops) {

    public SearchForgeProperties {
        if (indexDir == null) {
            throw new IllegalArgumentException("searchforge.index-dir must be set");
        }
        if (maxQueryLength < 1 || queryTimeoutMs < 1 || rateLimitPerSecond < 1
                || rateLimitBurst < 1 || cacheSize < 0 || trustedProxyHops < 0) {
            throw new IllegalArgumentException("Invalid searchforge.* settings");
        }
    }
}
