package io.agenticpayments.replay;

import java.util.*;

public record ReplayReport(String reportVersion,String outcome,UUID executionId,UUID runId,String planDigest,
                           String configurationDigest,ReplayPlan.Artifacts artifactIdentities,String coreDigest,String journalDigest,String auditDigest,
                           String combinedDigest,List<String> rawInvariantChecks,Map<String,Object> normalizedSnapshot,
                           String reason) {
    public ReplayReport{rawInvariantChecks=List.copyOf(rawInvariantChecks);normalizedSnapshot=Collections.unmodifiableMap(new LinkedHashMap<>(normalizedSnapshot));}
    public String json(){return ReplayJson.write(this);}
}
