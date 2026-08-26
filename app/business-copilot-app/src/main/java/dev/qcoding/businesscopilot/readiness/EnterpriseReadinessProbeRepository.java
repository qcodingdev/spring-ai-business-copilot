package dev.qcoding.businesscopilot.readiness;

import java.time.Instant;
import java.util.Map;

/** Reads only bounded aggregate counts; no business content leaves module-owned tables. */
public interface EnterpriseReadinessProbeRepository {

    Map<String, Long> probe(Instant now, EnterpriseReadinessProperties properties);
}
