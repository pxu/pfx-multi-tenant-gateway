package com.example.gateway.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.gateway.error.ErrorCode;
import com.example.gateway.error.GatewayException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Two ConfigStore instances (= two gateway instances) on one real Redis, using database 1 to stay isolated. */
class ConfigStoreIT {

    /** One in-memory H2 database per test class instance, with the real schema; shared by two "instances". */
    private final DriverManagerDataSource db = new DriverManagerDataSource(
            "jdbc:h2:mem:configstore-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    private final JdbcClient jdbc = JdbcClient.create(db);
    private final ConfigRepository repository = new JdbcConfigRepository(jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(db)), new ObjectMapper());
    /** A clock the test moves by hand (tenant cache TTL). */
    private final MutableClock clock = new MutableClock();
    private final ConfigStore instanceA = new ConfigStore(repository, Duration.ofSeconds(5), Duration.ofSeconds(5), clock);
    private final ConfigStore instanceB = new ConfigStore(repository, Duration.ofSeconds(5), Duration.ofSeconds(5), clock);

    static final class MutableClock extends Clock {
        private final AtomicLong millis = new AtomicLong(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli());

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        void advance(Duration d) {
            millis.addAndGet(d.toMillis());
        }
    }

    private static Tenant tenant(String id) {
        return new Tenant(id, id, false, 100, 0, List.of("/"), Set.of());
    }

    private static Route route(String id, String prefix) {
        return new Route(id, prefix, List.of(new Route.Upstream("http://upstream", 1)), null);
    }

    @BeforeEach
    void createSchema() {
        new ResourceDatabasePopulator(new ClassPathResource("schema-h2.sql")).execute(db);
    }

    /** e.g. the database was restored from an empty backup: every table, the version too. */
    private void loseAllData() {
        for (String table : List.of("role_assignments", "tenants", "routes", "roles", "config_version")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
    }

    @Test
    void testRouteWrite_OtherInstanceSeesItAfterItsNextRefresh() {
        // Arrange
        instanceB.refresh();

        // Act
        instanceA.createRoute(route("orders", "/orders"));

        // Assert: immediate on the writer, on the other instance after its (periodic) refresh
        assertThat(instanceA.match("/orders/1")).isPresent();
        assertThat(instanceB.match("/orders/1")).isEmpty();
        instanceB.refresh();
        assertThat(instanceB.match("/orders/1")).hasValueSatisfying(e -> assertThat(e.route().id()).isEqualTo("orders"));
    }

    @Test
    void testTenant_1000ConcurrentRequestsOnAColdCache_OneDatabaseRead() throws Exception {
        // Arrange: the tenant exists in the database; instance B has never been asked for it
        instanceA.createTenant(tenant("acme"));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<Tenant>>> results = new ArrayList<>();

        // Act: 1,000 requests for acme at the same instant
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 1000; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return instanceB.tenant("acme");
                }));
            }
            start.countDown();
            for (Future<Optional<Tenant>> r : results) {
                assertThat(r.get(10, TimeUnit.SECONDS)).hasValueSatisfying(t -> assertThat(t.id()).isEqualTo("acme"));
            }
        }

        // Assert: single flight, then the cache: one database read for all of them
        assertThat(instanceB.tenantLoads()).isEqualTo(1);
    }

    @Test
    void testTenantWrite_WriterAtOnce_OtherInstanceWithinTtl() {
        // Arrange: both instances have acme cached
        instanceA.createTenant(tenant("acme"));
        assertThat(instanceB.tenant("acme")).hasValueSatisfying(t -> assertThat(t.disabled()).isFalse());

        // Act: disable it through instance A
        instanceA.updateTenant(new Tenant("acme", "acme", true, 100, 0, List.of("/"), Set.of()));

        // Assert: A drops its copy at once; B serves its cached copy until the TTL runs out
        assertThat(instanceA.tenant("acme")).hasValueSatisfying(t -> assertThat(t.disabled()).isTrue());
        assertThat(instanceB.tenant("acme")).hasValueSatisfying(t -> assertThat(t.disabled()).isFalse());
        clock.advance(Duration.ofSeconds(5));
        assertThat(instanceB.tenant("acme")).hasValueSatisfying(t -> assertThat(t.disabled()).isTrue());
    }

    @Test
    void testTenant_UnknownIsCachedToo_CreateAndDeleteApplyAtOnce() {
        assertThat(instanceA.tenant("ghost")).isEmpty();
        assertThat(instanceA.tenant("ghost")).isEmpty();
        assertThat(instanceA.tenantLoads()).isEqualTo(1); // "not found" is cached as well

        instanceA.createTenant(tenant("ghost")); // ...and dropped when the tenant is created
        assertThat(instanceA.tenant("ghost")).isPresent();
        instanceA.deleteTenant("ghost");
        assertThat(instanceA.tenant("ghost")).isEmpty();
    }

    @Test
    void testRbacData_PropagatesLikeConfig_AndTenantDeleteDropsItsAssignments() {
        // Arrange
        instanceA.createTenant(tenant("acme"));
        instanceA.createTenant(tenant("acme-2")); // shares the prefix "acme", must keep its assignment

        // Act
        instanceA.putRole("orders-auditor", List.of("orders:read"));
        instanceA.putAssignment("acme", "rita", List.of("orders-auditor"));
        instanceA.putAssignment("acme-2", "rita", List.of("orders-auditor"));
        instanceB.refresh();

        // Assert: the other instance decides from the same data, from memory
        assertThat(instanceB.customRoles()).containsEntry("orders-auditor", List.of("orders:read"));
        assertThat(instanceB.rolesOf("acme", "rita")).containsExactly("orders-auditor");
        assertThat(instanceA.listAssignments("acme")).containsOnlyKeys("rita");

        // Act: deleting the tenant also deletes its users' assignments, and only its own
        instanceA.deleteTenant("acme");
        instanceB.refresh();

        // Assert
        assertThat(instanceB.rolesOf("acme", "rita")).isEmpty();
        assertThat(instanceB.rolesOf("acme-2", "rita")).containsExactly("orders-auditor");
    }

    @Test
    void testRefresh_VersionUnchanged_SnapshotNotReloaded() {
        instanceA.createRoute(route("orders", "/orders"));
        // Someone edits the table without bumping the version: not picked up (one cheap read per poll).
        jdbc.sql("DELETE FROM routes WHERE id = 'orders'").update();

        instanceA.refresh();

        assertThat(instanceA.match("/orders")).isPresent();
    }

    @Test
    void testRefresh_DatabaseLostAllData_KeepsLastSnapshot() {
        instanceA.createTenant(tenant("acme"));
        instanceA.createRoute(route("orders", "/orders"));

        assertThat(instanceA.tenant("acme")).isPresent(); // cached

        loseAllData();
        instanceA.refresh();
        clock.advance(Duration.ofSeconds(10)); // the cached tenant has expired: it must reload

        // Still serving: "not found" after a data loss is not the truth; keep the last known values.
        assertThat(instanceA.tenant("acme")).isPresent();
        assertThat(instanceA.match("/orders")).isPresent();
    }

    @Test
    void testWriteAfterDataLoss_IsPickedUpEvenThoughCountersRestarted() {
        // A plain counter restarts at 1 after a wipe and can collide with the snapshot's version,
        // making the writer (or another instance) skip the reload. Generation tokens never collide.
        instanceA.createTenant(tenant("acme"));
        instanceB.refresh();
        loseAllData();

        instanceA.createTenant(tenant("beta"));
        instanceB.refresh();

        assertThat(instanceA.tenant("beta")).isPresent();
        assertThat(instanceB.tenant("beta")).isPresent();
    }

    @Test
    void testWrites_PutReplaces_PrefixUniquenessHoldsOnUpdate() {
        // Arrange
        instanceA.createTenant(tenant("acme"));
        instanceA.createRoute(route("orders", "/orders"));
        instanceA.createRoute(route("billing", "/billing"));

        // Act: create-or-replace without vendor upsert syntax
        instanceA.putRole("auditor", List.of("orders:read"));
        instanceA.putRole("auditor", List.of("orders:read", "billing:read"));
        instanceA.putAssignment("acme", "rita", List.of("auditor"));
        instanceA.putAssignment("acme", "rita", List.of("auditor", "viewer"));

        // Assert
        assertThat(instanceA.customRoles()).containsEntry("auditor", List.of("orders:read", "billing:read"));
        assertThat(instanceA.rolesOf("acme", "rita")).containsExactly("auditor", "viewer");
        assertThatThrownBy(() -> instanceA.updateRoute(route("billing", "/orders"))) // the database's unique constraint
                .satisfies(e -> code(e, ErrorCode.CONFLICT));
        assertThat(instanceA.findRoute("billing")).hasValueSatisfying(r -> assertThat(r.pathPrefix()).isEqualTo("/billing"));
    }

    @Test
    void testAdminWrites_ConflictsAndMissing_Rejected() {
        instanceA.createTenant(tenant("acme"));
        instanceA.createRoute(route("orders", "/orders"));

        assertThatThrownBy(() -> instanceA.createTenant(tenant("acme"))).satisfies(e -> code(e, ErrorCode.CONFLICT));
        assertThatThrownBy(() -> instanceA.createRoute(route("other", "/orders"))).satisfies(e -> code(e, ErrorCode.CONFLICT));
        assertThatThrownBy(() -> instanceA.updateTenant(tenant("nobody"))).satisfies(e -> code(e, ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> instanceA.deleteRoute("nothing")).satisfies(e -> code(e, ErrorCode.NOT_FOUND));
    }

    @Test
    void testDelete_RemovedFromSnapshot() {
        instanceA.createRoute(route("orders", "/orders"));

        instanceA.deleteRoute("orders");

        assertThat(instanceA.match("/orders")).isEmpty();
        assertThat(instanceA.listRoutes()).isEmpty();
    }
    @Test
    void testRevokeToken_WriterAtOnce_OtherInstanceWithinTtl_SnapshotUntouched() {
        // Arrange: both instances have checked the token (cached as "not revoked")
        assertThat(instanceA.isRevoked("t-1")).isFalse();
        assertThat(instanceB.isRevoked("t-1")).isFalse();
        String version = repository.version();

        // Act
        instanceA.revokeToken("t-1", clock.instant().plus(Duration.ofHours(1)));

        // Assert: A at once, B after its TTL; revocations are not configuration, so no version change
        assertThat(instanceA.isRevoked("t-1")).isTrue();
        assertThat(instanceB.isRevoked("t-1")).isFalse();
        clock.advance(Duration.ofSeconds(5));
        assertThat(instanceB.isRevoked("t-1")).isTrue();
        assertThat(instanceA.isRevoked("t-2")).isFalse();
        assertThat(repository.version()).isEqualTo(version);
    }

    @Test
    void testRevokeToken_TwiceIsIdempotent_ExpiredRevocationsArePurged() {
        // Arrange: one revocation that lapses in a minute
        instanceA.revokeToken("old", clock.instant().plus(Duration.ofMinutes(1)));
        instanceA.revokeToken("old", clock.instant().plus(Duration.ofMinutes(1)));

        // Act: two minutes later another token is revoked
        clock.advance(Duration.ofMinutes(2));
        instanceA.revokeToken("new", clock.instant().plus(Duration.ofHours(1)));

        // Assert: the lapsed row is gone (its token has expired anyway), the new one is there
        assertThat(jdbc.sql("SELECT jti FROM revoked_tokens").query(String.class).list()).containsExactly("new");
    }

    private static void code(Throwable e, ErrorCode expected) {
        assertThat(((GatewayException) e).code()).isEqualTo(expected);
    }
}
