package io.github.lakernote.meerkat.trace.internal;

import java.util.List;

/**
 * Immutable result of a finished root trace. {@code tree} and {@code nodes} are only populated when the trace was
 * slow, failed or explicitly required its tree, so the common successful path never renders anything.
 */
public record TraceCompletion(String tree, List<TraceNode> nodes, long costMs, boolean slow, boolean error,
                              List<String> reasons, String errorType, String errorMessage, String stackTrace) {
    public TraceCompletion {
        tree = tree == null ? "" : tree;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        errorType = errorType == null ? "" : errorType;
        errorMessage = errorMessage == null ? "" : errorMessage;
        stackTrace = stackTrace == null ? "" : stackTrace;
    }
}
