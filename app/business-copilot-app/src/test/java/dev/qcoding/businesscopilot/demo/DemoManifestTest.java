package dev.qcoding.businesscopilot.demo;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DemoManifestTest {

    @Test
    void containsVersionedScenariosAndServerSideResourcesForAllFiveModules() throws Exception {
        DemoManifest manifest = new ObjectMapper().readValue(
                new ClassPathResource("demo/manifest.json").getInputStream(),
                DemoManifest.class);

        assertThat(manifest.scenarios()).hasSize(15);
        assertThat(manifest.scenarios().stream().map(DemoManifest.ScenarioSeed::scenarioId))
                .doesNotHaveDuplicates()
                .contains(
                        "knowledge-annual-leave-001",
                        "support-refund-expired-001",
                        "hr-java-ai-candidate-001",
                        "data-sales-trend-30d-001",
                        "report-monthly-business-001");
        assertThat(manifest.scenarios()).allSatisfy(scenario -> {
            assertThat(scenario.version()).isPositive();
            assertThat(scenario.allowedOperations()).isNotEmpty();
            assertThat(scenario.dataScope()).isNotEmpty();
            assertThat(scenario.sampleResult()).isNotEmpty();
        });
        assertThat(manifest.scenarios().stream()
                .map(DemoManifest.ScenarioSeed::module)
                .collect(Collectors.toSet()))
                .isEqualTo(Set.of(DemoModule.KNOWLEDGE, DemoModule.SUPPORT, DemoModule.HR,
                        DemoModule.DATA, DemoModule.REPORT));

        assertThat(manifest.knowledgeDocuments())
                .extracting(DemoManifest.KnowledgeSeed::visibilityScope)
                .contains("ALL", "HR_REVIEWER", "ADMIN");
        assertThat(manifest.knowledgeDocuments()).allSatisfy(document ->
                assertThat(new ClassPathResource(document.resource()).exists()).isTrue());
    }
}
