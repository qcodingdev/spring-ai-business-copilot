package dev.qcoding.businesscopilot.reportcopilot.enterprise;

import java.util.List;
import java.util.UUID;

/** Server-created ownership proof carried from source collection to the short draft transaction. */
public record ReportPublicationClaim(String ownerActorId, UUID handoffToken, List<String> handoffReferences,
                                     Long scheduleId, UUID scheduleToken, Long scheduleRunId, String comparisonReference) {
    public ReportPublicationClaim(String ownerActorId, UUID handoffToken, List<String> handoffReferences,
                                  Long scheduleId, UUID scheduleToken, Long scheduleRunId) {
        this(ownerActorId, handoffToken, handoffReferences, scheduleId, scheduleToken, scheduleRunId, null);
    }

    public List<String> allReferences() {
        return java.util.stream.Stream.concat(handoffReferences.stream(),
                comparisonReference == null || comparisonReference.isBlank() ? java.util.stream.Stream.empty()
                        : java.util.stream.Stream.of(comparisonReference)).distinct().sorted().toList();
    }

    public ReportPublicationClaim {
        handoffReferences = handoffReferences == null ? List.of() : handoffReferences.stream().distinct().sorted().toList();
    }
}
