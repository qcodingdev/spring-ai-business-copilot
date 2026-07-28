package dev.qcoding.businesscopilot.knowledgecopilot.source;

import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeVisibilityScope;

import java.util.Map;

public record KnowledgeSourceConnection(
        long id,
        String connectionKey,
        String displayName,
        KnowledgeSourceProvider provider,
        String baseUrl,
        String rootReference,
        String secretRef,
        Map<String, KnowledgeVisibilityScope> groupMapping,
        KnowledgeVisibilityScope defaultVisibility,
        boolean enabled,
        String ownerActorId) {
}
