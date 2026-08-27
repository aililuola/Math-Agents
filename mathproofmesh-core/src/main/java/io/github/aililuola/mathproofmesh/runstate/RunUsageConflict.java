package io.github.aililuola.mathproofmesh.runstate;

import java.util.List;

public record RunUsageConflict(
    String providerRequestId, String code, List<String> evidenceRefs) {
  public RunUsageConflict {
    providerRequestId = RunStateHashes.required(providerRequestId, "providerRequestId");
    code = RunStateHashes.required(code, "code");
    evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
  }

  @Override
  public List<String> evidenceRefs() {
    return List.copyOf(evidenceRefs);
  }
}
