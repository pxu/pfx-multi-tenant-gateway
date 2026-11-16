package com.example.gateway.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.gateway.error.ErrorCode;
import com.example.gateway.error.GatewayException;
import java.time.Clock;
import com.example.gateway.resilience.CircuitBreaker;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class ConfigStoreTest {

    @Test
    void testTenant_DatabaseDownAndNeverLoaded_Rejected503NotInternalError() {
        // Arrange: the database is unreachable and this tenant was never cached
        ConfigRepository repository = mock(ConfigRepository.class);
        when(repository.tenant("acme")).thenThrow(new DataAccessResourceFailureException("connection refused"));
        ConfigStore store = new ConfigStore(repository, Duration.ofSeconds(5), Duration.ofSeconds(5), Clock.systemUTC());

        // Act + Assert
        assertThatThrownBy(() -> store.tenant("acme"))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.CONFIG_UNAVAILABLE);
                    assertThat(e.headers()).containsEntry("Retry-After", "1");
                });
    }

    @Test
    void testIsRevoked_DatabaseDownAndNeverChecked_FailsOpenAndCounts() {
        // Arrange
        ConfigRepository repository = mock(ConfigRepository.class);
        when(repository.isRevoked("t-1")).thenThrow(new DataAccessResourceFailureException("connection refused"));
        ConfigStore store = new ConfigStore(repository, Duration.ofSeconds(5), Duration.ofSeconds(5), Clock.systemUTC());

        // Act
        boolean revoked = store.isRevoked("t-1");

        // Assert
        assertThat(revoked).isFalse();
        assertThat(store.revocationUnavailable()).isEqualTo(1);
    }

    @Test
    void testIsRevoked_DatabaseDownFailClosed_Rejected503AndCounts() {
        // Arrange: fail-closed, the database is unreachable and the token was never checked here
        ConfigRepository repository = mock(ConfigRepository.class);
        when(repository.isRevoked("t-1")).thenThrow(new DataAccessResourceFailureException("connection refused"));
        CircuitBreaker breaker = new CircuitBreaker("config-db", ConfigStore.DEFAULT_DATABASE_BREAKER, Clock.systemUTC());
        ConfigStore store = new ConfigStore(repository, Duration.ofSeconds(5), Duration.ofSeconds(5), breaker, false,
                Clock.systemUTC());

        // Act
        Throwable thrown = catchThrowable(() -> store.isRevoked("t-1"));

        // Assert: a dependency failure (503), not "revoked" (401)
        assertThat(thrown).isInstanceOfSatisfying(GatewayException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.CONFIG_UNAVAILABLE);
            assertThat(e.headers()).containsEntry("Retry-After", "1");
        });
        assertThat(store.revocationUnavailable()).isEqualTo(1);
    }

    @Test
    void testIsRevoked_DatabaseDownFailClosedButCheckedBefore_KeepsLastAnswer() {
        // Arrange: fail-closed; the token was checked once (not revoked), then the database goes down
        ConfigStoreIT.MutableClock clock = new ConfigStoreIT.MutableClock();
        ConfigRepository repository = mock(ConfigRepository.class);
        when(repository.isRevoked("t-1")).thenReturn(false)
                .thenThrow(new DataAccessResourceFailureException("connection refused"));
        CircuitBreaker breaker = new CircuitBreaker("config-db", ConfigStore.DEFAULT_DATABASE_BREAKER, clock);
        ConfigStore store = new ConfigStore(repository, Duration.ofSeconds(5), Duration.ofSeconds(5), breaker, false, clock);
        store.isRevoked("t-1");
        clock.advance(Duration.ofSeconds(6)); // past the TTL: the next check tries the database again

        // Act
        boolean revoked = store.isRevoked("t-1");

        // Assert: stale if error, no 503 for a token already known
        assertThat(revoked).isFalse();
    }

    @Test
    void testDatabaseKeepsFailing_BreakerOpens_ReadsFailFastThenAProbeClosesIt() {
        // Arrange: a breaker that opens after 2 failures out of 2, for 5s
        ConfigStoreIT.MutableClock clock = new ConfigStoreIT.MutableClock();
        CircuitBreaker breaker = new CircuitBreaker("config-db", new CircuitBreaker.Settings(50, 2, 2, Duration.ofSeconds(5), 1), clock);
        ConfigRepository repository = mock(ConfigRepository.class);
        when(repository.tenant(anyString())).thenThrow(new DataAccessResourceFailureException("connection refused"));
        when(repository.isRevoked(anyString())).thenThrow(new DataAccessResourceFailureException("connection refused"));
        ConfigStore store = new ConfigStore(repository, Duration.ofSeconds(5), Duration.ofSeconds(5), breaker, clock);

        // Act: two failed reads open it; the next ones do not reach the database
        assertThatThrownBy(() -> store.tenant("a")).isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> store.tenant("b")).isInstanceOf(GatewayException.class);
        Throwable fastFail = catchThrowable(() -> store.tenant("c"));
        boolean revoked = store.isRevoked("t-1");

        // Assert
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(fastFail).isInstanceOfSatisfying(GatewayException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.CONFIG_UNAVAILABLE);
            assertThat(e.headers()).containsEntry("Retry-After", "5"); // when the breaker will probe
        });
        assertThat(revoked).isFalse(); // revocation still fails open, now without waiting
        verify(repository, times(2)).tenant(anyString());
        verify(repository, never()).isRevoked(anyString());

        // ...and after the cool-down one successful read (the probe) closes it again
        clock.advance(Duration.ofSeconds(5));
        doReturn(Optional.empty()).when(repository).tenant("d");
        assertThat(store.tenant("d")).isEmpty();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
