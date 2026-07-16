package dev.qcoding.businesscopilot.aicore;

/** Stable prompt identity; full prompt text is intentionally excluded from audit. */
public record PromptTemplateMetadata(String name, String version, String contentHash) {
}
