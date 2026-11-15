package com.example.upstream;

import com.example.upstream.UpstreamApplication.RandomSource;
import com.example.upstream.UpstreamApplication.Sleeper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints. The gateway appends the client's path to the upstream URL, so every path that is not
 * one of the special ones is echoed.
 *
 * <p>Unlike a Go handler, a servlet cannot cheaply tell that the caller went away, so a delay always
 * runs to the end (at most {@code max-delay-ms}) even when the gateway has cancelled the request (a
 * losing hedge, a timeout). The gateway's behaviour is the same either way.
 */
@RestController
public class UpstreamController {

    private final UpstreamProperties properties;
    private final RandomSource random;
    private final Sleeper sleeper;

    public UpstreamController(UpstreamProperties properties, RandomSource random, Sleeper sleeper) {
        this.properties = properties;
        this.random = random;
        this.sleeper = sleeper;
    }

    /** What the echo returns; {@code headers} has lower-case names, each with all its values. */
    public record EchoResponse(
            String version, String method, String path, String query, long delayMs,
            Map<String, List<String>> headers) {}

    @GetMapping(value = "/healthz", produces = MediaType.TEXT_PLAIN_VALUE)
    public String healthz() {
        return "ok\n";
    }

    @RequestMapping("/delay/{ms}")
    public ResponseEntity<?> delay(@PathVariable String ms, HttpServletRequest request) throws InterruptedException {
        long millis = parse(ms);
        if (millis < 0) {
            return badRequest("delay must be a number of milliseconds");
        }
        return waitThenEcho(millis, request);
    }

    @RequestMapping("/random-delay")
    public ResponseEntity<?> randomDelay(
            @RequestParam(required = false) String min, @RequestParam(required = false) String max,
            HttpServletRequest request) throws InterruptedException {
        long lo = parse(min);
        long hi = parse(max);
        if (lo < 0 || hi < lo || hi - lo >= Integer.MAX_VALUE) {
            return badRequest("need 0 <= min <= max (milliseconds)");
        }
        return waitThenEcho(lo + random.nextInt((int) (hi - lo + 1)), request);
    }

    @RequestMapping("/status/{code}")
    public ResponseEntity<String> status(@PathVariable String code) {
        long status = parse(code);
        if (status < 200 || status > 599) {
            return badRequest("status must be 200-599");
        }
        return ResponseEntity.status((int) status).contentType(MediaType.TEXT_PLAIN)
                .body("status " + status + " from " + properties.version() + "\n");
    }

    @RequestMapping("/**")
    public ResponseEntity<?> echo(HttpServletRequest request) {
        if (properties.plainOk()) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body("ok\n");
        }
        return echo(request, 0);
    }

    private ResponseEntity<?> waitThenEcho(long millis, HttpServletRequest request) throws InterruptedException {
        long capped = Math.min(millis, properties.maxDelayMs());
        sleeper.sleep(capped);
        return echo(request, capped);
    }

    private ResponseEntity<EchoResponse> echo(HttpServletRequest request, long delayMs) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.computeIfAbsent(name.toLowerCase(), k -> new ArrayList<>())
                    .addAll(Collections.list(request.getHeaders(name)));
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(new EchoResponse(
                properties.version(), request.getMethod(), request.getRequestURI(), request.getQueryString(),
                delayMs, headers));
    }

    private static ResponseEntity<String> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.TEXT_PLAIN).body(message + "\n");
    }

    /** A non-negative number, or -1 when missing or not a number. */
    private static long parse(String value) {
        try {
            return value == null ? -1 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
