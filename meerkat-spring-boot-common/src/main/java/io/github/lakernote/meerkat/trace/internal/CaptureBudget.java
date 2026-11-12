package io.github.lakernote.meerkat.trace.internal;

/** Bounds the captured text of one trace snapshot, per value and in total. */
final class CaptureBudget {
    static final String OMITTED = "[omitted: capture size limit for this trace reached]";
    private final int maxValueChars;
    private long remainingChars;
    private boolean omittedReported;

    CaptureBudget(int maxValueChars, long maxTraceChars) {
        this.maxValueChars = Math.max(64, maxValueChars);
        this.remainingChars = Math.max(0, maxTraceChars);
    }

    /** @return the bounded text; {@link #OMITTED} once when the budget runs out, then {@code null} to drop values */
    String render(Object value) {
        if (remainingChars <= 0) {
            if (omittedReported) return null;
            omittedReported = true;
            return OMITTED;
        }
        int limit = (int) Math.min(maxValueChars, remainingChars);
        String text;
        try {
            text = value instanceof CaptureValue lazy ? lazy.render(limit) : String.valueOf(value);
        } catch (RuntimeException | LinkageError failure) {
            text = "[capture failed: " + failure.getClass().getSimpleName() + "]";
        }
        if (text == null) text = "null";
        if (text.length() > limit) {
            // The marker counts against the limit too, so a value never takes more than its share of the budget.
            String marker = "… [truncated, " + text.length() + " chars]";
            text = marker.length() < limit ? text.substring(0, limit - marker.length()) + marker
                    : text.substring(0, limit);
        }
        remainingChars -= text.length();
        return text;
    }
}
