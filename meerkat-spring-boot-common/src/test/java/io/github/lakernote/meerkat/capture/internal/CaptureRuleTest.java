package io.github.lakernote.meerkat.capture.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaptureRuleTest {

    @Test
    void normalizesFields() {
        CaptureRule rule = new CaptureRule(null, null, null, null, 1, 1);

        assertThat(rule.method()).isEmpty();
        assertThat(rule.pathPattern()).isEmpty();
        assertThat(rule.headerName()).isEmpty();
        assertThat(rule.headerValue()).isEmpty();

        CaptureRule trimmed = new CaptureRule(" post ", " /a ", " X-User ", " bob ", 1, 1);
        assertThat(trimmed).isEqualTo(new CaptureRule("POST", "/a", "X-User", "bob", 1, 1));
    }

    @Test
    void acceptsValidRule() {
        assertThatCode(() -> new CaptureRule("GET", "/api/**", "X-User", "bob", CaptureRule.MAX_COUNT, 60)
                .validate(60, CaptureRule.MAX_COUNT)).doesNotThrowAnyException();
        assertThatCode(() -> new CaptureRule("", "/", "", "", 1, 1).validate(60, CaptureRule.MAX_COUNT)).doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidRules() {
        assertInvalid(new CaptureRule("GET", "", "", "", 1, 1), "path must start with /");
        assertInvalid(new CaptureRule("GET", "api", "", "", 1, 1), "path must start with /");
        assertInvalid(new CaptureRule("GET", "/" + "a".repeat(512), "", "", 1, 1), "path is too long");
        assertInvalid(new CaptureRule("G1", "/a", "", "", 1, 1), "invalid method");
        assertInvalid(new CaptureRule("GETPOSTPUTX", "/a", "", "", 1, 1), "invalid method");
        assertInvalid(new CaptureRule("GET", "/a", "h".repeat(129), "", 1, 1), "header is too long");
        assertInvalid(new CaptureRule("GET", "/a", "h", "v".repeat(513), 1, 1), "header is too long");
        assertInvalid(new CaptureRule("GET", "/a", "", "value", 1, 1), "header value needs a header name");
        assertInvalid(new CaptureRule("GET", "/a", "", "", 0, 1), "count must be between 1 and 1000");
        assertInvalid(new CaptureRule("GET", "/a", "", "", 1001, 1), "count must be between");
        assertInvalid(new CaptureRule("GET", "/a", "", "", 1, 0), "duration must be between 1 and 60 seconds");
        assertInvalid(new CaptureRule("GET", "/a", "", "", 1, 61), "duration must be between");
    }

    private static void assertInvalid(CaptureRule rule, String message) {
        assertThatThrownBy(() -> rule.validate(60, CaptureRule.MAX_COUNT)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }
}
