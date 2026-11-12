package io.github.lakernote.meerkat.trace.internal.store;

import io.github.lakernote.meerkat.trace.internal.BoundedHistoryWriter;
import io.github.lakernote.meerkat.trace.internal.TraceNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the slow, failed and resource-heavy entry traces: a light in-memory index for listing and searching, the
 * newest full records in memory, and every record in daily segment files on disk.
 *
 * <p>Segments are named {@code traces-yyyyMMdd-N.jsonl}, roll per day or when a segment is full, and are deleted
 * whole once they fall out of the retention period or the storage budget, so old data never has to be rewritten.
 * Business threads only touch memory; encoding and file I/O happen on a single bounded background writer. Any
 * storage failure turns the store into a memory-only store until the application restarts.</p>
 */
public final class TraceStore implements AutoCloseable {
    static final int MAX_INDEXED_TRACES = 50_000;
    /** Heap budget for the list index; past it the oldest traces are dropped from the list, like the count cap. */
    static final long MAX_INDEX_BYTES = 16L * 1024 * 1024;
    static final long MAX_SEGMENT_BYTES = 8L * 1024 * 1024;
    static final long MIN_SEGMENT_BYTES = 256L * 1024;
    static final long SEARCH_SCAN_BYTES = 32L * 1024 * 1024;
    static final int MAX_PAGE_SIZE = 200;
    static final int MAX_QUERY_CHARS = 200;
    /** Retention and the storage budget are also checked this often, so a quiet store still drops expired days. */
    static final long LIMITS_CHECK_MILLIS = 60_000;
    private static final Logger log = LoggerFactory.getLogger(TraceStore.class);
    private static final Pattern SEGMENT_NAME = Pattern.compile("traces-(\\d{8})-(\\d+)\\.jsonl");
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int READ_BUFFER_BYTES = 64 * 1024;

    private final TraceRecordJsonCodec codec;
    private final Path directory;
    private final int retentionDays;
    private final long maxStorageBytes;
    private final long segmentBytes;
    private final long hotBudgetBytes;
    private final Predicate<Runnable> writer;
    private final Runnable writerShutdown;
    private final Clock clock;
    private final ZoneId zone;
    private long searchScanBytes = SEARCH_SCAN_BYTES;
    private volatile int maxPerOperationPerMinute;
    /** Traces kept this minute per operation and outcome; replaced as a whole when the minute changes. */
    private final AtomicReference<Window> window = new AtomicReference<>(new Window(Long.MIN_VALUE));
    private final AtomicLong throttled = new AtomicLong();

    /** Newest first. Values are mutated only while holding {@link #stateLock}. */
    private final ConcurrentSkipListMap<Key, Entry> index = new ConcurrentSkipListMap<>();
    private final AtomicInteger indexSize = new AtomicInteger();
    private long indexBytes;
    private long maxIndexBytes = MAX_INDEX_BYTES;
    private final ArrayDeque<Entry> hot = new ArrayDeque<>();
    private long hotBytes;
    /** Records waiting for the writer; kept within the hot budget so a slow disk cannot pin unbounded heap. */
    private long queuedBytes;
    private final AtomicLong nextId = new AtomicLong();
    private final AtomicLong restoredSequence = new AtomicLong();
    private final AtomicLong limitsCheckedAtMillis = new AtomicLong();
    private final Object stateLock = new Object();
    private long generation;
    private volatile boolean persistent;
    private volatile boolean restoring;
    private volatile Thread restoreThread;
    private boolean warningLogged;
    private boolean oversizedWarningLogged;

    /** Segment bookkeeping, guarded by {@link #fileLock}. */
    private final Object fileLock = new Object();
    private final TreeMap<SegmentId, Segment> segments = new TreeMap<>();
    private Segment current;
    private FileChannel currentChannel;

    /**
     * @param directory     segment directory, or {@code null} to keep traces in memory only
     * @param hotBudgetBytes estimated heap budget for the newest full records kept in memory; the newest record is
     *                       always kept
     */
    public TraceStore(Path directory, int retentionDays, long maxStorageBytes, long hotBudgetBytes) {
        this(directory, retentionDays, maxStorageBytes, hotBudgetBytes, new BoundedHistoryWriter());
    }

    private TraceStore(Path directory, int retentionDays, long maxStorageBytes, long hotBudgetBytes,
                       BoundedHistoryWriter writer) {
        this(TraceRecordJsonCodec.INSTANCE, directory, retentionDays, maxStorageBytes, hotBudgetBytes,
                writer::execute, writer::close, Clock.systemDefaultZone());
    }

    TraceStore(TraceRecordJsonCodec codec, Path directory, int retentionDays, long maxStorageBytes, long hotBudgetBytes,
               Predicate<Runnable> writer, Runnable writerShutdown, Clock clock) {
        this.codec = codec;
        this.directory = directory == null ? null : directory.toAbsolutePath().normalize();
        this.retentionDays = Math.max(1, retentionDays);
        this.maxStorageBytes = Math.max(MIN_SEGMENT_BYTES, maxStorageBytes);
        this.segmentBytes = Math.max(MIN_SEGMENT_BYTES, Math.min(MAX_SEGMENT_BYTES, this.maxStorageBytes / 8));
        this.hotBudgetBytes = Math.max(0, hotBudgetBytes);
        this.writer = writer;
        this.writerShutdown = writerShutdown;
        this.clock = clock;
        this.zone = clock.getZone();
        this.persistent = directory != null;
    }

    /** A store that keeps only the newest traces that fit in {@code budgetBytes} of estimated heap. */
    public static TraceStore inMemory(long budgetBytes) {
        return new TraceStore(TraceRecordJsonCodec.INSTANCE, null, 1, MIN_SEGMENT_BYTES, budgetBytes,
                task -> false, () -> { }, Clock.systemDefaultZone());
    }

    /** Opens the segment directory and rebuilds the index from existing segments on a background thread. */
    public void start() {
        if (!persistent) return;
        List<Segment> existing;
        synchronized (fileLock) {
            try {
                Files.createDirectories(directory);
                loadSegments();
                enforceLimits();
                limitsCheckedAtMillis.set(clock.millis());
                resumeLastSegment();
            } catch (Exception failure) {
                disablePersistence("open trace storage", failure);
                return;
            }
            existing = new ArrayList<>(segments.descendingMap().values());
        }
        if (existing.isEmpty()) return;
        long startGeneration;
        synchronized (stateLock) {
            startGeneration = generation;
        }
        restoring = true;
        Thread thread = new Thread(() -> restoreIndex(existing, startGeneration), "meerkat-trace-index");
        thread.setDaemon(true);
        restoreThread = thread;
        thread.start();
    }

    /**
     * Keeps at most {@code perMinute} traces per minute for each operation and outcome (error type, slow or
     * resource), so one failing endpoint cannot push every other trace out of the store. Zero or below disables
     * the limit.
     */
    public TraceStore limitPerOperation(int perMinute) {
        this.maxPerOperationPerMinute = perMinute;
        return this;
    }

    /** Stores a completed entry trace, assigning its record ID and completion time. */
    public TraceRecord add(TraceRecord trace) {
        if (trace == null || !isEntrySource(trace.sourceType()) || !isDiagnostic(trace)) return null;
        enforceLimitsPeriodically();
        Instant finishedAt = clock.instant();
        if (!admit(trace, finishedAt.toEpochMilli())) {
            throttled.incrementAndGet();
            return null;
        }
        TraceRecord stored = trace.withIdentity(nextId.incrementAndGet(), finishedAt);
        Entry entry = new Entry(new Key(finishedAt.toEpochMilli(), stored.id()), TraceSummary.of(stored), stored);
        long writeGeneration;
        synchronized (stateLock) {
            insertLocked(entry);
            entry.hot = true;
            hot.addFirst(entry);
            hotBytes += entry.estimatedBytes;
            while (hotBytes > hotBudgetBytes && hot.size() > 1) {
                Entry oldest = hot.pollLast();
                hotBytes -= oldest.estimatedBytes;
                evictLocked(oldest);
            }
            if (!persistent) return stored;
            if (queuedBytes + entry.estimatedBytes > hotBudgetBytes && queuedBytes > 0) return stored;
            entry.pendingWrite = true;
            queuedBytes += entry.estimatedBytes;
            writeGeneration = generation;
        }
        boolean queued;
        try {
            queued = writer.test(() -> append(entry, stored, writeGeneration));
        } catch (RuntimeException failure) {
            queued = false;
            disablePersistence("schedule trace write", failure);
        }
        if (!queued) finishWrite(entry, null);
        return stored;
    }

    /** Lists traces newest first, matching the filters; {@code query.before} continues a previous page. */
    public TracePage search(TraceQuery query) {
        enforceLimitsPeriodically();
        String text = normalizeQuery(query.text());
        Key before = Key.parse(query.before());
        int limit = Math.max(1, Math.min(query.limit(), MAX_PAGE_SIZE));
        NavigableMap<Key, Entry> view = before == null ? index : index.tailMap(before, false);
        List<TraceSummary> items = new ArrayList<>(Math.min(limit, 64));
        Key last = null;
        long scannedBytes = 0;
        boolean scanLimited = false;
        byte[] needle = asciiNeedle(text);
        try (SegmentReader reader = new SegmentReader()) {
            for (Entry entry : view.values()) {
                if (items.size() == limit) break;
                if (!query.accepts(entry.summary)) {
                    last = entry.key;
                    continue;
                }
                boolean matches = text.isEmpty() || matchesSummary(entry.summary, text);
                if (!matches) {
                    TraceRecord full = entry.full;
                    if (full == null) {
                        Location location = entry.location;
                        if (location == null) {
                            last = entry.key;
                            continue;
                        }
                        if (scannedBytes + location.length() > searchScanBytes && scannedBytes > 0) {
                            scanLimited = true;
                            break;
                        }
                        scannedBytes += location.length();
                        byte[] bytes = reader.read(location);
                        if (bytes != null && (needle == null || containsIgnoreAsciiCase(bytes, needle))) {
                            full = decode(bytes);
                        }
                    }
                    matches = full != null && matchesNodes(full.treeNodes(), text);
                }
                last = entry.key;
                if (matches) items.add(entry.summary);
            }
        }
        String next = null;
        if (last != null && (scanLimited || index.higherKey(last) != null)) next = last.cursor();
        return new TracePage(List.copyOf(items), next, scanLimited, text.isEmpty() ? count(query) : -1);
    }

    private int count(TraceQuery query) {
        int count = 0;
        for (Entry entry : index.values()) if (query.accepts(entry.summary)) count++;
        return count;
    }

    /** Visits every indexed trace row, newest first, without loading call trees; the index is bounded. */
    public void forEachSummary(java.util.function.Consumer<TraceSummary> visitor) {
        for (Entry entry : index.values()) visitor.accept(entry.summary);
    }

    /** Finds the newest stored trace with the ID; without a source an HTTP entry is preferred. */
    public TraceRecord find(String traceId, String sourceType) {
        if (traceId == null || traceId.isBlank()) return null;
        boolean anySource = sourceType == null || sourceType.isBlank();
        Entry fallback = null;
        for (Entry entry : index.values()) {
            if (!traceId.equals(entry.summary.traceId())) continue;
            if (anySource) {
                if ("HTTP".equals(entry.summary.sourceType())) return load(entry);
                if (fallback == null) fallback = entry;
            } else if (sourceType.equalsIgnoreCase(entry.summary.sourceType())) {
                return load(entry);
            }
        }
        return fallback == null ? null : load(fallback);
    }

    /** The newest stored traces with their call trees, newest first. */
    public List<TraceRecord> recent(int limit) {
        List<TraceRecord> result = new ArrayList<>();
        for (Entry entry : index.values()) {
            if (result.size() >= limit) break;
            TraceRecord trace = load(entry);
            if (trace != null) result.add(trace);
        }
        return List.copyOf(result);
    }

    public int size() {
        return indexSize.get();
    }

    private TraceRecord load(Entry entry) {
        TraceRecord full = entry.full;
        if (full != null) return full;
        Location location = entry.location;
        if (location == null) return null;
        try (SegmentReader reader = new SegmentReader()) {
            byte[] bytes = reader.read(location);
            return bytes == null ? null : decode(bytes);
        }
    }

    public StorageStatus storageStatus() {
        enforceLimitsPeriodically();
        long bytes = 0;
        synchronized (fileLock) {
            for (Segment segment : segments.values()) bytes += segment.size;
        }
        return new StorageStatus(persistent, persistent ? bytes : 0, maxStorageBytes, retentionDays,
                indexSize.get(), restoring, throttled.get());
    }

    /**
     * Deletes every stored trace; writes queued before the call are discarded.
     *
     * @throws java.io.UncheckedIOException when a segment file could not be deleted; it stays on disk and is
     *         retried by the next clear or retention check
     */
    public int clear() {
        synchronized (fileLock) {
            int cleared;
            synchronized (stateLock) {
                generation++;
                cleared = indexSize.getAndSet(0);
                indexBytes = 0;
                index.clear();
                hot.clear();
                hotBytes = 0;
            }
            closeCurrent();
            IOException failure = null;
            var iterator = segments.values().iterator();
            while (iterator.hasNext()) {
                Segment segment = iterator.next();
                try {
                    delete(segment);
                    iterator.remove();
                } catch (IOException deleteFailure) {
                    if (failure == null) failure = deleteFailure;
                    else failure.addSuppressed(deleteFailure);
                }
            }
            if (failure != null) {
                throw new java.io.UncheckedIOException("Could not delete trace segments in " + directory, failure);
            }
            return cleared;
        }
    }

    /** Stops the index rebuild, flushes queued writes, then closes the open segment. */
    @Override
    public void close() {
        synchronized (stateLock) {
            generation++;
        }
        Thread restore = restoreThread;
        if (restore != null) {
            restore.interrupt();
            try {
                restore.join(TimeUnit.SECONDS.toMillis(3));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        writerShutdown.run();
        synchronized (fileLock) {
            closeCurrent();
        }
    }

    void searchScanBytes(long bytes) {
        searchScanBytes = bytes;
    }

    void maxIndexBytes(long bytes) {
        synchronized (stateLock) {
            maxIndexBytes = bytes;
        }
    }

    private boolean indexFull() {
        synchronized (stateLock) {
            return indexSize.get() >= MAX_INDEXED_TRACES || indexBytes >= maxIndexBytes;
        }
    }

    private boolean admit(TraceRecord trace, long nowMillis) {
        int limit = maxPerOperationPerMinute;
        if (limit <= 0) return true;
        long minute = Math.floorDiv(nowMillis, 60_000L);
        Window current = window.get();
        if (current.minute != minute) {
            Window next = new Window(minute);
            current = window.compareAndSet(current, next) ? next : window.get();
        }
        String outcome = trace.error() ? trace.errorType() : trace.slow() ? "SLOW" : "RESOURCE";
        String key = trace.sourceType() + '\n' + trace.name() + '\n' + outcome;
        AtomicInteger kept = current.kept.get(key);
        if (kept == null) {
            // Past the key cap, new keys share one budget so a storm of distinct names stays bounded too.
            if (current.kept.size() >= Window.MAX_KEYS) key = "";
            kept = current.kept.computeIfAbsent(key, ignored -> new AtomicInteger());
        }
        return kept.incrementAndGet() <= limit;
    }

    private static final class Window {
        static final int MAX_KEYS = 4_096;
        final long minute;
        final ConcurrentHashMap<String, AtomicInteger> kept = new ConcurrentHashMap<>();

        Window(long minute) {
            this.minute = minute;
        }
    }

    public static boolean isEntrySource(String sourceType) {
        return "HTTP".equals(sourceType) || "KAFKA_CONSUMER".equals(sourceType) || "JOB".equals(sourceType);
    }

    private static boolean isDiagnostic(TraceRecord trace) {
        return trace.slow() || trace.error() || trace.highCpu() || trace.highAllocation();
    }

    // ---- in-memory index ----

    private void insertLocked(Entry entry) {
        if (index.put(entry.key, entry) != null) return;
        indexSize.incrementAndGet();
        indexBytes += entry.indexBytes;
        while ((indexSize.get() > MAX_INDEXED_TRACES || indexBytes > maxIndexBytes) && indexSize.get() > 1) {
            var oldest = index.pollLastEntry();
            if (oldest == null) break;
            indexSize.decrementAndGet();
            indexBytes -= oldest.getValue().indexBytes;
            oldest.getValue().removed = true;
        }
    }

    private void removeLocked(Entry entry) {
        entry.removed = true;
        if (index.remove(entry.key, entry)) {
            indexSize.decrementAndGet();
            indexBytes -= entry.indexBytes;
        }
    }

    /** A record leaving the hot window stays listed only when it can be read back from disk. */
    private void evictLocked(Entry entry) {
        if (entry == null) return;
        entry.hot = false;
        if (entry.location != null) entry.full = null;
        else if (!entry.pendingWrite) removeLocked(entry);
    }

    private void finishWrite(Entry entry, Location location) {
        synchronized (stateLock) {
            if (entry.pendingWrite) queuedBytes -= entry.estimatedBytes;
            entry.pendingWrite = false;
            if (location != null && !entry.removed) entry.location = location;
            if (!entry.hot) evictLocked(entry);
        }
    }

    // ---- segment files ----

    private void append(Entry entry, TraceRecord trace, long writeGeneration) {
        Location location = null;
        synchronized (fileLock) {
            try {
                if (!shouldWrite(writeGeneration)) return;
                byte[] bytes = encode(trace);
                if (bytes == null) return;
                LocalDate day = LocalDate.ofInstant(trace.finishedAt(), zone);
                Segment segment = segmentFor(day, bytes.length);
                long offset = segment.size;
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    if (currentChannel.write(buffer) == 0) throw new EOFException("Could not write trace segment");
                }
                segment.size += bytes.length;
                location = new Location(segment, offset, bytes.length - 1);
            } catch (Exception failure) {
                disablePersistence("write trace segment", failure);
            } finally {
                finishWrite(entry, location);
            }
        }
    }

    private byte[] encode(TraceRecord trace) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
        codec.write(output, trace);
        output.write('\n');
        if (output.size() <= segmentBytes) return output.toByteArray();
        if (!oversizedWarningLogged) {
            oversizedWarningLogged = true;
            log.warn("Meerkat trace {} is larger than one trace segment ({} bytes) and is kept in memory "
                    + "only", trace.traceId(), segmentBytes);
        }
        return null;
    }

    private Segment segmentFor(LocalDate day, int length) throws IOException {
        if (current != null && currentChannel != null && currentChannel.isOpen() && !day.isAfter(current.id.day())
                && current.size + length <= segmentBytes) {
            return current;
        }
        closeCurrent();
        int sequence = 1;
        var lastOfDay = segments.floorKey(new SegmentId(day, Integer.MAX_VALUE));
        if (lastOfDay != null && lastOfDay.day().equals(day)) sequence = lastOfDay.sequence() + 1;
        SegmentId id = new SegmentId(day, sequence);
        Segment segment = new Segment(id, directory.resolve(id.fileName()));
        Files.createDirectories(directory);
        currentChannel = FileChannel.open(segment.path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
        segments.put(id, segment);
        current = segment;
        enforceLimits();
        return segment;
    }

    /**
     * Keeps appending to today's newest segment after a restart, so restarts do not leave many small files. A
     * segment whose last line was cut short is left alone and the next write starts a new one.
     */
    private void resumeLastSegment() throws IOException {
        var last = segments.lastEntry();
        if (last == null) return;
        Segment segment = last.getValue();
        if (!segment.id.day().equals(LocalDate.now(clock)) || segment.size >= segmentBytes
                || !endsWithNewline(segment)) {
            return;
        }
        currentChannel = FileChannel.open(segment.path, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        current = segment;
    }

    private static boolean endsWithNewline(Segment segment) throws IOException {
        if (segment.size == 0) return true;
        try (FileChannel channel = FileChannel.open(segment.path, StandardOpenOption.READ)) {
            ByteBuffer last = ByteBuffer.allocate(1);
            return channel.read(last, segment.size - 1) == 1 && last.get(0) == '\n';
        }
    }

    private void loadSegments() throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "traces-*.jsonl")) {
            for (Path file : files) {
                Matcher matcher = SEGMENT_NAME.matcher(file.getFileName().toString());
                if (!matcher.matches()) continue;
                try {
                    SegmentId id = new SegmentId(LocalDate.parse(matcher.group(1), DAY),
                            Integer.parseInt(matcher.group(2)));
                    Segment segment = new Segment(id, file);
                    segment.size = Files.size(file);
                    segment.loadedSize = segment.size;
                    segments.put(id, segment);
                } catch (RuntimeException ignored) {
                    // Not one of our segment names.
                }
            }
        }
    }

    /**
     * Also enforces the limits at most once per {@link #LIMITS_CHECK_MILLIS}, not only when a segment opens. The
     * check is queued on the writer, so callers never delete files themselves.
     */
    private void enforceLimitsPeriodically() {
        if (!persistent) return;
        long now = clock.millis();
        long last = limitsCheckedAtMillis.get();
        if (now - last < LIMITS_CHECK_MILLIS || !limitsCheckedAtMillis.compareAndSet(last, now)) return;
        try {
            writer.test(() -> {
                synchronized (fileLock) {
                    if (persistent) enforceLimits();
                }
            });
        } catch (RuntimeException ignored) {
            // The next interval tries again.
        }
    }

    /** Deletes whole segments that are past retention or beyond the storage budget, oldest first. */
    private void enforceLimits() {
        LocalDate oldestKept = LocalDate.now(clock).minusDays(retentionDays - 1L);
        // An open segment that has expired is closed, so it can be deleted like any other.
        if (current != null && current.id.day().isBefore(oldestKept)) closeCurrent();
        long total = 0;
        for (Segment segment : segments.values()) total += segment.size;
        List<Segment> expired = new ArrayList<>();
        for (Segment segment : segments.values()) {
            if (segment == current) break;
            if (segment.id.day().isBefore(oldestKept) || total > maxStorageBytes) {
                expired.add(segment);
                total -= segment.size;
            } else {
                break;
            }
        }
        if (expired.isEmpty()) return;
        for (Segment segment : expired) {
            try {
                delete(segment);
                segments.remove(segment.id);
            } catch (IOException failure) {
                // Kept in the segment list so the next check retries it and new segments never reuse its name.
                log.debug("Could not delete Meerkat trace segment {}", segment.path, failure);
            }
        }
        synchronized (stateLock) {
            for (Entry entry : index.values()) {
                Location location = entry.location;
                if (location == null || !location.segment().deleted) continue;
                entry.location = null;
                if (!entry.hot) removeLocked(entry);
            }
        }
    }

    private static void delete(Segment segment) throws IOException {
        Files.deleteIfExists(segment.path);
        segment.deleted = true;
    }

    private void closeCurrent() {
        current = null;
        if (currentChannel == null) return;
        try {
            currentChannel.close();
        } catch (IOException ignored) {
            // The file is complete; nothing else to release.
        }
        currentChannel = null;
    }

    private boolean shouldWrite(long writeGeneration) {
        synchronized (stateLock) {
            return persistent && writeGeneration == generation;
        }
    }

    private void disablePersistence(String operation, Exception failure) {
        boolean logWarning;
        synchronized (stateLock) {
            persistent = false;
            logWarning = !warningLogged;
            warningLogged = true;
        }
        if (logWarning) {
            log.warn("Could not {}; trace history is kept in memory only until the application restarts; path={}",
                    operation, directory, failure);
        }
    }

    // ---- index rebuild ----

    private void restoreIndex(List<Segment> newestFirst, long startGeneration) {
        try {
            for (Segment segment : newestFirst) {
                if (indexFull() || segment.deleted || Thread.currentThread().isInterrupted()) break;
                List<Restored> lines = readSegment(segment);
                // Older segments get lower sequences, and later lines of a segment higher ones, so records that
                // finished in the same millisecond keep their file order.
                long base = restoredSequence.addAndGet(-lines.size());
                synchronized (stateLock) {
                    if (generation != startGeneration) return;
                    // Deleted while being read: the deletion has already swept the index, so its lines must not
                    // be listed with a location that can no longer be read.
                    if (segment.deleted) continue;
                    for (int i = 0; i < lines.size(); i++) {
                        Restored line = lines.get(i);
                        Entry entry = new Entry(new Key(line.summary().finishedAt().toEpochMilli(), base + i),
                                line.summary(), null);
                        entry.location = line.location();
                        insertLocked(entry);
                    }
                }
            }
        } catch (Exception failure) {
            if (!Thread.currentThread().isInterrupted()) {
                log.warn("Could not rebuild the Meerkat trace index from {}", directory, failure);
            }
        } finally {
            restoring = false;
            restoreThread = null;
        }
    }

    private List<Restored> readSegment(Segment segment) throws IOException {
        List<Restored> entries = new ArrayList<>();
        int maxLineBytes = (int) segmentBytes;
        try (InputStream input = Files.newInputStream(segment.path)) {
            byte[] buffer = new byte[READ_BUFFER_BYTES];
            ByteArrayOutputStream line = new ByteArrayOutputStream(4096);
            long position = 0;
            long lineStart = 0;
            boolean oversized = false;
            long limit = segment.loadedSize;
            int read;
            while (position < limit
                    && (read = input.read(buffer, 0, (int) Math.min(buffer.length, limit - position))) >= 0) {
                int from = 0;
                for (int i = 0; i < read; i++) {
                    if (buffer[i] != '\n') continue;
                    if (!oversized) line.write(buffer, from, i - from);
                    long end = position + i;
                    if (!oversized) restoreLine(segment, line.toByteArray(), lineStart, entries);
                    line.reset();
                    oversized = false;
                    lineStart = end + 1;
                    from = i + 1;
                }
                if (!oversized) {
                    line.write(buffer, from, read - from);
                    if (line.size() > maxLineBytes) {
                        oversized = true;
                        line.reset();
                    }
                }
                position += read;
            }
            // A trailing line without a newline is a write cut short by a crash; it is ignored.
        }
        return entries;
    }

    private void restoreLine(Segment segment, byte[] bytes, long offset, List<Restored> entries) {
        if (bytes.length == 0) return;
        try {
            TraceRecord trace = codec.read(bytes);
            if (!isEntrySource(trace.sourceType()) || !isDiagnostic(trace) || trace.finishedAt() == null) return;
            entries.add(new Restored(TraceSummary.of(trace), new Location(segment, offset, bytes.length)));
        } catch (Exception malformed) {
            log.debug("Skipping malformed Meerkat trace record in {}", segment.path, malformed);
        }
    }

    // ---- search ----

    private TraceRecord decode(byte[] bytes) {
        try {
            return codec.read(bytes);
        } catch (Exception malformed) {
            return null;
        }
    }

    static String normalizeQuery(String text) {
        if (text == null) return "";
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        return normalized.length() > MAX_QUERY_CHARS ? normalized.substring(0, MAX_QUERY_CHARS) : normalized;
    }

    private static boolean matchesSummary(TraceSummary summary, String text) {
        return contains(summary.name(), text) || contains(summary.tag(), text)
                || contains(summary.traceId(), text) || contains(summary.errorType(), text)
                || contains(summary.errorMessage(), text) || contains(summary.threadName(), text);
    }

    private static boolean matchesNodes(List<TraceNode> nodes, String text) {
        for (TraceNode node : nodes) {
            if (contains(node.name(), text) || contains(node.tag(), text) || contains(node.kind(), text)
                    || contains(node.errorType(), text) || contains(node.threadName(), text)
                    || matchesNodes(node.children(), text)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String value, String text) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(text);
    }

    /**
     * Bytes to look for in the raw JSON line before decoding it, or {@code null} when the query contains
     * characters that JSON escapes or that do not lower-case as plain ASCII.
     */
    static byte[] asciiNeedle(String text) {
        if (text.isEmpty()) return null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c > 0x7e || c == '"' || c == '\\') return null;
        }
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    static boolean containsIgnoreAsciiCase(byte[] haystack, byte[] lowerNeedle) {
        int last = haystack.length - lowerNeedle.length;
        outer:
        for (int i = 0; i <= last; i++) {
            for (int j = 0; j < lowerNeedle.length; j++) {
                byte b = haystack[i + j];
                if (b >= 'A' && b <= 'Z') b = (byte) (b + 32);
                if (b != lowerNeedle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    /** Reuses one read channel per segment within a single search. */
    private static final class SegmentReader implements AutoCloseable {
        private final Map<Segment, FileChannel> channels = new HashMap<>();

        byte[] read(Location location) {
            if (location.segment().deleted) return null;
            try {
                FileChannel channel = channels.get(location.segment());
                if (channel == null) {
                    channel = FileChannel.open(location.segment().path, StandardOpenOption.READ);
                    channels.put(location.segment(), channel);
                }
                ByteBuffer buffer = ByteBuffer.allocate(location.length());
                long position = location.offset();
                while (buffer.hasRemaining()) {
                    int read = channel.read(buffer, position);
                    if (read <= 0) return null;
                    position += read;
                }
                return buffer.array();
            } catch (IOException failure) {
                return null;
            }
        }

        @Override
        public void close() {
            for (FileChannel channel : channels.values()) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // Read-only channel.
                }
            }
        }
    }

    // ---- types ----

    /** Sorts newest first; restored records use negative sequences so they never collide with new ones. */
    record Key(long finishedAtMillis, long sequence) implements Comparable<Key> {
        @Override
        public int compareTo(Key other) {
            int byTime = Long.compare(other.finishedAtMillis, finishedAtMillis);
            return byTime != 0 ? byTime : Long.compare(other.sequence, sequence);
        }

        String cursor() {
            return finishedAtMillis + "_" + sequence;
        }

        static Key parse(String cursor) {
            if (cursor == null || cursor.isBlank()) return null;
            int separator = cursor.indexOf('_');
            if (separator <= 0) throw new IllegalArgumentException("Invalid cursor");
            try {
                return new Key(Long.parseLong(cursor.substring(0, separator)),
                        Long.parseLong(cursor.substring(separator + 1)));
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("Invalid cursor", invalid);
            }
        }
    }

    private static final class Entry {
        final Key key;
        final TraceSummary summary;
        volatile TraceRecord full;
        volatile Location location;
        final long estimatedBytes;
        final long indexBytes;
        boolean hot;
        boolean pendingWrite;
        boolean removed;

        Entry(Key key, TraceSummary summary, TraceRecord full) {
            this.key = key;
            this.summary = summary;
            this.full = full;
            this.estimatedBytes = full == null ? 0 : full.estimatedBytes();
            this.indexBytes = summary.estimatedBytes();
        }
    }

    private record Location(Segment segment, long offset, int length) { }

    private record Restored(TraceSummary summary, Location location) { }

    private record SegmentId(LocalDate day, int sequence) implements Comparable<SegmentId> {
        @Override
        public int compareTo(SegmentId other) {
            int byDay = day.compareTo(other.day);
            return byDay != 0 ? byDay : Integer.compare(sequence, other.sequence);
        }

        String fileName() {
            return "traces-" + DAY.format(day) + "-" + sequence + ".jsonl";
        }
    }

    private static final class Segment {
        final SegmentId id;
        final Path path;
        long size;
        /** Bytes present when the store started; the index rebuild reads no further. */
        long loadedSize;
        volatile boolean deleted;

        Segment(SegmentId id, Path path) {
            this.id = id;
            this.path = path;
        }
    }

    /**
     * @param kind   {@code all}, {@code slow} (slow without error), {@code error} or {@code resource}
     * @param source entry type such as {@code HTTP}, or blank for all
     * @param text   case-insensitive text found in the entry, trace ID, error, or any span name or SQL
     * @param before cursor returned as {@link TracePage#nextCursor()}, or blank for the newest traces
     */
    public record TraceQuery(String kind, String source, String text, String before, int limit) {
        public TraceQuery {
            kind = kind == null || kind.isBlank() ? "all" : kind.toLowerCase(Locale.ROOT);
            if (!List.of("all", "slow", "error", "resource").contains(kind)) {
                throw new IllegalArgumentException("kind must be all, slow, error or resource");
            }
            source = source == null ? "" : source.trim();
        }

        boolean accepts(TraceSummary trace) {
            if (!source.isEmpty() && !source.equalsIgnoreCase(trace.sourceType())) return false;
            return switch (kind) {
                case "slow" -> trace.slow() && !trace.error();
                case "error" -> trace.error();
                case "resource" -> trace.highCpu() || trace.highAllocation();
                default -> true;
            };
        }
    }

    /**
     * @param nextCursor  pass as {@code before} to continue, or {@code null} when there is nothing older
     * @param scanLimited the text search stopped at its per-request disk budget; continuing searches older traces
     * @param total       traces matching the kind and source filters, or {@code -1} for text searches
     */
    public record TracePage(List<TraceSummary> items, String nextCursor, boolean scanLimited, int total) { }

    public record StorageStatus(boolean persistent, long storageBytes, long maxStorageBytes, int retentionDays,
                                int traceCount, boolean indexing, long throttledCount) { }
}
