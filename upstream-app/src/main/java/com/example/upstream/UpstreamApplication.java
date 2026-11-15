package com.example.upstream;

import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * A small upstream for demonstrating and load testing the gateway against something real: it echoes
 * requests, waits as long as it is told, answers any status code, and can fail on purpose.
 *
 * <pre>
 *   GET /delay/{ms}                       wait ms milliseconds (at most max-delay-ms), then echo
 *   GET /random-delay?min=100&amp;max=5000    wait a uniform random time in [min, max] ms, then echo
 *   GET /status/{code}                    answer with that status code
 *   GET /healthz                          200 "ok" (never fails, never waits)
 *   anything else                         echo: method, path, query and headers as JSON ("ok" in plain-ok mode)
 * </pre>
 */
@SpringBootApplication
@EnableConfigurationProperties(UpstreamProperties.class)
public class UpstreamApplication {

    private static final Logger log = LoggerFactory.getLogger(UpstreamApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(UpstreamApplication.class, args);
    }

    /** Uniform random int in [0, n); a bean so tests can make it deterministic. */
    @Bean
    RandomSource randomSource() {
        return n -> ThreadLocalRandom.current().nextInt(n);
    }

    @Bean
    Sleeper sleeper() {
        return Sleeper.THREAD_SLEEP;
    }

    @Bean
    FilterRegistrationBean<VersionAndFailureFilter> versionAndFailureFilter(
            UpstreamProperties properties, RandomSource random, Sleeper sleeper) {
        log.info("upstream '{}' (fail {}%, max delay {} ms, plain-ok {}, every request delayed {}-{} ms)",
                properties.version(), properties.failPct(), properties.maxDelayMs(), properties.plainOk(),
                properties.delayMinMs(), properties.delayMaxMs());
        var registration = new FilterRegistrationBean<>(
                new VersionAndFailureFilter(properties, random, sleeper));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /** Source of randomness: a uniform int in [0, n). */
    @FunctionalInterface
    public interface RandomSource {
        int nextInt(int n);
    }

    /** Waits; a seam so tests do not have to sleep. */
    @FunctionalInterface
    public interface Sleeper {
        Sleeper THREAD_SLEEP = Thread::sleep;

        void sleep(long millis) throws InterruptedException;
    }
}
