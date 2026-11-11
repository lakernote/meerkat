package io.github.lakernote.meerkat.web.internal;

import io.github.lakernote.meerkat.diagnostics.internal.LiveDiagnostics;
import io.github.lakernote.meerkat.web.ObservabilityAccessPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveDiagnosticsControllerTest {
    private final LiveDiagnosticsController controller = new LiveDiagnosticsController(() -> true);

    @Test
    void servesThePageAndRedirectsToTheTrailingSlash() throws Exception {
        assertThat(controller.livePage().getBody().exists()).isTrue();
        assertThat(controller.livePage().getHeaders().getContentType()).isEqualTo(MediaType.TEXT_HTML);
        ResponseEntity<Void> redirect = controller.redirectToLive();
        assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.PERMANENT_REDIRECT);
        assertThat(redirect.getHeaders().getLocation()).hasToString("live/");
    }

    @Test
    void returnsRequestsThreadsAndADownloadableDump() throws Exception {
        assertThat(controller.requests().items()).isNotNull();
        assertThat(controller.threads(0).threads()).isNotEmpty();
        ResponseEntity<String> dump = controller.threadDump();
        assertThat(dump.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("text/plain;charset=UTF-8"));
        assertThat(dump.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .matches("attachment; filename=\"threads-\\d{8}-\\d{6}\\.txt\"");
        assertThat(dump.getBody()).contains("java.lang.Thread.State");
    }

    @Test
    void rejectsInvalidSampleDurations() {
        assertThatThrownBy(() -> controller.threads(-1)).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> controller.threads(5001)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void answersTooManyRequestsWhileAnotherSampleRuns() throws Exception {
        LiveDiagnostics busy = new LiveDiagnostics();
        LiveDiagnosticsController guarded = new LiveDiagnosticsController(busy, () -> true);
        Thread sampler = new Thread(() -> {
            try {
                busy.threads(java.time.Duration.ofMillis(800));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        sampler.start();
        Thread.sleep(200);
        assertThatThrownBy(() -> guarded.threads(0)).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        sampler.join(5000);
    }

    @Test
    void requiresViewAccess() {
        ObservabilityAccessPolicy denied = () -> false;
        LiveDiagnosticsController forbidden = new LiveDiagnosticsController(denied);
        assertThatThrownBy(forbidden::requests).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> forbidden.threads(0)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(forbidden::threadDump).isInstanceOf(ResponseStatusException.class);
        assertThat(forbidden.livePage().getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
