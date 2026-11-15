package com.example.gateway.admin;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.gateway.error.GatewayException;
import org.junit.jupiter.api.Test;

class UpstreamUrlValidatorTest {

    private final UpstreamUrlValidator production = new UpstreamUrlValidator(false);
    private final UpstreamUrlValidator local = new UpstreamUrlValidator(true);

    @Test
    void testValidate_CloudMetadataAddress_Rejected() {
        assertThatThrownBy(() -> production.validate("http://169.254.169.254/latest/meta-data"))
                .isInstanceOf(GatewayException.class).hasMessageContaining("link-local");
        assertThatThrownBy(() -> local.validate("http://169.254.169.254")).isInstanceOf(GatewayException.class);
    }

    @Test
    void testValidate_WildcardAddress_Rejected() {
        assertThatThrownBy(() -> local.validate("http://0.0.0.0:8080")).isInstanceOf(GatewayException.class);
    }

    @Test
    void testValidate_Loopback_OnlyWhenAllowed() {
        assertThatThrownBy(() -> production.validate("http://127.0.0.1:8081")).hasMessageContaining("loopback");
        assertThatCode(() -> local.validate("http://localhost:8080")).doesNotThrowAnyException();
    }

    @Test
    void testValidate_PrivateNetwork_Allowed() {
        // Internal services live on private networks; that is what a gateway fronts.
        assertThatCode(() -> production.validate("http://10.0.0.12:8080")).doesNotThrowAnyException();
    }

    @Test
    void testValidate_UnresolvableHost_Rejected() {
        assertThatThrownBy(() -> production.validate("http://no-such-host.invalid")).hasMessageContaining("does not resolve");
    }
}
