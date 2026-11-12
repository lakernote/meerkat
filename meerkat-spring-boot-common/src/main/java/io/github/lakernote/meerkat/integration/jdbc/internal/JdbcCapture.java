package io.github.lakernote.meerkat.integration.jdbc.internal;

import io.github.lakernote.meerkat.capture.internal.CaptureText;
import io.github.lakernote.meerkat.trace.internal.CaptureTarget;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Records the raw SQL, bound parameters and outcome of a captured query; never throws. */
final class JdbcCapture {
    private static final int MAX_BATCH_ROWS = 50;

    private JdbcCapture() {
    }

    static void before(CaptureTarget target, List<QueryInfo> queries) {
        try {
            if (queries == null || queries.isEmpty()) return;
            StringBuilder sql = new StringBuilder();
            StringBuilder params = new StringBuilder();
            int maxChars = target.limits().maxValueChars();
            if (queries.size() > 1) sql.append("-- batch of ").append(queries.size()).append(" statements\n");
            for (QueryInfo query : queries) {
                if (sql.length() >= maxChars) break;
                if (!sql.isEmpty() && sql.charAt(sql.length() - 1) != '\n') sql.append(";\n");
                String text = query.getQuery() == null ? "" : query.getQuery();
                sql.append(text, 0, Math.min(text.length(), maxChars - sql.length() + 1));
                appendParameters(params, query.getParametersList(), maxChars);
            }
            target.capture("sql", sql.toString());
            if (!params.isEmpty()) target.capture("params", params.toString());
        } catch (RuntimeException ignored) {
            // 抓取失败不能影响 JDBC 调用。
        }
    }

    static void after(CaptureTarget target, Object result, Throwable failure) {
        try {
            if (failure != null) {
                target.capture("error", failure.toString());
            } else if (result instanceof ResultSet) {
                // Rows are recorded by the result set proxy as the application reads them.
            } else if (result instanceof Integer || result instanceof Long) {
                target.capture("result", "updated rows: " + result);
            } else if (result instanceof int[] || result instanceof long[]) {
                target.capture("result", "batch updated rows: " + CaptureText.of(result, 2_000));
            }
        } catch (RuntimeException ignored) {
            // 抓取失败不能影响 JDBC 调用。
        }
    }

    private static void appendParameters(StringBuilder out, List<List<ParameterSetOperation>> batches, int maxChars) {
        if (batches == null) return;
        int row = 0;
        for (List<ParameterSetOperation> operations : batches) {
            if (operations == null || operations.isEmpty()) continue;
            if (out.length() > maxChars) return;
            if (++row > MAX_BATCH_ROWS) {
                out.append("\n… ").append(batches.size()).append(" parameter sets");
                return;
            }
            if (!out.isEmpty()) out.append('\n');
            if (batches.size() > 1) out.append('#').append(row).append(' ');
            List<ParameterSetOperation> sorted = new ArrayList<>(operations);
            sorted.sort(Comparator.comparing(JdbcCapture::position));
            boolean first = true;
            for (ParameterSetOperation operation : sorted) {
                Object[] args = operation.getArgs();
                if (args == null || args.length == 0) continue;
                if (out.length() > maxChars) {
                    out.append(" …");
                    return;
                }
                if (!first) out.append(", ");
                first = false;
                out.append('[').append(args[0]).append("] ");
                if (ParameterSetOperation.isSetNullParameterOperation(operation)) out.append("NULL");
                else if (ParameterSetOperation.isRegisterOutParameterOperation(operation)) out.append("OUT");
                else out.append(value(args.length > 1 ? args[1] : null));
            }
        }
    }

    private static int position(ParameterSetOperation operation) {
        Object[] args = operation.getArgs();
        return args != null && args.length > 0 && args[0] instanceof Integer index ? index : Integer.MAX_VALUE;
    }

    private static String value(Object value) {
        if (value instanceof CharSequence text) return "'" + CaptureText.of(text, 2_000) + "'";
        return CaptureText.of(value, 2_000);
    }
}
