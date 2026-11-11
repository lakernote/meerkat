package io.github.lakernote.meerkat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Public configuration for the Meerkat Spring Boot Starter.
 */
@ConfigurationProperties("meerkat")
public class MeerkatProperties {
    private boolean enabled = true;
    private final Trace trace = new Trace();
    private final Web web = new Web();
    private final Metrics metrics = new Metrics();
    private final Storage storage = new Storage();
    private final Capture capture = new Capture();
    private final Tools tools = new Tools();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Trace getTrace() {
        return trace;
    }

    public Web getWeb() {
        return web;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public Storage getStorage() {
        return storage;
    }

    public Capture getCapture() {
        return capture;
    }

    public Tools getTools() {
        return tools;
    }

    /**
     * The on-demand toolbox page: temporary log levels, CPU flame graphs, heap histograms and dumps, configuration
     * sources and class locations. Nothing runs until an operator starts it from the dashboard. Changing log levels,
     * downloading heap dumps and reading configuration values need {@code ObservabilityAccessPolicy#canOperate()}.
     */
    public static class Tools {
        private boolean enabled = true;
        /** Longest time a temporary log level stays before it is reverted; at most one hour. */
        private Duration logLevelMaxDuration = Duration.ofHours(1);
        /** Shortest time between two heap histograms, each of which runs a full garbage collection. */
        private Duration heapHistogramInterval = Duration.ofSeconds(10);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getLogLevelMaxDuration() { return logLevelMaxDuration; }
        public void setLogLevelMaxDuration(Duration logLevelMaxDuration) { this.logLevelMaxDuration = logLevelMaxDuration; }
        public Duration getHeapHistogramInterval() { return heapHistogramInterval; }
        public void setHeapHistogramInterval(Duration heapHistogramInterval) { this.heapHistogramInterval = heapHistogramInterval; }
    }

    /**
     * On-demand traffic capture for troubleshooting. While a capture rule started from the dashboard matches a
     * request, the request and response, SQL with parameters and result rows, Redis commands with results and
     * outbound HTTP calls of that request are recorded in full, without redaction, and kept in memory only.
     * There is no on/off switch: starting a capture needs {@code ObservabilityAccessPolicy#canCapture()}, which is
     * denied unless a custom policy grants it.
     */
    public static class Capture {
        /** Bytes kept per request or response body; the rest of the body is passed through but not recorded. */
        private DataSize maxBodySize = DataSize.ofKilobytes(64);
        /** Result rows kept per SQL query; later rows are counted but not recorded. */
        private int maxResultRows = 20;
        /** Characters kept per captured value such as SQL parameters or a Redis result. */
        private int maxValueChars = 16_384;
        /** Characters kept across all captured values of one request. */
        private int maxTraceChars = 1_000_000;
        /** Captured requests kept in memory; the oldest are dropped first. */
        private int maxRecords = 100;
        /** Estimated heap for all kept captured requests; the oldest are dropped first, the newest is always kept. */
        private DataSize maxStoredSize = DataSize.ofMegabytes(32);
        /** How long captured requests are kept in memory. */
        private Duration retention = Duration.ofHours(2);
        /** Longest time a capture rule may stay active before it stops by itself. */
        private Duration maxDuration = Duration.ofHours(1);

        public DataSize getMaxBodySize() { return maxBodySize; }
        public void setMaxBodySize(DataSize maxBodySize) { this.maxBodySize = maxBodySize; }
        public int getMaxResultRows() { return maxResultRows; }
        public void setMaxResultRows(int maxResultRows) { this.maxResultRows = maxResultRows; }
        public int getMaxValueChars() { return maxValueChars; }
        public void setMaxValueChars(int maxValueChars) { this.maxValueChars = maxValueChars; }
        public int getMaxTraceChars() { return maxTraceChars; }
        public void setMaxTraceChars(int maxTraceChars) { this.maxTraceChars = maxTraceChars; }
        public int getMaxRecords() { return maxRecords; }
        public void setMaxRecords(int maxRecords) { this.maxRecords = maxRecords; }
        public DataSize getMaxStoredSize() { return maxStoredSize; }
        public void setMaxStoredSize(DataSize maxStoredSize) { this.maxStoredSize = maxStoredSize; }
        public Duration getRetention() { return retention; }
        public void setRetention(Duration retention) { this.retention = retention; }
        public Duration getMaxDuration() { return maxDuration; }
        public void setMaxDuration(Duration maxDuration) { this.maxDuration = maxDuration; }
    }

    public static class Storage {
        /**
         * Directory for the trace archive and metric history. Defaults to
         * {@code ./data/meerkat/<spring.application.name>}. Each application instance needs its own
         * directory; an instance that finds the directory in use keeps its history in memory only.
         */
        private String directory;

        public String getDirectory() { return directory; }
        public void setDirectory(String directory) { this.directory = directory; }
    }

    /**
     * Runtime metrics: per-operation call statistics (HTTP endpoints, SQL, remote calls, Redis commands, jobs,
     * Kafka), which require {@code meerkat.trace.enabled}, and JVM and pool resource metrics read from
     * Micrometer. History is kept in daily files under the storage directory.
     */
    public static class Metrics {
        private boolean enabled = true;
        /**
         * Maximum number of distinct operations kept across all categories; later ones are merged per category.
         * Operations without a call for an hour free their slot.
         */
        private int maxOperations = 500;
        /** History retention in whole days, between 1 and 365 days. A bare number is read as days. */
        @DurationUnit(ChronoUnit.DAYS)
        private Duration retention = Duration.ofDays(30);
        /** Disk budget for the metric files; the oldest days are deleted beyond it. */
        private DataSize maxStorageSize = DataSize.ofMegabytes(64);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxOperations() { return maxOperations; }
        public void setMaxOperations(int maxOperations) { this.maxOperations = maxOperations; }
        public Duration getRetention() { return retention; }
        public void setRetention(Duration retention) { this.retention = retention; }
        public DataSize getMaxStorageSize() { return maxStorageSize; }
        public void setMaxStorageSize(DataSize maxStorageSize) { this.maxStorageSize = maxStorageSize; }
    }

    public static class Web {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

    }

    public static class Trace {
        private boolean enabled = true;
        private String headerName = "X-Trace-Id";
        // Thresholds of zero or below disable the corresponding trigger. A bare number is read as milliseconds.
        private Duration jobSlowThreshold = Duration.ofSeconds(1);
        private Duration httpSlowThreshold = Duration.ofMillis(500);
        private Duration kafkaConsumerSlowThreshold = Duration.ofSeconds(1);
        private Duration cpuThreshold = Duration.ofMillis(500);
        private DataSize allocatedMemoryThreshold = DataSize.ofMegabytes(50);
        /** How long stored traces are kept, in whole days between 1 and 365. A bare number is read as days. */
        @DurationUnit(ChronoUnit.DAYS)
        private Duration retention = Duration.ofDays(7);
        /** Disk budget for the trace files; the oldest files are deleted beyond it. */
        private DataSize maxStorageSize = DataSize.ofMegabytes(128);
        /**
         * Heap budget for the newest stored traces kept in memory with their full call tree; older ones are read
         * from disk. Without usable storage, traces beyond it are dropped.
         */
        private DataSize maxMemorySize = DataSize.ofMegabytes(32);
        /**
         * Traces stored per minute for each entry and outcome (error type, slow or resource); later ones that minute
         * are only counted, so one failing endpoint cannot push out every other trace. Zero or below keeps all.
         */
        private int maxPerOperationPerMinute = 60;
        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getHeaderName() {
            return headerName;
        }

        public void setHeaderName(String headerName) {
            this.headerName = headerName == null || headerName.isBlank() ? "X-Trace-Id" : headerName;
        }

        public Duration getJobSlowThreshold() {
            return jobSlowThreshold;
        }

        public void setJobSlowThreshold(Duration jobSlowThreshold) {
            this.jobSlowThreshold = jobSlowThreshold == null ? Duration.ZERO : jobSlowThreshold;
        }

        public Duration getHttpSlowThreshold() {
            return httpSlowThreshold;
        }

        public void setHttpSlowThreshold(Duration httpSlowThreshold) {
            this.httpSlowThreshold = httpSlowThreshold == null ? Duration.ZERO : httpSlowThreshold;
        }

        public Duration getKafkaConsumerSlowThreshold() {
            return kafkaConsumerSlowThreshold;
        }

        public void setKafkaConsumerSlowThreshold(Duration kafkaConsumerSlowThreshold) {
            this.kafkaConsumerSlowThreshold = kafkaConsumerSlowThreshold == null ? Duration.ZERO : kafkaConsumerSlowThreshold;
        }

        public Duration getCpuThreshold() {
            return cpuThreshold;
        }

        public void setCpuThreshold(Duration cpuThreshold) {
            this.cpuThreshold = cpuThreshold == null ? Duration.ZERO : cpuThreshold;
        }

        public DataSize getAllocatedMemoryThreshold() {
            return allocatedMemoryThreshold;
        }

        public void setAllocatedMemoryThreshold(DataSize allocatedMemoryThreshold) {
            this.allocatedMemoryThreshold = allocatedMemoryThreshold == null ? DataSize.ofBytes(0)
                    : allocatedMemoryThreshold;
        }

        public Duration getRetention() {
            return retention;
        }

        public void setRetention(Duration retention) {
            this.retention = retention;
        }

        public DataSize getMaxStorageSize() {
            return maxStorageSize;
        }

        public void setMaxStorageSize(DataSize maxStorageSize) {
            this.maxStorageSize = maxStorageSize;
        }

        public DataSize getMaxMemorySize() {
            return maxMemorySize;
        }

        public void setMaxMemorySize(DataSize maxMemorySize) {
            this.maxMemorySize = maxMemorySize;
        }

        public int getMaxPerOperationPerMinute() {
            return maxPerOperationPerMinute;
        }

        public void setMaxPerOperationPerMinute(int maxPerOperationPerMinute) {
            this.maxPerOperationPerMinute = maxPerOperationPerMinute;
        }
    }
}
