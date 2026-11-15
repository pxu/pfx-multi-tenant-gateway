package com.example.gateway.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps audit I/O off the request path: request threads {@code offer()} into a bounded queue (O(1),
 * never blocks) and one background thread serialises each entry to a JSON line on the {@code AUDIT}
 * logger (stdout, picked up by the log shipper).
 *
 * <p>Explicit trade-off: if the writer falls far enough behind to fill the queue, new entries are
 * dropped and counted in {@code gateway_audit_dropped_total} (alert on it) rather than slowing every
 * request or growing memory without bound. In production this would publish to Kafka.
 */
public class AsyncAuditSink implements AuditSink, AutoCloseable {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");
    private static final Logger log = LoggerFactory.getLogger(AsyncAuditSink.class);

    private final BlockingQueue<Map<String, Object>> queue;
    private final ObjectMapper json;
    private final Consumer<String> writer;
    private final Counter dropped;
    private final Thread thread;
    private volatile boolean running = true;

    public AsyncAuditSink(ObjectMapper json, int capacity, MeterRegistry meters) {
        this(json, capacity, meters, AUDIT::info);
    }

    AsyncAuditSink(ObjectMapper json, int capacity, MeterRegistry meters, Consumer<String> writer) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.json = json;
        this.writer = writer;
        this.dropped = Counter.builder("gateway.audit.dropped").description("audit entries dropped: queue full").register(meters);
        Gauge.builder("gateway.audit.queue.size", queue, BlockingQueue::size).register(meters);
        this.thread = Thread.ofPlatform().daemon().name("audit-writer").start(this::run);
    }

    @Override
    public void submit(Map<String, Object> entry) {
        if (!queue.offer(entry)) {
            dropped.increment();
        }
    }

    private void run() {
        while (running || !queue.isEmpty()) {
            try {
                Map<String, Object> entry = queue.poll(100, TimeUnit.MILLISECONDS);
                if (entry != null) {
                    writer.accept(json.writeValueAsString(entry));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("failed to write audit entry", e); // never let the writer thread die
            }
        }
    }

    /** Flushes queued entries on shutdown (bounded wait). */
    @Override
    public void close() throws InterruptedException {
        running = false;
        thread.join(5_000);
    }
}
