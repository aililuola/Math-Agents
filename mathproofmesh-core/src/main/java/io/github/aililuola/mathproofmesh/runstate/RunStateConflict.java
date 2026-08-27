package io.github.aililuola.mathproofmesh.runstate;

import java.util.List;

public record RunStateConflict(String code, String detail, List<String> evidenceRefs) {
  public RunStateConflict {
    code = RunStateHashes.required(code, "code");
    detail = RunStateHashes.required(detail, "detail");
    evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
  }

  @Override
  public List<String> evidenceRefs() {
    return List.copyOf(evidenceRefs);
  }
}
