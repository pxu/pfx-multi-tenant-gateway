package com.example.gateway.store;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A route-table entry: requests whose path starts with {@code pathPrefix} are proxied to one of the
 * weighted {@code upstreams} ({@code upstream url + path}). Also the JSON body of the admin API.
 *
 * @param upstreams one or more destinations; weights are relative (90/10 = a 10% canary)
 * @param service   the service behind this route (default: the route id). A token must have it in
 *                  its scopes, and the role needs {@code <service>:read|write|delete} by HTTP method.
 * @param sticky    each tenant always goes to the same upstream (a sticky canary: whole tenants are
 *                  on the canary, chosen by weight); default false, a weighted pick per request
 * @param hedgeAfterMs  if a GET/HEAD/OPTIONS without a body has no response after this many
 *                  milliseconds, send a second copy (preferably to another upstream) and take whichever
 *                  answers first; 0 (default) = off. Hedges share the retry budget.
 * @param requestsPerMinute limit for the route as a whole, all tenants together (the service owner's
 *                  protection for the upstream); 0 (default) = none
 * @param burst     requests the route takes at once (≤ requestsPerMinute); 0 = one second's worth
 */
public record Route(
        @Pattern(regexp = Route.ID) String id,
        @NotBlank @Size(max = 512) @Pattern(regexp = "^/.*") String pathPrefix,
        @NotEmpty List<@Valid Upstream> upstreams,
        @Pattern(regexp = Route.ID) String service,
        boolean sticky,
        @PositiveOrZero @Max(60_000) int hedgeAfterMs,
        @PositiveOrZero int requestsPerMinute,
        @PositiveOrZero int burst) {

    public static final String ID = "^[a-z0-9][a-z0-9-]{0,62}$";

    public record Upstream(
            @NotBlank @Pattern(regexp = "^https?://[^/?#@]+(/[^?#]*)?$", message = "must be http(s)://host[:port][/path]") String url,
            @Min(1) @Max(1000) int weight) {}

    public Route {
        upstreams = upstreams == null ? List.of() : List.copyOf(upstreams);
    }

    /** Not sticky, no hedging, no route limit. */
    public Route(String id, String pathPrefix, List<Upstream> upstreams, String service) {
        this(id, pathPrefix, upstreams, service, false, 0, 0, 0);
    }

    /** The bucket capacity of the route limit: {@code burst}, or one second's worth (at least 1). */
    public int effectiveBurst() {
        return burst > 0 ? burst : Math.max(1, Math.ceilDiv(requestsPerMinute, 60));
    }

    /** Defaults to the route id. */
    @Override
    public String service() {
        return service != null ? service : id;
    }

    public Route withId(String newId) {
        return new Route(newId, pathPrefix, upstreams, service, sticky, hedgeAfterMs, requestsPerMinute, burst);
    }
}
