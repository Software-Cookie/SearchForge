package dev.searchforge.api;

import dev.searchforge.query.QueryTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void mapsErrorsToSafeProblemResponses() {
        assertThat(handler.timeout(new QueryTimeoutException()).getStatusCode().value())
                .isEqualTo(503);
        var internal = handler.unexpected(new IllegalStateException("secret internals"));
        assertThat(internal.getStatusCode().value()).isEqualTo(500);
        assertThat(internal.getBody().getDetail()).isEqualTo("Something went wrong");
        assertThat(handler.notFound(new NoResourceFoundException(HttpMethod.GET, "/x", "x"))
                .getStatusCode().value()).isEqualTo(404);
    }
}
