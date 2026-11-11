package io.github.lakernote.meerkat.capture.internal;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaptureSessionsTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final CaptureSessions sessions = new CaptureSessions(Duration.ofMinutes(30), 100, clock);

    private static CaptureRule rule(String method, String path, int count) {
        return new CaptureRule(method, path, null, null, count, 60);
    }

    @Test
    void startsSessionAndReportsItAsActive() {
        assertThat(sessions.anyActive()).isFalse();
        assertThat(sessions.match("GET", "/api/orders", name -> null)).isNull();

        CaptureSessions.SessionView view = sessions.start(rule(" get ", " /api/** ", 5), "alice");

        assertThat(sessions.anyActive()).isTrue();
        assertThat(view.id()).isEqualTo("1");
        assertThat(view.active()).isTrue();
        assertThat(view.operator()).isEqualTo("alice");
        assertThat(view.rule().method()).isEqualTo("GET");
        assertThat(view.rule().pathPattern()).isEqualTo("/api/**");
        assertThat(view.startedAt()).isEqualTo(clock.instant());
        assertThat(view.expiresAt()).isEqualTo(clock.instant().plusSeconds(60));
        assertThat(view.endedAt()).isNull();
        assertThat(view.capturedCount()).isZero();
        assertThat(sessions.maxDurationSeconds()).isEqualTo(1800);
    }

    @Test
    void matchesMethodPathAndHeader() {
        sessions.start(new CaptureRule("POST", "/api/orders/*", "X-User", "tester", 10, 60), "ops");
        Map<String, String> headers = Map.of("X-User", "tester");

        assertThat(sessions.match("GET", "/api/orders/1", headers::get)).isNull();
        assertThat(sessions.match("POST", "/api/users/1", headers::get)).isNull();
        assertThat(sessions.match("POST", null, headers::get)).isNull();
        assertThat(sessions.match("POST", "/api/orders/1", name -> null)).isNull();
        assertThat(sessions.match("POST", "/api/orders/1", name -> "someone-else")).isNull();

        CaptureSessions.Match match = sessions.match("post", "/api/orders/1", headers::get);

        assertThat(match).isEqualTo(new CaptureSessions.Match("1", 1));
        assertThat(sessions.list()).singleElement().satisfies(view -> assertThat(view.capturedCount()).isEqualTo(1));
    }

    @Test
    void headerNameWithoutValueAcceptsAnyValueAndBlankMethodAcceptsAnyMethod() {
        sessions.start(new CaptureRule("", "/api/**", "X-Debug", "", 10, 60), "ops");

        assertThat(sessions.match("DELETE", "/api/x", name -> "anything")).isNotNull();
        assertThat(sessions.match("DELETE", "/api/x", name -> null)).isNull();
    }

    @Test
    void finishesSessionAfterMaxCount() {
        sessions.start(rule("GET", "/api/**", 2), "ops");

        assertThat(sessions.match("GET", "/api/a", name -> null).sequence()).isEqualTo(1);
        assertThat(sessions.anyActive()).isTrue();
        assertThat(sessions.match("GET", "/api/b", name -> null).sequence()).isEqualTo(2);

        assertThat(sessions.anyActive()).isFalse();
        assertThat(sessions.match("GET", "/api/c", name -> null)).isNull();
        CaptureSessions.SessionView view = sessions.list().get(0);
        assertThat(view.active()).isFalse();
        assertThat(view.capturedCount()).isEqualTo(2);
        assertThat(view.endReason()).isEqualTo("reached 2 requests");
        assertThat(view.endedAt()).isEqualTo(clock.instant());
    }

    @Test
    void nextSessionTakesOverWhenFirstDoesNotMatch() {
        sessions.start(rule("GET", "/a/**", 5), "ops");
        sessions.start(rule("GET", "/b/**", 5), "ops");

        assertThat(sessions.match("GET", "/b/1", name -> null).sessionId()).isEqualTo("2");
    }

    @Test
    void expiresSessionsOnMatchAndList() {
        sessions.start(rule("GET", "/api/**", 5), "ops");
        clock.advance(Duration.ofSeconds(60));

        assertThat(sessions.match("GET", "/api/a", name -> null)).isNull();

        assertThat(sessions.anyActive()).isFalse();
        CaptureSessions.SessionView view = sessions.list().get(0);
        assertThat(view.active()).isFalse();
        assertThat(view.endReason()).isEqualTo("expired");
    }

    @Test
    void listExpiresSessionsAndShowsNewestFirst() {
        sessions.start(rule("GET", "/a/**", 5), "ops");
        clock.advance(Duration.ofSeconds(30));
        sessions.start(new CaptureRule("GET", "/b/**", null, null, 5, 600), "ops");
        clock.advance(Duration.ofSeconds(31));

        List<CaptureSessions.SessionView> views = sessions.list();

        assertThat(views).extracting(CaptureSessions.SessionView::id).containsExactly("2", "1");
        assertThat(views).extracting(CaptureSessions.SessionView::active).containsExactly(true, false);
        assertThat(sessions.anyActive()).isTrue();
    }

    @Test
    void stopsActiveSessionOnlyOnce() {
        sessions.start(rule("GET", "/api/**", 5), "ops");

        assertThat(sessions.stop("missing", "ops")).isFalse();
        assertThat(sessions.stop("1", "bob")).isTrue();
        assertThat(sessions.stop("1", "bob")).isFalse();

        assertThat(sessions.anyActive()).isFalse();
        assertThat(sessions.list().get(0).endReason()).isEqualTo("stopped by bob");
    }

    @Test
    void sanitizesOperator() {
        assertThat(sessions.start(rule("GET", "/a", 1), null).operator()).isEqualTo("anonymous");
        assertThat(sessions.start(rule("GET", "/a", 1), "  ").operator()).isEqualTo("anonymous");
        assertThat(sessions.start(rule("GET", "/a", 1), "eve\r\nINJECTED\t").operator()).isEqualTo("eve  INJECTED");
        assertThat(sessions.start(rule("GET", "/a", 1), "x".repeat(300)).operator()).hasSize(200);
    }

    @Test
    void limitsActiveSessions() {
        for (int i = 0; i < CaptureSessions.MAX_ACTIVE_SESSIONS; i++) sessions.start(rule("GET", "/a", 1), "ops");

        assertThatThrownBy(() -> sessions.start(rule("GET", "/a", 1), "ops"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("At most 10");

        sessions.stop("1", "ops");
        assertThat(sessions.start(rule("GET", "/a", 1), "ops").active()).isTrue();
    }

    @Test
    void keepsOnlyRecentFinishedSessions() {
        for (int i = 0; i < 60; i++) {
            CaptureSessions.SessionView view = sessions.start(rule("GET", "/a", 1), "ops");
            sessions.stop(view.id(), "ops");
        }
        sessions.start(rule("GET", "/a", 1), "ops");

        List<CaptureSessions.SessionView> views = sessions.list();
        assertThat(views).hasSize(51);
        assertThat(views.get(0).id()).isEqualTo("61");
        assertThat(views.get(0).active()).isTrue();
    }

    @Test
    void rejectsInvalidRules() {
        assertThatThrownBy(() -> sessions.start(rule("GET", "api", 1), "ops"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sessions.start(new CaptureRule("GET", "/a", null, null, 1, 1801), "ops"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1800");
        assertThat(sessions.anyActive()).isFalse();
    }

    @Test
    void defaultsInvalidMaxDurationToOneHour() {
        assertThat(new CaptureSessions(null, 100).maxDurationSeconds()).isEqualTo(3600);
        assertThat(new CaptureSessions(Duration.ZERO, 100).maxDurationSeconds()).isEqualTo(3600);
        assertThat(new CaptureSessions(Duration.ofSeconds(-5), 100).maxDurationSeconds()).isEqualTo(3600);
        assertThat(new CaptureSessions(Duration.ofMinutes(5), 100).maxDurationSeconds()).isEqualTo(300);
    }

    @Test
    void capsTheCountAtWhatTheStoreKeeps() {
        assertThat(sessions.maxCount()).isEqualTo(100);
        assertThat(new CaptureSessions(null, 5_000).maxCount()).isEqualTo(CaptureRule.MAX_COUNT);
        assertThatThrownBy(() -> sessions.start(new CaptureRule("", "/a", "", "", 101, 60), "ops"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("between 1 and 100");
    }
}
