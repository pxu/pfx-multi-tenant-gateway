package com.example.gateway.policy;

import com.example.gateway.audit.AuditRecord;
import com.example.gateway.error.ErrorCode;
import com.example.gateway.error.GatewayException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rate limiting by network origin, <b>before authentication</b> (after {@code AuditFilter}, before
 * {@code AuthFilter}). It is the one dimension that also covers requests nobody has authenticated yet:
 * a flood of junk tokens is stopped before each one costs a signature check, and repeated
 * {@code /admin/login} guesses from one address are throttled.
 *
 * <p>Deliberately generous (a whole office can sit behind one address); the identity dimensions in
 * {@link PolicyEngine} (per user, per tenant) do the fine-grained limiting. Uses the same
 * {@link RateLimiter} as they do, with {@code ip:<address>} keys. The address comes from the audit
 * record, resolved by {@code ClientIpResolver} (trusted proxies only).
 */
public class IpRateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final int perMinute;
    private final int burst;

    /** @param perMinute 0 disables the IP dimension */
    public IpRateLimitFilter(RateLimiter limiter, int perMinute, int burst) {
        this.limiter = limiter;
        this.perMinute = perMinute;
        this.burst = Math.max(1, burst);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (perMinute > 0) {
            String ip = AuditRecord.of(request).clientIp();
            RateLimiter.Result result = limiter.tryAcquire("ip:" + ip, perMinute, burst);
            if (!result.allowed()) {
                throw new GatewayException(ErrorCode.IP_RATE_LIMITED,
                        "more than " + perMinute + " requests per minute from this address",
                        Map.of("Retry-After", String.valueOf(result.retryAfterSeconds())));
            }
        }
        chain.doFilter(request, response);
    }
}
