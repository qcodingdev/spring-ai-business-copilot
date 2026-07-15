package dev.qcoding.businesscopilot.commonweb.request;

/** Per-request trace and authenticated actor metadata. */
public record BusinessRequestContext(String requestId, String actorId) {
}
