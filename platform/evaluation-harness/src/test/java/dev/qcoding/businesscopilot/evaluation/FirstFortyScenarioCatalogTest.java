package dev.qcoding.businesscopilot.evaluation;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Structural gate for the executable D/K/S/R/H/X scenario manifest. */
class FirstFortyScenarioCatalogTest {

    @Test
    void catalogDefinesExactlyFortyExecutableProductionFacingScenarios() throws Exception {
        List<String> lines;
        try (var input = getClass().getResourceAsStream("/first-40-scenarios.tsv")) {
            assertThat(input).as("first-40-scenarios.tsv must be packaged").isNotNull();
            try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                lines = reader.lines().toList();
            }
        }
        assertThat(lines.getFirst())
                .isEqualTo("scenario_id\tmodule\tlayer\ttest_class\ttest_method");

        Map<String, ScenarioMapping> mappings = new LinkedHashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] fields = line.split("\t", -1);
            assertThat(fields).as("catalog row: %s", line).hasSize(5);
            ScenarioMapping previous = mappings.put(fields[0],
                    new ScenarioMapping(fields[1], fields[2], fields[3], fields[4]));
            assertThat(previous).as("duplicate scenario id: %s", fields[0]).isNull();
        }

        assertThat(mappings.keySet()).containsExactlyElementsOf(expectedIds());
        assertThat(mappings.values()).allSatisfy(mapping -> {
            assertThat(mapping.module()).isNotBlank();
            assertThat(mapping.layer()).isIn(
                    "PRODUCTION_SERVICE", "SECURITY_GUARDRAIL", "DATABASE", "RUNTIME");
            assertThat(mapping.testClass()).startsWith("dev.qcoding.businesscopilot.");
            assertThat(mapping.testMethod()).matches("[A-Za-z][A-Za-z0-9]*");
        });
        assertThat(mappings.values().stream().map(ScenarioMapping::layer).collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(Set.of(
                        "PRODUCTION_SERVICE", "SECURITY_GUARDRAIL", "DATABASE", "RUNTIME"));
    }

    private List<String> expectedIds() {
        return java.util.stream.Stream.of(
                        ids("D", 6), ids("K", 6), ids("S", 6),
                        ids("R", 6), ids("H", 6), ids("X", 10))
                .flatMap(List::stream)
                .toList();
    }

    private List<String> ids(String prefix, int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(index -> "%s-%02d".formatted(prefix, index))
                .toList();
    }

    private record ScenarioMapping(String module, String layer,
                                   String testClass, String testMethod) {
    }
}
