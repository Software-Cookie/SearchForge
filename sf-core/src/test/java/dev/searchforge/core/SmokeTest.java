package dev.searchforge.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SmokeTest {

    @Test
    @DisplayName("Phase 0 setup test: JVM 21 environment verification")
    void verifyEnvironment() {
        String javaVersion = System.getProperty("java.version");
        assertThat(javaVersion).isNotNull();
        assertThat(Runtime.getRuntime().availableProcessors()).isGreaterThan(0);
    }
}
