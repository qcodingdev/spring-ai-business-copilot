package dev.qcoding.businesscopilot.aicore;

import java.util.Optional;

/** Optional governed source for published prompt versions. */
public interface PromptTemplateProvider {

    record Template(String content, String version, String contentHash) {
    }

    Optional<Template> activeTemplate(String location);
}
