package io.github.lakernote.meerkat.starter.boot3;

import io.github.lakernote.meerkat.trace.SpanKind;
import io.github.lakernote.meerkat.trace.internal.TraceCompletion;
import io.github.lakernote.meerkat.trace.TraceBlock;
import io.github.lakernote.meerkat.trace.internal.TraceContext;
import io.github.lakernote.meerkat.trace.internal.TraceNode;
import io.github.lakernote.meerkat.trace.TraceSpan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@SpringBootTest(classes = ExplicitSpanErrorE2ETest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "meerkat.metrics.enabled=false",
                "meerkat.web.enabled=false"
        })
class ExplicitSpanErrorE2ETest {

    @Autowired
    private ExplicitFailureService service;

    @AfterEach
    void clearTrace() {
        TraceContext.clear();
    }

    @Test
    void realTraceSpanProxyMarksHandledFailureAsAnError() {
        assertThat(AopUtils.isAopProxy(service)).isTrue();
        AtomicReference<TraceCompletion> completion = new AtomicReference<>();
        TraceContext.startRoot("explicit-span-e2e", SpanKind.HTTP);

        Throwable thrown = catchThrowable(service::failAnnotated);

        assertThat(thrown).isSameAs(service.annotatedFailure());
        TraceContext.currentRootTraceHandle().requireTree();
        TraceContext.stopRoot(0, null, completion::set);
        TraceCompletion result = completion.get();
        TraceNode root = result.nodes().get(0);
        TraceNode annotated = root.children().get(0);
        assertThat(result.error()).isTrue();
        assertThat(result.reasons()).containsExactly("CODE_ERROR").doesNotContain("ROOT_ERROR");
        assertThat(result.errorType()).isEqualTo(IllegalStateException.class.getName());
        assertThat(root.error()).isTrue();
        assertThat(annotated.name()).isEqualTo("annotated-failure");
        assertThat(annotated.error()).isTrue();
        assertThat(annotated.errorType()).isEqualTo(IllegalStateException.class.getName());
        assertThat(annotated.unfinishedAtTraceEnd()).isFalse();
    }

    @Test
    void traceBlockAndAnnotatedWrapperBothRecordThePropagatedFailure() {
        AtomicReference<TraceCompletion> completion = new AtomicReference<>();
        TraceContext.startRoot("explicit-block-e2e", SpanKind.HTTP);

        Throwable thrown = catchThrowable(service::failThroughBlock);

        assertThat(thrown).isSameAs(service.blockFailure());
        TraceContext.currentRootTraceHandle().requireTree();
        TraceContext.stopRoot(0, null, completion::set);
        TraceCompletion result = completion.get();
        TraceNode root = result.nodes().get(0);
        TraceNode wrapper = root.children().get(0);
        TraceNode block = wrapper.children().get(0);
        assertThat(result.error()).isTrue();
        assertThat(result.reasons()).containsExactly("CODE_ERROR").doesNotContain("ROOT_ERROR");
        assertThat(wrapper.name()).isEqualTo("annotated-wrapper");
        assertThat(wrapper.error()).isTrue();
        assertThat(block.name()).isEqualTo("block-failure");
        assertThat(block.error()).isTrue();
        assertThat(block.errorType()).isEqualTo(IllegalArgumentException.class.getName());
        assertThat(block.unfinishedAtTraceEnd()).isFalse();
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication {
        @Bean
        ExplicitFailureService explicitFailureService() {
            return new ExplicitFailureService();
        }
    }

    static class ExplicitFailureService {
        private final IllegalStateException annotatedFailure = new IllegalStateException("annotated failure");
        private final IllegalArgumentException blockFailure = new IllegalArgumentException("block failure");

        @TraceSpan(name = "annotated-failure", kind = SpanKind.SERVICE)
        public void failAnnotated() {
            throw annotatedFailure;
        }

        @TraceSpan(name = "annotated-wrapper", kind = SpanKind.SERVICE)
        public void failThroughBlock() {
            TraceBlock.run("block-failure", SpanKind.CODE, () -> {
                throw blockFailure;
            });
        }

        IllegalStateException annotatedFailure() {
            return annotatedFailure;
        }

        IllegalArgumentException blockFailure() {
            return blockFailure;
        }
    }
}
