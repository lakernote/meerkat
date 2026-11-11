package io.github.lakernote.meerkat.trace.internal.store;

import io.github.lakernote.meerkat.trace.internal.TraceNode;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Dependency-free JSON codec for one diagnostic trace archive line.
 * <p>The archive format is owned by this component and never depends on the host application's JSON library or
 * settings: record components in camelCase, {@code finishedAt} as an ISO-8601 string, unknown fields ignored and
 * missing fields defaulted so the format can evolve without breaking older archives.</p>
 */
class TraceRecordJsonCodec {
    static final TraceRecordJsonCodec INSTANCE = new TraceRecordJsonCodec();

    /** Guards the recursive tree parser against stack exhaustion from corrupted or hostile archive lines. */
    private static final int MAX_DEPTH = 256;

    void write(OutputStream output, TraceRecord trace) throws IOException {
        StringBuilder json = new StringBuilder(1024);
        writeRecord(json, trace);
        // Lone surrogates are already escaped, so UTF-8 encoding is lossless.
        output.write(json.toString().getBytes(StandardCharsets.UTF_8));
    }

    TraceRecord read(byte[] line) throws IOException {
        Parser parser = new Parser(new String(line, StandardCharsets.UTF_8));
        TraceRecord trace = parser.readRecord();
        parser.expectEnd();
        return trace;
    }

    private static void writeRecord(StringBuilder out, TraceRecord trace){
        out.append('{');
        field(out, "id", true); out.append(trace.id());
        field(out, "traceId", false); string(out, trace.traceId());
        field(out, "name", false); string(out, trace.name());
        field(out, "tag", false); string(out, trace.tag());
        field(out, "status", false); out.append(trace.status());
        field(out, "durationMs", false); out.append(trace.durationMs());
        field(out, "slow", false); out.append(trace.slow());
        field(out, "error", false); out.append(trace.error());
        field(out, "treeNodes", false); nodes(out, trace.treeNodes());
        field(out, "finishedAt", false);
        string(out, trace.finishedAt() == null ? null : trace.finishedAt().toString());
        field(out, "threadId", false); out.append(trace.threadId());
        field(out, "cpuTimeMs", false); out.append(trace.cpuTimeMs());
        field(out, "allocatedBytes", false); out.append(trace.allocatedBytes());
        field(out, "sourceType", false); string(out, trace.sourceType());
        field(out, "errorType", false); string(out, trace.errorType());
        field(out, "errorMessage", false); string(out, trace.errorMessage());
        field(out, "threadName", false); string(out, trace.threadName());
        field(out, "errorStackTrace", false); string(out, trace.errorStackTrace());
        field(out, "highCpu", false); out.append(trace.highCpu());
        field(out, "highAllocation", false); out.append(trace.highAllocation());
        out.append('}');
    }

    private static void nodes(StringBuilder out, List<TraceNode> nodes){
        out.append('[');
        for (int index = 0; index < nodes.size(); index++) {
            if (index > 0) out.append(',');
            node(out, nodes.get(index));
        }
        out.append(']');
    }

    private static void node(StringBuilder out, TraceNode node){
        out.append('{');
        field(out, "kind", true); string(out, node.kind());
        field(out, "name", false); string(out, node.name());
        field(out, "tag", false); string(out, node.tag());
        field(out, "durationMs", false); out.append(node.durationMs());
        field(out, "startOffsetMs", false);
        out.append(node.startOffsetMs());
        field(out, "slow", false); out.append(node.slow());
        field(out, "error", false); out.append(node.error());
        field(out, "unfinishedAtTraceEnd", false); out.append(node.unfinishedAtTraceEnd());
        field(out, "errorType", false); string(out, node.errorType());
        field(out, "threadName", false); string(out, node.threadName());
        field(out, "threadId", false); out.append(node.threadId());
        field(out, "children", false); nodes(out, node.children());
        out.append('}');
    }

    private static void field(StringBuilder out, String name, boolean first) {
        if (!first) out.append(',');
        out.append('"');
        out.append(name);
        out.append("\":");
    }

    private static void string(StringBuilder out, String value) {
        if (value == null) {
            out.append("null");
            return;
        }
        out.append('"');
        int unescapedFrom = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            String escape = switch (current) {
                case '"' -> "\\\"";
                case '\\' -> "\\\\";
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                case '\t' -> "\\t";
                case '\b' -> "\\b";
                case '\f' -> "\\f";
                // Lone surrogates are escaped so the line stays valid UTF-8 and round-trips exactly.
                default -> current < 0x20 || (Character.isSurrogate(current) && !pairedSurrogate(value, index))
                        ? unicodeEscape(current) : null;
            };
            if (escape == null) continue;
            out.append(value, unescapedFrom, index).append(escape);
            unescapedFrom = index + 1;
        }
        out.append(value, unescapedFrom, value.length()).append('"');
    }

    private static String unicodeEscape(char value) {
        String hex = Integer.toHexString(value);
        return "\\u" + "0".repeat(4 - hex.length()) + hex;
    }

    private static boolean pairedSurrogate(String value, int index) {
        char current = value.charAt(index);
        if (Character.isHighSurrogate(current)) {
            return index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1));
        }
        return index > 0 && Character.isHighSurrogate(value.charAt(index - 1));
    }

    private static final class Parser {
        private final String text;
        private int position;
        private int depth;

        Parser(String text) {
            this.text = text;
        }

        TraceRecord readRecord() throws IOException {
            long id = 0;
            String traceId = "";
            String name = "";
            String tag = "";
            int status = 0;
            long durationMs = 0;
            boolean slow = false;
            boolean error = false;
            List<TraceNode> treeNodes = List.of();
            Instant finishedAt = null;
            long threadId = 0;
            long cpuTimeMs = 0;
            long allocatedBytes = 0;
            String sourceType = "";
            String errorType = "";
            String errorMessage = "";
            String threadName = "";
            String errorStackTrace = "";
            boolean highCpu = false;
            boolean highAllocation = false;

            expect('{');
            if (!consumeIf('}')) {
                do {
                    String field = readString();
                    expect(':');
                    switch (field) {
                        case "id" -> id = readLong();
                        case "traceId" -> traceId = readNullableString();
                        case "name" -> name = readNullableString();
                        case "tag" -> tag = readNullableString();
                        case "status" -> status = Math.toIntExact(readLong());
                        case "durationMs" -> durationMs = readLong();
                        case "slow" -> slow = readBoolean();
                        case "error" -> error = readBoolean();
                        case "treeNodes" -> treeNodes = readNodes();
                        case "finishedAt" -> finishedAt = readInstant();
                        case "threadId" -> threadId = readLong();
                        case "cpuTimeMs" -> cpuTimeMs = readLong();
                        case "allocatedBytes" -> allocatedBytes = readLong();
                        case "sourceType" -> sourceType = readNullableString();
                        case "errorType" -> errorType = readNullableString();
                        case "errorMessage" -> errorMessage = readNullableString();
                        case "threadName" -> threadName = readNullableString();
                        case "errorStackTrace" -> errorStackTrace = readNullableString();
                        case "highCpu" -> highCpu = readBoolean();
                        case "highAllocation" -> highAllocation = readBoolean();
                        default -> skipValue();
                    }
                } while (consumeIf(','));
                expect('}');
            }
            return new TraceRecord(id, traceId, name, tag, status, durationMs, slow, error,
                    treeNodes, finishedAt, threadId, cpuTimeMs, allocatedBytes, sourceType, errorType, errorMessage,
                    threadName, errorStackTrace, highCpu, highAllocation);
        }

        private List<TraceNode> readNodes() throws IOException {
            if (consumeLiteral("null")) return List.of();
            enter();
            expect('[');
            List<TraceNode> nodes = new ArrayList<>();
            if (!consumeIf(']')) {
                do {
                    nodes.add(readNode());
                } while (consumeIf(','));
                expect(']');
            }
            depth--;
            return nodes;
        }

        private TraceNode readNode() throws IOException {
            String kind = null;
            String name = "";
            String tag = "";
            long durationMs = 0;
            long startOffsetMs = 0;
            boolean slow = false;
            boolean error = false;
            boolean unfinishedAtTraceEnd = false;
            String errorType = "";
            String threadName = "";
            long threadId = 0;
            List<TraceNode> children = List.of();

            enter();
            expect('{');
            if (!consumeIf('}')) {
                do {
                    String field = readString();
                    expect(':');
                    switch (field) {
                        case "kind" -> kind = readNullableString();
                        case "name" -> name = readNullableString();
                        case "tag" -> tag = readNullableString();
                        case "durationMs" -> durationMs = readLong();
                        case "startOffsetMs" -> startOffsetMs = readLong();
                        case "slow" -> slow = readBoolean();
                        case "error" -> error = readBoolean();
                        case "unfinishedAtTraceEnd" -> unfinishedAtTraceEnd = readBoolean();
                        case "errorType" -> errorType = readNullableString();
                        case "threadName" -> threadName = readNullableString();
                        case "threadId" -> threadId = readLong();
                        case "children" -> children = readNodes();
                        default -> skipValue();
                    }
                } while (consumeIf(','));
                expect('}');
            }
            depth--;
            return new TraceNode(kind, name, tag, durationMs, startOffsetMs, slow, error, unfinishedAtTraceEnd,
                    errorType, threadName, threadId, children);
        }

        private Instant readInstant() throws IOException {
            String value = readNullableString();
            if (value == null) return null;
            try {
                return Instant.parse(value);
            } catch (RuntimeException invalid) {
                throw new IOException("Invalid finishedAt: " + value, invalid);
            }
        }

        private void skipValue() throws IOException {
            skipWhitespace();
            char current = peek();
            if (current == '"') {
                readString();
            } else if (current == '{' || current == '[') {
                char close = current == '{' ? '}' : ']';
                enter();
                position++;
                if (!consumeIf(close)) {
                    do {
                        if (close == '}') {
                            readString();
                            expect(':');
                        }
                        skipValue();
                    } while (consumeIf(','));
                    expect(close);
                }
                depth--;
            } else if (!consumeLiteral("true") && !consumeLiteral("false") && !consumeLiteral("null")) {
                readNumber();
            }
        }

        private long readLong() throws IOException {
            String number = readNumber();
            try {
                return Long.parseLong(number);
            } catch (NumberFormatException invalid) {
                throw error("Expected an integer but found " + number);
            }
        }

        private String readNumber() throws IOException {
            skipWhitespace();
            int start = position;
            while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) position++;
            if (start == position) throw error("Expected a value");
            return text.substring(start, position);
        }

        private boolean readBoolean() throws IOException {
            if (consumeLiteral("true")) return true;
            if (consumeLiteral("false")) return false;
            throw error("Expected a boolean");
        }

        private String readNullableString() throws IOException {
            return consumeLiteral("null") ? null : readString();
        }

        private String readString() throws IOException {
            expect('"');
            int start = position;
            while (position < text.length()) {
                char current = text.charAt(position);
                if (current == '"') return text.substring(start, position++);
                if (current == '\\') break;
                position++;
            }
            StringBuilder value = new StringBuilder(position - start + 16).append(text, start, position);
            while (true) {
                if (position >= text.length()) throw error("Unterminated string");
                char current = text.charAt(position++);
                if (current == '"') return value.toString();
                if (current != '\\') {
                    value.append(current);
                    continue;
                }
                if (position >= text.length()) throw error("Unterminated escape");
                char escaped = text.charAt(position++);
                switch (escaped) {
                    case '"', '\\', '/' -> value.append(escaped);
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'u' -> {
                        if (position + 4 > text.length()) throw error("Truncated unicode escape");
                        try {
                            value.append((char) Integer.parseInt(text, position, position + 4, 16));
                        } catch (NumberFormatException invalid) {
                            throw error("Invalid unicode escape");
                        }
                        position += 4;
                    }
                    default -> throw error("Invalid escape \\" + escaped);
                }
            }
        }

        private void enter() throws IOException {
            if (++depth > MAX_DEPTH) throw error("Nesting deeper than " + MAX_DEPTH);
        }

        private boolean consumeLiteral(String literal) {
            skipWhitespace();
            if (!text.startsWith(literal, position)) return false;
            position += literal.length();
            return true;
        }

        private boolean consumeIf(char expected) {
            skipWhitespace();
            if (position < text.length() && text.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void expect(char expected) throws IOException {
            if (!consumeIf(expected)) throw error("Expected '" + expected + "'");
        }

        void expectEnd() throws IOException {
            skipWhitespace();
            if (position != text.length()) throw error("Unexpected trailing content");
        }

        private char peek() throws IOException {
            if (position >= text.length()) throw error("Unexpected end of input");
            return text.charAt(position);
        }

        private void skipWhitespace() {
            while (position < text.length()) {
                char current = text.charAt(position);
                if (current != ' ' && current != '\t' && current != '\r' && current != '\n') return;
                position++;
            }
        }

        private IOException error(String message) {
            return new IOException(message + " at offset " + position);
        }
    }
}
