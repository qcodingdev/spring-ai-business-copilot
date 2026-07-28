package dev.qcoding.businesscopilot.supportcopilot.integration;

public record SupportExternalConnection(
        long id,
        String connectionKey,
        String displayName,
        SupportExternalProvider provider,
        String baseUrl,
        String secretRef,
        boolean enabled,
        String ownerActorId) {
}
