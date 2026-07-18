package dev.qcoding.businesscopilot.aicore;

/** Rendered prompt plus non-sensitive template metadata. */
public record RenderedPrompt(String content, PromptTemplateMetadata metadata) {
}
