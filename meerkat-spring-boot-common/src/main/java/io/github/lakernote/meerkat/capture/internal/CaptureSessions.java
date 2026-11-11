package io.github.lakernote.meerkat.capture.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/**
 * Active capture rules. Each start and stop is written to the application log as an audit trail, because captured
 * requests are recorded without redaction.
 */
public final class CaptureSessions {
    private static final Logger log = LoggerFactory.getLogger(CaptureSessions.class);
    static final int MAX_ACTIVE_SESSIONS = 10;
    private static final int MAX_FINISHED_SESSIONS = 50;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final CopyOnWriteArrayList<Session> sessions = new CopyOnWriteArrayList<>();
    private final AtomicLong nextId = new AtomicLong();
    private final Duration maxDuration;
    private final int maxCount;
    private final Clock clock;
    /** Read on every request; true only while at least one session may still match. */
    private volatile boolean anyActive;

    /** {@code maxRecords} caps each session's count, so one session never captures more than the store keeps. */
    public CaptureSessions(Duration maxDuration, int maxRecords) {
        this(maxDuration, maxRecords, Clock.systemUTC());
    }

    CaptureSessions(Duration maxDuration, int maxRecords, Clock clock) {
        this.maxDuration = maxDuration == null || maxDuration.isNegative() || maxDuration.isZero()
                ? Duration.ofHours(1) : maxDuration;
        this.maxCount = Math.max(1, Math.min(CaptureRule.MAX_COUNT, maxRecords));
        this.clock = clock;
    }

    public int maxCount() {
        return maxCount;
    }

    public long maxDurationSeconds() {
        return maxDuration.toSeconds();
    }

    public synchronized SessionView start(CaptureRule rule, String operator) {
        rule.validate(maxDurationSeconds(), maxCount);
        expire();
        long active = sessions.stream().filter(Session::active).count();
        if (active >= MAX_ACTIVE_SESSIONS) {
            throw new IllegalStateException("At most " + MAX_ACTIVE_SESSIONS + " captures may be active");
        }
        Instant now = clock.instant();
        Session session = new Session(Long.toString(nextId.incrementAndGet()), rule, safe(operator), now,
                now.plusSeconds(rule.durationSeconds()));
        sessions.add(session);
        trimFinished();
        anyActive = true;
        log.warn("Meerkat traffic capture #{} started by {}: method={} path={} header={} count={} duration={}s. "
                        + "Matching requests are recorded without redaction.", session.id, session.operator,
                rule.method().isEmpty() ? "ANY" : rule.method(), rule.pathPattern(),
                rule.headerName().isEmpty() ? "-" : rule.headerName(), rule.maxCount(), rule.durationSeconds());
        return session.view(now);
    }

    public synchronized boolean stop(String id, String operator) {
        for (Session session : sessions) {
            if (session.id.equals(id) && session.active()) {
                finish(session, "stopped by " + safe(operator));
                refreshActive();
                return true;
            }
        }
        return false;
    }

    public synchronized List<SessionView> list() {
        expire();
        Instant now = clock.instant();
        List<SessionView> views = new ArrayList<>();
        for (int index = sessions.size() - 1; index >= 0; index--) views.add(sessions.get(index).view(now));
        return views;
    }

    public boolean anyActive() {
        return anyActive;
    }

    /**
     * Returns the session that takes this request, consuming one of its remaining captures, or {@code null}.
     *
     * @param header returns a request header value, or {@code null} when absent
     */
    public Match match(String method, String path, UnaryOperator<String> header) {
        if (!anyActive) return null;
        Instant now = clock.instant();
        boolean expired = false;
        for (Session session : sessions) {
            if (!session.active()) continue;
            if (!now.isBefore(session.expiresAt)) {
                expired = true;
                continue;
            }
            if (!session.matches(method, path, header, pathMatcher)) continue;
            int sequence = session.captured.incrementAndGet();
            if (sequence > session.rule.maxCount()) continue;
            if (sequence == session.rule.maxCount()) {
                synchronized (this) {
                    finish(session, "reached " + sequence + " requests");
                    refreshActive();
                }
            }
            return new Match(session.id, sequence);
        }
        if (expired) {
            synchronized (this) {
                expire();
            }
        }
        return null;
    }

    private void expire() {
        Instant now = clock.instant();
        for (Session session : sessions) {
            if (session.active() && !now.isBefore(session.expiresAt)) finish(session, "expired");
        }
        refreshActive();
    }

    private void finish(Session session, String reason) {
        if (!session.active()) return;
        session.endedAt = clock.instant();
        session.endReason = reason;
        log.warn("Meerkat traffic capture #{} ended ({}); {} request(s) captured", session.id, reason,
                Math.min(session.captured.get(), session.rule.maxCount()));
    }

    private void refreshActive() {
        anyActive = sessions.stream().anyMatch(Session::active);
    }

    private void trimFinished() {
        int finished = 0;
        for (int index = sessions.size() - 1; index >= 0; index--) {
            Session session = sessions.get(index);
            if (!session.active() && ++finished > MAX_FINISHED_SESSIONS) sessions.remove(index);
        }
    }

    private static String safe(String operator) {
        if (operator == null || operator.isBlank()) return "anonymous";
        String cleaned = operator.replaceAll("[\\r\\n\\t]", " ").trim();
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }

    public record Match(String sessionId, int sequence) { }

    public record SessionView(String id, CaptureRule rule, String operator, Instant startedAt, Instant expiresAt,
                              Instant endedAt, String endReason, int capturedCount, boolean active) { }

    private static final class Session {
        private final String id;
        private final CaptureRule rule;
        private final String operator;
        private final Instant startedAt;
        private final Instant expiresAt;
        private final AtomicInteger captured = new AtomicInteger();
        private volatile Instant endedAt;
        private volatile String endReason = "";

        private Session(String id, CaptureRule rule, String operator, Instant startedAt, Instant expiresAt) {
            this.id = id;
            this.rule = rule;
            this.operator = operator;
            this.startedAt = startedAt;
            this.expiresAt = expiresAt;
        }

        boolean active() {
            return endedAt == null;
        }

        boolean matches(String method, String path, UnaryOperator<String> header, AntPathMatcher matcher) {
            if (!rule.method().isEmpty() && !rule.method().equalsIgnoreCase(method)) return false;
            if (path == null || !matcher.match(rule.pathPattern(), path)) return false;
            if (rule.headerName().isEmpty()) return true;
            String value = header.apply(rule.headerName());
            return value != null && (rule.headerValue().isEmpty() || rule.headerValue().equals(value));
        }

        SessionView view(Instant now) {
            return new SessionView(id, rule, operator, startedAt, expiresAt, endedAt, endReason,
                    Math.min(captured.get(), rule.maxCount()), active() && now.isBefore(expiresAt));
        }
    }
}
