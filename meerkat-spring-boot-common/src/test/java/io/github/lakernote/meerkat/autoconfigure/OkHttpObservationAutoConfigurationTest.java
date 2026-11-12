package io.github.lakernote.meerkat.autoconfigure;

import okhttp3.Request;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OkHttpObservationAutoConfigurationTest {
    @Test
    void replacesIdentifiersAndCredentialsInThePath() {
        assertThat(uri("/users/12345/orders/7f1e9a2c-4b3d-4e5f-8a9b-0c1d2e3f4a5b")).isEqualTo("/users/{id}/orders/{id}");
        assertThat(uri("/files/0123456789abcdef0123")).isEqualTo("/files/{id}");
        assertThat(uri("/users/alice@example.com/profile")).isEqualTo("/users/{id}/profile");
        assertThat(uri("/users/alice%40example.com")).isEqualTo("/users/{id}");
        assertThat(uri("/verify/eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl")).isEqualTo("/verify/{id}");
        assertThat(uri("/reset/Zx8kQ2pLm9Rt4Vw7Yb3Nc6Hd")).isEqualTo("/reset/{id}");
        assertThat(uri("/orders/ORD20260001")).isEqualTo("/orders/{id}");
    }

    @Test
    void keepsOrdinaryPathWords() {
        assertThat(uri("/api/v2/oauth2/token")).isEqualTo("/api/v2/oauth2/token");
        assertThat(uri("/s3/buckets/")).isEqualTo("/s3/buckets/");
        assertThat(uri("/")).isEqualTo("/");
        assertThat(uri("/a".repeat(200))).hasSize(256);
    }

    private static String uri(String path) {
        return OkHttpObservationAutoConfiguration.boundedUri(
                new Request.Builder().url("https://api.example.com" + path + "?token=secret").build());
    }
}
