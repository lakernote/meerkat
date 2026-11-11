package io.github.lakernote.meerkat.metrics.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.lakernote.meerkat.metrics.internal.OperationStats.ALLOCATED;
import static io.github.lakernote.meerkat.metrics.internal.OperationStats.CELLS;
import static io.github.lakernote.meerkat.metrics.internal.OperationStats.CPU;
import static io.github.lakernote.meerkat.metrics.internal.OperationStats.ERRORS;
import static io.github.lakernote.meerkat.metrics.internal.OperationStats.FIRST_BUCKET;
import static io.github.lakernote.meerkat.metrics.internal.OperationStats.MAX;
import static io.github.lakernote.meerkat.metrics.internal.OperationStats.MEASURED;
import static io.github.lakernote.meerkat.metrics.internal.OperationStats.TOTAL;

/**
 * Append-only daily metric files: {@code operations-yyyyMMdd.tsv} with per-operation histogram aggregates and
 * {@code resources-yyyyMMdd.tsv} with sampled JVM and pool values.
 *
 * <p>Recent days keep fine buckets (10 minutes for operations, 1 minute for resources). Days older than
 * {@value #FINE_DAYS} days are rewritten once with coarser buckets (1 hour and 10 minutes), which keeps 30 days in a
 * few tens of megabytes. Histograms merge exactly, so averages and P95 stay correct at any resolution; resource
 * values merge by their {@link ResourceMeters#rollup rollup}, so counts, peaks and limits survive too. Days beyond
 * the retention, or the oldest days beyond the size budget, are deleted. Any write failure turns persistence off
 * and metrics stay in memory.</p>
 */
public final class MetricsArchive {
    static final int FINE_DAYS = 2;
    static final int TOTALS_CACHE_ENTRIES = 64;
    static final String OPERATIONS = "operations";
    static final String RESOURCES = "resources";
    private static final Logger log = LoggerFactory.getLogger(MetricsArchive.class);
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String SUFFIX = ".tsv";

    private final Path directory;
    private final long maxBytes;
    private final int retentionDays;
    private final ZoneId zone;
    private final AtomicBoolean budgetWarned = new AtomicBoolean();
    private volatile boolean available;
    private String closedDaysSignature;
    private Map<OperationStats.Category, Map<String, long[]>> closedDays;
    private long todaySize = -1;
    private LocalDate todayDate;
    private Map<OperationStats.Category, Map<String, long[]>> today;
    /** Per-file bucket totals for trends, keyed by file and filter; small, and only reused while the file is unchanged. */
    private final Map<String, CachedTotals> totalsCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedTotals> eldest) {
            return size() > TOTALS_CACHE_ENTRIES;
        }
    };

    private record CachedTotals(long size, long modifiedMillis, List<BucketTotal> rows) { }

    private record BucketTotal(long start, OperationStats.Category category, long[] cells) { }

    /** One operation's cells for one bucket, as collected from memory. */
    record OperationRow(OperationStats.Category category, String name, long[] cells) { }

    @FunctionalInterface
    interface OperationVisitor {
        void visit(long bucketStartMillis, OperationStats.Category category, String name, long[] cells);
    }

    @FunctionalInterface
    interface ResourceVisitor {
        void visit(long timestampMillis, Map<String, Double> values);
    }

    public record StorageStatus(boolean persistent, long storageBytes, long maxStorageBytes, int retentionDays) { }

    /** @param directory archive directory, or {@code null} to keep metrics in memory only */
    public MetricsArchive(Path directory, long maxBytes, int retentionDays, ZoneId zone) {
        this.directory = directory == null ? null : directory.toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
        this.retentionDays = retentionDays;
        this.zone = zone;
        if (this.directory == null) return;
        try {
            Files.createDirectories(this.directory);
            available = true;
        } catch (IOException | RuntimeException failure) {
            disable("create the metrics directory", failure);
        }
    }

    public boolean available() {
        return available;
    }

    LocalDate date(long epochMillis) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), zone);
    }

    /** Appends one bucket of operation aggregates. Returns false when persistence is off. */
    synchronized boolean appendOperations(long bucketStartMillis, List<OperationRow> rows, LocalDate today) {
        if (!available) return false;
        if (rows.isEmpty()) return true;
        StringBuilder text = new StringBuilder(rows.size() * 128);
        for (OperationRow row : rows) {
            appendOperationLine(text, bucketStartMillis, row.category(), row.name(), row.cells());
        }
        return append(OPERATIONS, date(bucketStartMillis), OperationStats.SLICE_MILLIS / 1000, text, today);
    }

    /** Appends one resource sample. Returns false when persistence is off. */
    synchronized boolean appendResources(long timestampMillis, Map<String, Double> values, LocalDate today) {
        if (!available) return false;
        if (values.isEmpty()) return true;
        StringBuilder text = new StringBuilder(values.size() * 24);
        appendResourceLine(text, timestampMillis, values);
        return append(RESOURCES, date(timestampMillis), 60, text, today);
    }

    /** Visits every archived operation bucket recorded on the local days {@code from} through {@code to}. */
    synchronized void scanOperations(LocalDate from, LocalDate to, OperationVisitor visitor) {
        if (!available) return;
        try {
            for (Map.Entry<LocalDate, Path> file : files(OPERATIONS).entrySet()) {
                if (file.getKey().isBefore(from) || file.getKey().isAfter(to)) continue;
                parseOperations(file.getValue(), visitor);
            }
        } catch (IOException | RuntimeException failure) {
            log.warn("Meerkat could not read operation metrics from {}: {}", directory, failure.toString());
        }
    }

    /**
     * Visits the archived buckets of the local days {@code from} through {@code to}, summed per bucket and category
     * over one operation ({@code onlyName}, with its category) or over every operation (both {@code null}); the
     * visitor gets {@code onlyName} as the name and must not modify the cells. Each file is parsed once and reused
     * until its size or modification time changes, so trend polls do not re-read unchanged days.
     */
    synchronized void scanOperationTotals(LocalDate from, LocalDate to, OperationStats.Category onlyCategory,
                                          String onlyName, OperationVisitor visitor) {
        if (!available) return;
        try {
            for (Map.Entry<LocalDate, Path> file : files(OPERATIONS).entrySet()) {
                if (file.getKey().isBefore(from) || file.getKey().isAfter(to)) continue;
                for (BucketTotal row : totals(file.getValue(), onlyCategory, onlyName)) {
                    visitor.visit(row.start(), row.category(), onlyName, row.cells());
                }
            }
        } catch (IOException | RuntimeException failure) {
            log.warn("Meerkat could not read operation metrics from {}: {}", directory, failure.toString());
        }
    }

    private List<BucketTotal> totals(Path file, OperationStats.Category onlyCategory, String onlyName)
            throws IOException {
        String key = file.getFileName() + "\n" + onlyCategory + "\n" + onlyName;
        long size = Files.size(file);
        long modified = Files.getLastModifiedTime(file).toMillis();
        CachedTotals cached = totalsCache.get(key);
        if (cached != null && cached.size() == size && cached.modifiedMillis() == modified) return cached.rows();
        Map<Long, Map<OperationStats.Category, long[]>> buckets = new TreeMap<>();
        parseOperations(file, (start, category, name, cells) -> {
            if (onlyCategory != null && category != onlyCategory) return;
            if (onlyName != null && !onlyName.equals(name)) return;
            long[] into = buckets.computeIfAbsent(start, ignored -> new EnumMap<>(OperationStats.Category.class))
                    .computeIfAbsent(category, ignored -> new long[CELLS]);
            OperationStats.mergeCells(into, cells);
        });
        List<BucketTotal> rows = new ArrayList<>();
        buckets.forEach((start, categories) -> categories.forEach((category, cells) ->
                rows.add(new BucketTotal(start, category, cells))));
        totalsCache.put(key, new CachedTotals(size, modified, List.copyOf(rows)));
        return rows;
    }

    /** Visits every archived resource sample recorded on the local days {@code from} through {@code to}. */
    synchronized void scanResources(LocalDate from, LocalDate to, ResourceVisitor visitor) {
        if (!available) return;
        try {
            for (Map.Entry<LocalDate, Path> file : files(RESOURCES).entrySet()) {
                if (file.getKey().isBefore(from) || file.getKey().isAfter(to)) continue;
                parseResources(file.getValue(), visitor);
            }
        } catch (IOException | RuntimeException failure) {
            log.warn("Meerkat could not read resource metrics from {}: {}", directory, failure.toString());
        }
    }

    /**
     * Sums every archived operation recorded on the local days from {@code from} through {@code today}. Closed days
     * are cached until their files change, so ranking refreshes only re-read today's file.
     */
    synchronized Map<OperationStats.Category, Map<String, long[]>> rankOperations(LocalDate from, LocalDate today) {
        Map<OperationStats.Category, Map<String, long[]>> result = emptyResult();
        if (!available) return result;
        try {
            TreeMap<LocalDate, Path> files = files(OPERATIONS);
            StringBuilder signature = new StringBuilder(from.toString());
            List<Path> closed = new ArrayList<>();
            for (Map.Entry<LocalDate, Path> file : files.entrySet()) {
                if (file.getKey().isBefore(from) || !file.getKey().isBefore(today)) continue;
                closed.add(file.getValue());
                signature.append('|').append(file.getKey()).append(':').append(Files.size(file.getValue()));
            }
            if (!signature.toString().equals(closedDaysSignature)) {
                Map<OperationStats.Category, Map<String, long[]>> merged = emptyResult();
                for (Path file : closed) parseOperations(file, sumInto(merged));
                closedDays = merged;
                closedDaysSignature = signature.toString();
            }
            Path todayFile = files.get(today);
            long size = todayFile == null ? -1 : Files.size(todayFile);
            if (!today.equals(todayDate) || size != todaySize) {
                Map<OperationStats.Category, Map<String, long[]>> parsed = emptyResult();
                if (todayFile != null) parseOperations(todayFile, sumInto(parsed));
                this.today = parsed;
                todayDate = today;
                todaySize = size;
            }
            merge(result, closedDays);
            merge(result, this.today);
        } catch (IOException | RuntimeException failure) {
            log.warn("Meerkat could not read operation metrics from {}: {}", directory, failure.toString());
        }
        return result;
    }

    /** Rewrites days older than {@value #FINE_DAYS} days with coarser buckets; each day is rewritten once. */
    synchronized void compact(LocalDate today) {
        if (!available) return;
        LocalDate newestCoarse = today.minusDays(FINE_DAYS);
        try {
            for (Map.Entry<LocalDate, Path> file : files(OPERATIONS).entrySet()) {
                if (!file.getKey().isAfter(newestCoarse) && bucketSeconds(file.getValue()) < 3600) {
                    compactOperations(file.getValue());
                }
            }
            for (Map.Entry<LocalDate, Path> file : files(RESOURCES).entrySet()) {
                if (!file.getKey().isAfter(newestCoarse) && bucketSeconds(file.getValue()) < 600) {
                    compactResources(file.getValue());
                }
            }
        } catch (IOException | RuntimeException failure) {
            disable("compact metrics history", failure);
        }
    }

    /** Deletes the saved operation history; resource history is kept. */
    synchronized void clearOperations() {
        totalsCache.clear();
        closedDays = null;
        closedDaysSignature = null;
        today = null;
        todayDate = null;
        todaySize = -1;
        if (!available) return;
        try {
            for (Path file : files(OPERATIONS).values()) Files.deleteIfExists(file);
        } catch (IOException | RuntimeException failure) {
            disable("delete operation metrics", failure);
        }
    }

    public synchronized StorageStatus status() {
        long bytes = 0;
        if (available) {
            try {
                for (Path file : files(OPERATIONS).values()) bytes += Files.size(file);
                for (Path file : files(RESOURCES).values()) bytes += Files.size(file);
            } catch (IOException | RuntimeException ignored) {
                // Report what could be measured.
            }
        }
        return new StorageStatus(available, bytes, maxBytes, retentionDays);
    }

    private boolean append(String kind, LocalDate date, long bucketSeconds, CharSequence text, LocalDate today) {
        Path file = file(kind, date);
        try {
            boolean fresh = !Files.exists(file);
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (fresh) writer.write(header(kind, bucketSeconds));
                writer.append(text);
            }
            enforceLimits(today);
            return true;
        } catch (IOException | RuntimeException failure) {
            disable("write metrics", failure);
            return false;
        }
    }

    private void compactOperations(Path file) throws IOException {
        Map<String, Object[]> buckets = new LinkedHashMap<>();
        parseOperations(file, (start, category, name, cells) -> {
            long hour = Math.floorDiv(start, OperationStats.HOUR_MILLIS) * OperationStats.HOUR_MILLIS;
            Object[] entry = buckets.computeIfAbsent(hour + "\t" + category + "\t" + name,
                    ignored -> new Object[] {hour, category, name, new long[CELLS]});
            OperationStats.mergeCells((long[]) entry[3], cells);
        });
        StringBuilder text = new StringBuilder(header(OPERATIONS, 3600));
        for (Object[] entry : buckets.values()) {
            appendOperationLine(text, (Long) entry[0], (OperationStats.Category) entry[1], (String) entry[2],
                    (long[]) entry[3]);
        }
        replace(file, text);
    }

    private void compactResources(Path file) throws IOException {
        Map<Long, Map<String, double[]>> buckets = new TreeMap<>();
        parseResources(file, (timestamp, values) -> {
            long bucket = Math.floorDiv(timestamp, 600_000L) * 600_000L;
            Map<String, double[]> cells = buckets.computeIfAbsent(bucket, ignored -> new LinkedHashMap<>());
            values.forEach((key, value) -> ResourceMeters.fold(ResourceMeters.rollup(key),
                    cells.computeIfAbsent(key, ignored -> new double[2]), 0, 1, value));
        });
        StringBuilder text = new StringBuilder(header(RESOURCES, 600));
        buckets.forEach((bucket, cells) -> {
            Map<String, Double> values = new LinkedHashMap<>();
            cells.forEach((key, cell) ->
                    values.put(key, ResourceMeters.result(ResourceMeters.rollup(key), cell, 0, 1)));
            appendResourceLine(text, bucket, values);
        });
        replace(file, text);
    }

    private void replace(Path file, CharSequence text) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void enforceLimits(LocalDate today) throws IOException {
        TreeMap<LocalDate, List<Path>> days = new TreeMap<>();
        files(OPERATIONS).forEach((date, file) -> days.computeIfAbsent(date, ignored -> new ArrayList<>()).add(file));
        files(RESOURCES).forEach((date, file) -> days.computeIfAbsent(date, ignored -> new ArrayList<>()).add(file));
        LocalDate oldestKept = today.minusDays(retentionDays - 1L);
        long total = 0;
        for (Map.Entry<LocalDate, List<Path>> day : new ArrayList<>(days.entrySet())) {
            if (day.getKey().isBefore(oldestKept)) {
                for (Path file : day.getValue()) Files.deleteIfExists(file);
                days.remove(day.getKey());
            } else {
                for (Path file : day.getValue()) total += Files.size(file);
            }
        }
        while (total > maxBytes && days.size() > 1) {
            Map.Entry<LocalDate, List<Path>> oldest = days.pollFirstEntry();
            for (Path file : oldest.getValue()) {
                total -= Files.size(file);
                Files.deleteIfExists(file);
            }
            if (budgetWarned.compareAndSet(false, true)) {
                log.warn("Meerkat metrics exceed meerkat.metrics.max-storage-size; deleting the "
                        + "oldest days, so long ranges cover fewer days than requested");
            }
        }
    }

    private TreeMap<LocalDate, Path> files(String kind) throws IOException {
        TreeMap<LocalDate, Path> files = new TreeMap<>();
        String prefix = kind + "-";
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, prefix + "*" + SUFFIX)) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                try {
                    files.put(LocalDate.parse(name.substring(prefix.length(), name.length() - SUFFIX.length()),
                            FILE_DATE), file);
                } catch (DateTimeParseException | IndexOutOfBoundsException ignored) {
                    // Not one of ours.
                }
            }
        }
        return files;
    }

    private Path file(String kind, LocalDate date) {
        return directory.resolve(kind + "-" + FILE_DATE.format(date) + SUFFIX);
    }

    private static String header(String kind, long bucketSeconds) {
        return "# meerkat " + kind + (kind.equals(RESOURCES) ? " v2" : " v1") + " bucket=" + bucketSeconds + "\n";
    }

    private static long bucketSeconds(Path file) throws IOException {
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String first = reader.readLine();
            int index = first == null ? -1 : first.lastIndexOf("bucket=");
            if (index < 0) return 0;
            try {
                return Long.parseLong(first.substring(index + "bucket=".length()).trim());
            } catch (NumberFormatException corrupt) {
                return 0;
            }
        }
    }

    private static void appendOperationLine(StringBuilder line, long bucketStart, OperationStats.Category category,
                                            String name, long[] cells) {
        line.append(bucketStart).append('\t').append(category.name()).append('\t')
                .append(cells[ERRORS]).append('\t').append(cells[TOTAL]).append('\t').append(cells[MAX]).append('\t')
                .append(cells[CPU]).append('\t').append(cells[ALLOCATED]).append('\t').append(cells[MEASURED])
                .append('\t');
        boolean first = true;
        for (int cell = FIRST_BUCKET; cell < CELLS; cell++) {
            if (cells[cell] == 0) continue;
            if (!first) line.append(';');
            line.append(cell - FIRST_BUCKET).append(':').append(cells[cell]);
            first = false;
        }
        line.append('\t').append(escape(name)).append('\n');
    }

    private static void appendResourceLine(StringBuilder line, long timestamp, Map<String, Double> values) {
        line.append(timestamp).append('\t');
        boolean first = true;
        for (Map.Entry<String, Double> value : values.entrySet()) {
            if (value.getValue() == null || !Double.isFinite(value.getValue())) continue;
            if (!first) line.append(';');
            line.append(value.getKey()).append('=').append(format(value.getValue()));
            first = false;
        }
        line.append('\n');
    }

    static String format(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) return Long.toString((long) value);
        String text = String.format(Locale.ROOT, "%.3f", value);
        int end = text.length();
        while (text.charAt(end - 1) == '0') end--;
        return text.substring(0, end);
    }

    private static void parseOperations(Path file, OperationVisitor visitor) throws IOException {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != 10) continue;
            try {
                long start = Long.parseLong(fields[0]);
                OperationStats.Category category = OperationStats.Category.valueOf(fields[1]);
                long[] cells = new long[CELLS];
                cells[ERRORS] = Long.parseLong(fields[2]);
                cells[TOTAL] = Long.parseLong(fields[3]);
                cells[MAX] = Long.parseLong(fields[4]);
                cells[CPU] = Long.parseLong(fields[5]);
                cells[ALLOCATED] = Long.parseLong(fields[6]);
                cells[MEASURED] = Long.parseLong(fields[7]);
                if (!fields[8].isEmpty()) {
                    for (String bucket : fields[8].split(";")) {
                        int colon = bucket.indexOf(':');
                        int index = FIRST_BUCKET + Integer.parseInt(bucket.substring(0, colon));
                        if (index < FIRST_BUCKET || index >= CELLS) throw new IllegalArgumentException(bucket);
                        cells[index] += Long.parseLong(bucket.substring(colon + 1));
                    }
                }
                OperationStats.deriveCount(cells);
                visitor.visit(start, category, unescape(fields[9]), cells);
            } catch (RuntimeException corrupt) {
                // A torn or foreign line must not hide the rest of the history.
            }
        }
    }

    private static void parseResources(Path file, ResourceVisitor visitor) throws IOException {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            int tab = line.indexOf('\t');
            if (tab < 0) continue;
            try {
                long timestamp = Long.parseLong(line.substring(0, tab));
                Map<String, Double> values = new HashMap<>();
                for (String pair : line.substring(tab + 1).split(";")) {
                    int equals = pair.indexOf('=');
                    if (equals <= 0) continue;
                    values.put(pair.substring(0, equals), Double.parseDouble(pair.substring(equals + 1)));
                }
                visitor.visit(timestamp, values);
            } catch (RuntimeException corrupt) {
                // A torn or foreign line must not hide the rest of the history.
            }
        }
    }

    private static OperationVisitor sumInto(Map<OperationStats.Category, Map<String, long[]>> into) {
        return (start, category, name, cells) -> add(into, category, name, cells);
    }

    private static void merge(Map<OperationStats.Category, Map<String, long[]>> into,
                              Map<OperationStats.Category, Map<String, long[]>> source) {
        if (source == null) return;
        source.forEach((category, entries) -> entries.forEach((name, cells) -> add(into, category, name, cells)));
    }

    static void add(Map<OperationStats.Category, Map<String, long[]>> into, OperationStats.Category category,
                    String name, long[] cells) {
        into.get(category).compute(name, (ignored, existing) -> {
            if (existing == null) return cells.clone();
            OperationStats.mergeCells(existing, cells);
            return existing;
        });
    }

    static Map<OperationStats.Category, Map<String, long[]>> emptyResult() {
        Map<OperationStats.Category, Map<String, long[]>> result = new EnumMap<>(OperationStats.Category.class);
        for (OperationStats.Category category : OperationStats.Category.values()) result.put(category, new HashMap<>());
        return result;
    }

    static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '\t' -> escaped.append("\\t");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    static String unescape(String value) {
        if (value.indexOf('\\') < 0) return value;
        StringBuilder plain = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 == value.length()) {
                plain.append(c);
                continue;
            }
            char next = value.charAt(++i);
            plain.append(switch (next) {
                case 't' -> '\t';
                case 'n' -> '\n';
                case 'r' -> '\r';
                default -> next;
            });
        }
        return plain.toString();
    }

    private void disable(String action, Throwable failure) {
        available = false;
        log.warn("Meerkat could not {} in {}; metrics are kept in memory only: {}",
                action, directory, failure.toString());
    }
}
