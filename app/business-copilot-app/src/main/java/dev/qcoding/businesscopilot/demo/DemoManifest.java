package dev.qcoding.businesscopilot.demo;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** classpath 虚构数据 manifest 的反序列化模型。 */
public record DemoManifest(
        int manifestVersion,
        Instant generatedAt,
        List<KnowledgeSeed> knowledgeDocuments,
        List<ScenarioSeed> scenarios) {

    public record KnowledgeSeed(
            UUID logicalDocumentId,
            String resource,
            String category,
            String visibilityScope) {
    }

    public record ScenarioSeed(
            String scenarioId,
            DemoModule module,
            String title,
            String description,
            String inputTemplate,
            List<DemoOperation> allowedOperations,
            Map<String, Object> dataScope,
            String dataScopeLabel,
            int version,
            Map<String, Object> sampleResult) {
    }
}
