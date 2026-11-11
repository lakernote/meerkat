package io.github.lakernote.meerkat.web.internal;

import io.github.lakernote.meerkat.capture.internal.CaptureRecord;
import io.github.lakernote.meerkat.capture.internal.CaptureRule;
import io.github.lakernote.meerkat.capture.internal.CaptureSessions;
import io.github.lakernote.meerkat.capture.internal.CaptureStore;
import io.github.lakernote.meerkat.web.ObservabilityAccessPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrafficCaptureControllerTest {
    private static final CaptureRule RULE = new CaptureRule("get", "/api/**", null, null, 5, 60);

    private final CaptureSessions sessions = new CaptureSessions(Duration.ofMinutes(10), 100);
    private final CaptureStore store = new CaptureStore(10, Duration.ofHours(1));

    private static ObservabilityAccessPolicy policy(boolean view, boolean capture) {
        return new ObservabilityAccessPolicy() {
            @Override
            public boolean canView() {
                return view;
            }

            @Override
            public boolean canCapture() {
                return capture;
            }
        };
    }

    private TrafficCaptureController controller(boolean view, boolean capture, boolean enabled) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (enabled) {
            beans.addBean("captureSessions", sessions);
            beans.addBean("captureStore", store);
        }
        ObjectProvider<CaptureSessions> sessionsProvider = beans.getBeanProvider(CaptureSessions.class);
        ObjectProvider<CaptureStore> storeProvider = beans.getBeanProvider(CaptureStore.class);
        return new TrafficCaptureController(sessionsProvider, storeProvider, policy(view, capture));
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }

    private static MockHttpServletRequest request(Consumer<MockHttpServletRequest> customizer) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.7");
        customizer.accept(request);
        return request;
    }

    @Test
    void servesPageAndRedirect() {
        TrafficCaptureController controller = controller(true, true, true);

        var redirect = controller.redirectToCapture();
        assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.PERMANENT_REDIRECT);
        assertThat(redirect.getHeaders().getLocation()).hasToString("capture/");
        var page = controller.capturePage();
        assertThat(page.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_HTML);
        assertThat(page.getBody()).isNotNull();
        assertThat(page.getBody().exists()).isTrue();
    }

    @Test
    void statusRequiresViewAndHidesSessionsWithoutCapturePermission() {
        assertStatus(() -> controller(false, true, true).status(), HttpStatus.FORBIDDEN);

        sessions.start(RULE, "ops@1");
        store.add(record("1-1", "1"));

        var limited = controller(true, false, true).status();
        assertThat(limited.enabled()).isTrue();
        assertThat(limited.canCapture()).isFalse();
        assertThat(limited.maxDurationSeconds()).isEqualTo(600);
        assertThat(limited.sessions()).isEmpty();
        assertThat(limited.records()).isZero();

        var full = controller(true, true, true).status();
        assertThat(full.sessions()).hasSize(1);
        assertThat(full.records()).isEqualTo(1);
    }

    @Test
    void statusReportsDisabledWhenBeansAreAbsent() {
        var status = controller(true, true, false).status();

        assertThat(status.enabled()).isFalse();
        assertThat(status.canCapture()).isTrue();
        assertThat(status.maxDurationSeconds()).isZero();
        assertThat(status.sessions()).isEmpty();
        assertThat(status.records()).isZero();
    }

    @Test
    void operationsRequireViewAndCapturePermissions() {
        MockHttpServletRequest request = request(r -> { });
        for (TrafficCaptureController denied : new TrafficCaptureController[]{
                controller(false, true, true), controller(true, false, true)}) {
            assertStatus(() -> denied.start(RULE, request), HttpStatus.FORBIDDEN);
            assertStatus(() -> denied.stop("1", request), HttpStatus.FORBIDDEN);
            assertStatus(() -> denied.records(null), HttpStatus.FORBIDDEN);
            assertStatus(() -> denied.record("1-1"), HttpStatus.FORBIDDEN);
            assertStatus(denied::clear, HttpStatus.FORBIDDEN);
        }
        assertThat(sessions.list()).isEmpty();
    }

    @Test
    void operationsReturnNotFoundWhenCaptureIsDisabled() {
        TrafficCaptureController disabled = controller(true, true, false);

        assertStatus(() -> disabled.start(RULE, request(r -> { })), HttpStatus.NOT_FOUND);
        assertStatus(() -> disabled.records("1"), HttpStatus.NOT_FOUND);
        assertStatus(disabled::clear, HttpStatus.NOT_FOUND);
    }

    @Test
    void storeMissingAloneIsReportedAsDisabled() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("captureSessions", sessions);
        TrafficCaptureController controller = new TrafficCaptureController(
                beans.getBeanProvider(CaptureSessions.class), beans.getBeanProvider(CaptureStore.class),
                policy(true, true));

        assertThat(controller.status().enabled()).isFalse();
        assertStatus(() -> controller.records(null), HttpStatus.NOT_FOUND);
        assertStatus(() -> controller.record("x"), HttpStatus.NOT_FOUND);
    }

    @Test
    void startsAndStopsSessionsRecordingTheOperator() {
        TrafficCaptureController controller = controller(true, true, true);

        var view = controller.start(RULE, request(r -> r.setUserPrincipal(() -> "alice")));
        assertThat(view.operator()).isEqualTo("alice@10.0.0.7");
        assertThat(view.rule().method()).isEqualTo("GET");

        var remoteUser = controller.start(RULE, request(r -> r.setRemoteUser("bob")));
        assertThat(remoteUser.operator()).isEqualTo("bob@10.0.0.7");

        var anonymous = controller.start(RULE, request(r -> r.setRemoteUser(" ")));
        assertThat(anonymous.operator()).isEqualTo("anonymous@10.0.0.7");

        assertThat(controller.stop(view.id(), request(r -> { })).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertStatus(() -> controller.stop("missing", request(r -> { })), HttpStatus.NOT_FOUND);
        assertThat(controller.status().sessions()).hasSize(3);
    }

    @Test
    void mapsInvalidRulesToBadRequestAndFullSessionsToConflict() {
        TrafficCaptureController controller = controller(true, true, true);

        assertThatThrownBy(() -> controller.start(new CaptureRule("GET", "no-slash", null, null, 1, 60),
                request(r -> { })))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).isEqualTo("path must start with /");
                });

        for (int i = 0; i < 10; i++) controller.start(RULE, request(r -> { }));
        assertStatus(() -> controller.start(RULE, request(r -> { })), HttpStatus.CONFLICT);
    }

    @Test
    void listsFindsAndClearsRecords() {
        TrafficCaptureController controller = controller(true, true, true);
        store.add(record("1-1", "1"));
        store.add(record("2-1", "2"));

        assertThat(controller.records(null)).extracting(CaptureRecord.Summary::id).containsExactly("2-1", "1-1");
        assertThat(controller.records("1")).extracting(CaptureRecord.Summary::id).containsExactly("1-1");
        assertThat(controller.record("2-1").path()).isEqualTo("/api/2-1");
        assertStatus(() -> controller.record("missing"), HttpStatus.NOT_FOUND);

        assertThat(controller.clear().cleared()).isEqualTo(2);
        assertThat(controller.records(null)).isEmpty();
    }

    private static CaptureRecord record(String id, String sessionId) {
        return new CaptureRecord(id, sessionId, "trace-" + id, "GET", "/api/" + id, 200, 3, false, Instant.now(),
                null, null, null, null);
    }
}
