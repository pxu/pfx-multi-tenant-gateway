package com.example.gateway.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The RBAC module is used only through its API, so it could move into its own service (with a
 * remote {@link Authorizer}) without touching the gateway. {@code GatewayConfig} is the composition
 * root and may build the implementation.
 */
class RbacBoundaryTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/gateway");
    private static final Set<String> API = Set.of("Authorizer", "Subject", "Permission", "RoleAssignments");
    private static final Pattern IMPORT = Pattern.compile("^import com\\.example\\.gateway\\.rbac\\.(\\w+);", Pattern.MULTILINE);

    @Test
    void testGatewayCode_ImportsOnlyTheRbacApi() throws IOException {
        // Arrange
        List<Path> gatewayFiles;
        try (Stream<Path> files = Files.walk(MAIN)) {
            gatewayFiles = files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.startsWith(MAIN.resolve("rbac")) && !f.endsWith("GatewayConfig.java"))
                    .toList();
        }

        // Act
        List<String> violations = new ArrayList<>();
        for (Path file : gatewayFiles) {
            Matcher m = IMPORT.matcher(Files.readString(file));
            while (m.find()) {
                if (!API.contains(m.group(1))) {
                    violations.add(MAIN.relativize(file) + " imports rbac." + m.group(1));
                }
            }
        }

        // Assert
        assertThat(gatewayFiles).isNotEmpty();
        assertThat(violations).isEmpty();
    }
}
