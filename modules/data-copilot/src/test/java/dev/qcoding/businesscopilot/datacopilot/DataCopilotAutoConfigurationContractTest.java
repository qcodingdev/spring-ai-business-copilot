package dev.qcoding.businesscopilot.datacopilot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DataCopilotAutoConfigurationContractTest {

    @Test
    void publishesAutoConfigurationWithoutHostPackageScanning() throws IOException {
        String resource = "META-INF/spring/"
                + "org.springframework.boot.autoconfigure.AutoConfiguration.imports";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .contains(DataCopilotAutoConfiguration.class.getName());
        }
    }
}
