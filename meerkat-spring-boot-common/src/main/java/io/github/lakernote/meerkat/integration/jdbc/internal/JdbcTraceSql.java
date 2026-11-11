package io.github.lakernote.meerkat.integration.jdbc.internal;

import net.ttddyy.dsproxy.QueryInfo;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 生成有长度上限的 SQL 标签，不保留字面量或绑定参数。 */
final class JdbcTraceSql {
    static final int MAX_SQL_LENGTH = 512;
    static final int MAX_SCAN_LENGTH = 8_192;
    private static final int INCOMPLETE_DOLLAR_DELIMITER = -2;
    private static final int AMBIGUOUS_LITERAL = -3;
    private static final Set<String> COMMANDS = Set.of(
            "ALTER", "CALL", "COMMIT", "CREATE", "DELETE", "DROP", "EXEC", "EXECUTE", "EXPLAIN",
            "GRANT", "INSERT", "MERGE", "PRAGMA", "REPLACE", "REVOKE", "ROLLBACK", "SAVEPOINT",
            "SELECT", "SET", "SHOW", "TRUNCATE", "UPDATE", "UPSERT", "USE", "WITH");

    private static final Pattern IN_LIST = Pattern.compile("(?i)\\b(in)\\s*\\(\\s*\\?(?:\\s*,\\s*\\?)*\\s*\\)");
    private static final Pattern VALUE_ROWS = Pattern.compile("(\\([?,\\s]+\\))(?:\\s*,\\s*\\([?,\\s]+\\))+");

    private JdbcTraceSql() { }

    static String describe(List<QueryInfo> queries) {
        if (queries == null || queries.isEmpty() || queries.get(0) == null) return "statement";
        String sql = normalize(queries.get(0).getQuery());
        if (sql.isBlank()) sql = "statement";
        if (queries.size() > 1) sql = bound(sql + " (" + (queries.size() - 1) + " more statements)");
        return sql;
    }

    /**
     * Returns a statistics key: placeholder lists whose length varies per call ({@code IN (?, ?, ...)}, multi-row
     * {@code VALUES}) are collapsed so they map to one row, and statement batches are keyed by their first statement.
     */
    static String statsName(List<QueryInfo> queries, String described) {
        String sql = described;
        if (queries != null && queries.size() > 1 && queries.get(0) != null) {
            String first = normalize(queries.get(0).getQuery());
            sql = (first.isBlank() ? "statement" : first) + " [batch]";
        }
        if (sql.indexOf('?') < 0) return sql;
        sql = VALUE_ROWS.matcher(sql).replaceAll("$1, …");
        return IN_LIST.matcher(sql).replaceAll("$1 (?…)");
    }

    static String command(List<QueryInfo> queries) {
        return commandOf(describe(queries));
    }

    static String commandOf(String sql) {
        int end = 0;
        while (end < sql.length() && isAsciiLetter(sql.charAt(end))) end++;
        if (end == 0 || (end < sql.length() && sql.charAt(end) != ' ')) return "STATEMENT";
        String command = sql.substring(0, end).toUpperCase(Locale.ROOT);
        return COMMANDS.contains(command) ? command : "STATEMENT";
    }

    /**
     * A bounded, fail-closed lexer is used instead of regular expressions because comment markers may occur inside
     * literals and malformed SQL must not expose the remainder of an unterminated literal.
     */
    static String normalize(String sql) {
        if (sql == null || sql.isEmpty()) return "";
        int limit = Math.min(sql.length(), MAX_SCAN_LENGTH);
        NormalizedSql result = new NormalizedSql();
        int index = 0;
        // Open array constructor/subscript brackets; a '[' nested in one is never a quoted identifier.
        int expressionBrackets = 0;
        while (index < limit && !result.full()) {
            char current = sql.charAt(index);

            if (Character.isWhitespace(current)) {
                result.separate();
                index++;
                continue;
            }
            if (current == '-' && hasNext(sql, index, limit, '-')) {
                result.separate();
                index = skipLineComment(sql, index + 2, limit);
                continue;
            }
            if (current == '#' && !hasNext(sql, index, limit, '>')) {
                result.separate();
                index = skipLineComment(sql, index + 1, limit);
                continue;
            }
            if (current == '/' && hasNext(sql, index, limit, '*')) {
                result.separate();
                index = skipBlockComment(sql, index + 2, limit);
                continue;
            }
            if (isOracleQuotedLiteralStart(sql, index, limit)) {
                result.placeholder();
                index = skipOracleQuotedLiteral(sql, index, limit);
                continue;
            }
            if (current == '$' && (index == 0 || !isIdentifierPart(sql.charAt(index - 1)))) {
                int delimiterEnd = dollarDelimiterEnd(sql, index, limit);
                if (delimiterEnd == INCOMPLETE_DOLLAR_DELIMITER) {
                    result.placeholder();
                    break;
                }
                if (delimiterEnd > 0) {
                    result.placeholder();
                    index = skipDollarQuotedLiteral(sql, index, delimiterEnd, limit);
                    continue;
                }
            }
            if (current == '\'' || current == '"') {
                result.placeholder();
                index = skipQuoted(sql, index, limit, current);
                if (index == AMBIGUOUS_LITERAL) break;
                continue;
            }
            if (current == '`'
                    || (current == '[' && expressionBrackets == 0 && isBracketIdentifierStart(sql, index, limit))) {
                char closing = current == '`' ? '`' : ']';
                int quotedEnd = quotedIdentifierEnd(sql, index + 1, limit, closing);
                if (quotedEnd < 0) {
                    result.placeholder();
                    break;
                }
                result.appendRange(sql, index, quotedEnd);
                index = quotedEnd;
                continue;
            }
            if (isNumericStart(sql, index, limit)) {
                result.placeholder();
                index = skipNumericToken(sql, index + 1, limit);
                continue;
            }

            if (current == '[') expressionBrackets++;
            else if (current == ']' && expressionBrackets > 0) expressionBrackets--;
            result.append(current);
            index++;
        }
        return result.value();
    }

    private static boolean hasNext(String sql, int index, int limit, char expected) {
        return index + 1 < limit && sql.charAt(index + 1) == expected;
    }

    private static int skipLineComment(String sql, int index, int limit) {
        while (index < limit) {
            char current = sql.charAt(index);
            if (current == '\r' || current == '\n') return index + 1;
            index++;
        }
        return limit;
    }

    private static int skipBlockComment(String sql, int index, int limit) {
        int depth = 1;
        while (index < limit) {
            if (index + 1 < limit && sql.charAt(index) == '/' && sql.charAt(index + 1) == '*') {
                depth++;
                index += 2;
            } else if (index + 1 < limit && sql.charAt(index) == '*' && sql.charAt(index + 1) == '/') {
                depth--;
                index += 2;
                if (depth == 0) return index;
            } else {
                index++;
            }
        }
        return limit;
    }

    private static boolean isOracleQuotedLiteralStart(String sql, int index, int limit) {
        if (index + 2 >= limit || (sql.charAt(index) != 'q' && sql.charAt(index) != 'Q')) return false;
        return sql.charAt(index + 1) == '\'' && !Character.isWhitespace(sql.charAt(index + 2));
    }

    private static int skipOracleQuotedLiteral(String sql, int index, int limit) {
        char opening = sql.charAt(index + 2);
        char closing = switch (opening) {
            case '[' -> ']';
            case '{' -> '}';
            case '(' -> ')';
            case '<' -> '>';
            default -> opening;
        };
        int cursor = index + 3;
        while (cursor + 1 < limit) {
            if (sql.charAt(cursor) == closing && sql.charAt(cursor + 1) == '\'') return cursor + 2;
            cursor++;
        }
        return limit;
    }

    private static int dollarDelimiterEnd(String sql, int index, int limit) {
        int cursor = index + 1;
        if (cursor >= limit) return -1;
        if (sql.charAt(cursor) == '$') return cursor + 1;
        if (!isDollarTagStart(sql.charAt(cursor))) return -1;
        while (cursor < limit && isDollarTagPart(sql.charAt(cursor))) {
            cursor++;
        }
        return cursor < limit && sql.charAt(cursor) == '$' ? cursor + 1 : INCOMPLETE_DOLLAR_DELIMITER;
    }

    private static int skipDollarQuotedLiteral(String sql, int delimiterStart, int delimiterEnd, int limit) {
        int delimiterLength = delimiterEnd - delimiterStart;
        int cursor = delimiterEnd;
        while (cursor + delimiterLength <= limit) {
            if (sql.regionMatches(cursor, sql, delimiterStart, delimiterLength)) return cursor + delimiterLength;
            cursor++;
        }
        return limit;
    }

    /**
     * Whether a backslash escapes the next character depends on the dialect and server settings (MySQL by default,
     * PostgreSQL only in {@code E'...'}). When both readings end the literal at different places the remainder cannot
     * be classified safely, so {@link #AMBIGUOUS_LITERAL} is returned and the caller stops emitting text.
     */
    private static int skipQuoted(String sql, int start, int limit, char closing) {
        int escapedEnd = quotedEnd(sql, start + 1, limit, closing, true);
        if (isEscapeStringPrefix(sql, start)) return escapedEnd;
        int standardEnd = quotedEnd(sql, start + 1, limit, closing, false);
        return escapedEnd == standardEnd ? standardEnd : AMBIGUOUS_LITERAL;
    }

    private static boolean isEscapeStringPrefix(String sql, int quote) {
        if (quote == 0 || (sql.charAt(quote - 1) != 'E' && sql.charAt(quote - 1) != 'e')) return false;
        return quote == 1 || !isIdentifierPart(sql.charAt(quote - 2));
    }

    private static int quotedEnd(String sql, int index, int limit, char closing, boolean backslashEscapes) {
        while (index < limit) {
            char current = sql.charAt(index);
            if (backslashEscapes && current == '\\' && index + 1 < limit) {
                index += 2;
            } else if (current == closing) {
                if (index + 1 < limit && sql.charAt(index + 1) == closing) {
                    index += 2;
                } else {
                    return index + 1;
                }
            } else {
                index++;
            }
        }
        return limit;
    }

    /**
     * {@code [name]} is a SQL Server quoted identifier, but brackets after an expression are PostgreSQL array
     * constructors or subscripts ({@code ARRAY['x']}, {@code tags[1]}) whose contents must still be redacted.
     */
    private static boolean isBracketIdentifierStart(String sql, int index, int limit) {
        int previous = index - 1;
        while (previous >= 0 && Character.isWhitespace(sql.charAt(previous))) previous--;
        if (previous >= 0) {
            char before = sql.charAt(previous);
            if (isIdentifierPart(before) || before == ']' || before == ')') return false;
        }
        for (int cursor = index + 1; cursor < limit; cursor++) {
            char current = sql.charAt(cursor);
            if (current == ']') return true;
            if (current == '\'' || current == '"') return false;
        }
        return true;
    }

    private static int quotedIdentifierEnd(String sql, int index, int limit, char closing) {
        while (index < limit) {
            char current = sql.charAt(index);
            if (current == closing) {
                if (index + 1 < limit && sql.charAt(index + 1) == closing) {
                    index += 2;
                } else {
                    return index + 1;
                }
            } else {
                index++;
            }
        }
        return -1;
    }

    private static boolean isNumericStart(String sql, int index, int limit) {
        char current = sql.charAt(index);
        if (current == '.' && index + 1 < limit && Character.isDigit(sql.charAt(index + 1))) return true;
        if (!Character.isDigit(current)) return false;
        if (index > 0 && sql.charAt(index - 1) == '$') {
            return index == 1 || !isIdentifierPart(sql.charAt(index - 2));
        }
        return index == 0 || !isIdentifierPart(sql.charAt(index - 1));
    }

    private static int skipNumericToken(String sql, int index, int limit) {
        while (index < limit) {
            char current = sql.charAt(index);
            if (isIdentifierPart(current) || current == '.') {
                index++;
            } else if (current == '-' && index + 1 < limit && sql.charAt(index + 1) != '-'
                    && isIdentifierPart(sql.charAt(index + 1))) {
                index++;
            } else if (current == '+' && index > 0
                    && (sql.charAt(index - 1) == 'e' || sql.charAt(index - 1) == 'E')) {
                index++;
            } else {
                break;
            }
        }
        return index;
    }

    private static boolean isIdentifierPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$';
    }

    private static boolean isDollarTagStart(char value) {
        return Character.isLetter(value) || value == '_';
    }

    private static boolean isDollarTagPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }

    private static boolean isAsciiLetter(char value) {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
    }

    private static String bound(String value) {
        return value.length() <= MAX_SQL_LENGTH ? value : value.substring(0, MAX_SQL_LENGTH);
    }

    private static final class NormalizedSql {
        private final StringBuilder value = new StringBuilder(128);
        private boolean pendingSeparator;

        private void append(char character) {
            if (full()) return;
            if (pendingSeparator && !value.isEmpty()) {
                value.append(' ');
                pendingSeparator = false;
                if (full()) return;
            }
            value.append(character);
        }

        private void placeholder() {
            append('?');
        }

        private void appendRange(String source, int start, int end) {
            for (int index = start; index < end && !full(); index++) append(source.charAt(index));
        }

        private void separate() {
            if (!value.isEmpty()) pendingSeparator = true;
        }

        private boolean full() {
            return value.length() >= MAX_SQL_LENGTH;
        }

        private String value() {
            return value.toString();
        }
    }
}
