package dev.qcoding.businesscopilot.e2e;

import dev.qcoding.businesscopilot.BusinessCopilotApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit CI gate: ./mvnw -pl app/business-copilot-app -am -Dtest=RealBackendBrowserIT
 * -Dsurefire.failIfNoSpecifiedTests=false test. No browser or database availability skip. */
class RealBackendBrowserIT {
    @Test
    void fiveBusinessFlowsAgainstRealApplicationAndPostgres() throws Exception {
        Path frontend = Path.of("../../frontend").toAbsolutePath().normalize();
        Path evidence = Path.of("target/real-backend-e2e").toAbsolutePath();
        Files.createDirectories(evidence);
        try (var postgres = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16")
                .asCompatibleSubstituteFor("postgres"))) {
            postgres.start();
            try (var app = new SpringApplicationBuilder(BusinessCopilotApplication.class, ControlledModelConfiguration.class).run(
                    "--server.port=0", "--server.address=127.0.0.1",
                    "--spring.datasource.url=" + postgres.getJdbcUrl(),
                    "--spring.datasource.username=" + postgres.getUsername(),
                    "--spring.datasource.password=" + postgres.getPassword(),
                    "--spring.ai.model.chat=none", "--spring.ai.model.embedding=none",
                    "--business-copilot.runtime-mode=development", "--spring.profiles.active=default",
                    "--business-copilot.security.oidc.enabled=false",
                    "--business-copilot.data-copilot.datasource.enabled=false",
                    "--business-copilot.security.admin.username=admin", "--business-copilot.security.admin.password=admin-change-me",
                    "--business-copilot.security.operator.username=operator", "--business-copilot.security.operator.password=operator-change-me",
                    "--business-copilot.security.reviewer.username=reviewer", "--business-copilot.security.reviewer.password=reviewer-change-me")) {
                int port = ((WebServerApplicationContext) app).getWebServer().getPort();
                var builder = new ProcessBuilder("node", "node_modules/@playwright/test/cli.js", "test",
                        "--config=playwright.real.config.ts", "--workers=1", "--timeout=90000")
                        .directory(frontend.toFile()).redirectErrorStream(true)
                        .redirectOutput(evidence.resolve("playwright.log").toFile());
                builder.environment().put("E2E_BASE_URL", "http://127.0.0.1:" + port);
                builder.environment().put("PLAYWRIGHT_JSON_OUTPUT_NAME", evidence.resolve("results.json").toString());
                builder.environment().put("PLAYWRIGHT_HTML_OUTPUT_DIR", evidence.resolve("html").toString());
                Process process = builder.start();
                try {
                    assertThat(process.waitFor(15, TimeUnit.MINUTES)).as("browser gate timeout").isTrue();
                    assertThat(process.exitValue()).withFailMessage(() -> {
                        try { return Files.readString(evidence.resolve("playwright.log")); }
                        catch (Exception ex) { return "Read target/real-backend-e2e/playwright.log"; }
                    }).isZero();
                    var stats = new ObjectMapper().readTree(Files.readString(evidence.resolve("results.json"))).path("stats");
                    assertThat(stats.path("skipped").asInt(-1)).isZero();
                    assertThat(stats.path("expected").asInt()).isGreaterThanOrEqualTo(22);
                    LocalDeliveryChecks.capacity(frontend.getParent(), evidence, port);
                } finally {
                    process.descendants().forEach(ProcessHandle::destroy);
                    process.destroy();
                    if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
                }
            }
            LocalDeliveryChecks.restore(postgres, evidence);
        }
    }
}
