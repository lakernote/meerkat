package io.github.lakernote.meerkat.web.internal;

import io.github.lakernote.meerkat.capture.internal.CaptureRecord;
import io.github.lakernote.meerkat.capture.internal.CaptureRule;
import io.github.lakernote.meerkat.capture.internal.CaptureSessions;
import io.github.lakernote.meerkat.capture.internal.CaptureStore;
import io.github.lakernote.meerkat.web.ObservabilityAccessPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.security.Principal;
import java.util.List;

/** Serves the traffic capture page and its API. Every operation except the status needs the capture permission. */
@RestController
public class TrafficCaptureController {
    private static final Resource CAPTURE_PAGE = new ClassPathResource("META-INF/resources/meerkat/capture.html");
    private static final String API = "/api/meerkat/capture";
    private final ObjectProvider<CaptureSessions> sessionsProvider;
    private final ObjectProvider<CaptureStore> storeProvider;
    private final ObservabilityAccessPolicy accessPolicy;

    public TrafficCaptureController(ObjectProvider<CaptureSessions> sessionsProvider,
                                    ObjectProvider<CaptureStore> storeProvider,
                                    ObservabilityAccessPolicy accessPolicy) {
        this.sessionsProvider = sessionsProvider;
        this.storeProvider = storeProvider;
        this.accessPolicy = accessPolicy;
    }

    @GetMapping("/meerkat/capture")
    public ResponseEntity<Void> redirectToCapture() {
        return ResponseEntity.status(HttpStatus.PERMANENT_REDIRECT).location(URI.create("capture/")).build();
    }

    @GetMapping("/meerkat/capture/")
    public ResponseEntity<Resource> capturePage() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(CAPTURE_PAGE);
    }

    @GetMapping(API + "/status")
    public Status status() {
        if (!accessPolicy.canView()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        CaptureSessions sessions = sessionsProvider.getIfAvailable();
        CaptureStore store = storeProvider.getIfAvailable();
        boolean enabled = sessions != null && store != null;
        boolean canCapture = accessPolicy.canCapture();
        return new Status(enabled, canCapture, enabled ? sessions.maxDurationSeconds() : 0,
                enabled ? sessions.maxCount() : 0,
                enabled && canCapture ? sessions.list() : List.of(), enabled && canCapture ? store.size() : 0);
    }

    @PostMapping(API + "/sessions")
    public CaptureSessions.SessionView start(@RequestBody CaptureRule rule, HttpServletRequest request) {
        CaptureSessions sessions = sessions();
        try {
            return sessions.start(rule, operator(request));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        } catch (IllegalStateException full) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, full.getMessage());
        }
    }

    @PostMapping(API + "/sessions/{id}/stop")
    public ResponseEntity<Void> stop(@PathVariable String id, HttpServletRequest request) {
        if (!sessions().stop(id, operator(request))) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(API + "/records")
    public List<CaptureRecord.Summary> records(@RequestParam(required = false) String sessionId) {
        sessions();
        return store().list(sessionId);
    }

    @GetMapping(API + "/records/{id}")
    public CaptureRecord record(@PathVariable String id) {
        sessions();
        CaptureRecord record = store().find(id);
        if (record == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return record;
    }

    @PostMapping(API + "/records/clear")
    public Cleared clear() {
        sessions();
        return new Cleared(store().clear());
    }

    private CaptureSessions sessions() {
        requireCapture();
        CaptureSessions sessions = sessionsProvider.getIfAvailable();
        if (sessions == null) throw disabled();
        return sessions;
    }

    private CaptureStore store() {
        CaptureStore store = storeProvider.getIfAvailable();
        if (store == null) throw disabled();
        return store;
    }

    private void requireCapture() {
        if (!accessPolicy.canView() || !accessPolicy.canCapture()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private static ResponseStatusException disabled() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Traffic capture is unavailable; it needs meerkat.trace.enabled=true");
    }

    private static String operator(HttpServletRequest request) {
        Principal principal = request.getUserPrincipal();
        String user = principal != null ? principal.getName() : request.getRemoteUser();
        String address = request.getRemoteAddr();
        return (user == null || user.isBlank() ? "anonymous" : user) + "@" + address;
    }

    public record Status(boolean enabled, boolean canCapture, long maxDurationSeconds, int maxCount,
                         List<CaptureSessions.SessionView> sessions, int records) { }

    public record Cleared(int cleared) { }
}
