package io.github.aililuola.mathproofmesh.memory;

import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record LemmaMemorySnapshot(
    Map<String, ClaimCard> claims,
    Map<String, String> claimAliases,
    List<String> committedStepIds,
    List<MemoryAuditEvent> audit) {

  public LemmaMemorySnapshot {
    claims = claims == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(claims));
    claimAliases =
        claimAliases == null
            ? Map.of()
            : Map.copyOf(new LinkedHashMap<>(claimAliases));
    committedStepIds =
        committedStepIds == null ? List.of() : List.copyOf(committedStepIds);
    audit = audit == null ? List.of() : List.copyOf(audit);
  }
}
