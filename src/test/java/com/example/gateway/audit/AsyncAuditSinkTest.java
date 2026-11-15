package com.example.gateway.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class AsyncAuditSinkTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void testSubmit_WritesEachEntryAsOneJsonLine() throws Exception {
        // Arrange
        List<String> lines = new CopyOnWriteArrayList<>();
        try (AsyncAuditSink sink = new AsyncAuditSink(new ObjectMapper(), 10, meters, lines::add)) {

            // Act
            sink.submit(Map.of("requestId", "r1"));
            sink.submit(Map.of("requestId", "r2"));

            // Assert
            await().atMost(Duration.ofSeconds(2)).until(() -> lines.size() == 2);
            assertThat(lines).containsExactly("{\"requestId\":\"r1\"}", "{\"requestId\":\"r2\"}");
        }
    }

    @Test
    void testSubmit_WriterStuckAndQueueFull_DropsAndCountsWithoutBlocking() throws Exception {
        // Arrange: a writer that hangs on the first entry, and room for 2 more in the queue
        CountDownLatch release = new CountDownLatch(1);
        try (AsyncAuditSink sink = new AsyncAuditSink(new ObjectMapper(), 2, meters, line -> block(release))) {
            sink.submit(Map.of("n", 0));
            await().atMost(Duration.ofSeconds(2)).until(() -> meters.get("gateway.audit.queue.size").gauge().value() == 0);

            // Act: 2 fit in the queue, 3 do not
            long start = System.nanoTime();
            for (int i = 1; i <= 5; i++) {
                sink.submit(Map.of("n", i));
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            // Assert
            assertThat(meters.get("gateway.audit.dropped").counter().count()).isEqualTo(3);
            assertThat(elapsedMs).isLessThan(100); // the request thread never waited for the writer
            release.countDown();
        }
    }

    private static void block(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
