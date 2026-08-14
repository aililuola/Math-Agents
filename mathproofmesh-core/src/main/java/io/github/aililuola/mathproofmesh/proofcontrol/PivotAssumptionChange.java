package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.List;

public record PivotAssumptionChange(
    String oldAssumption, String newAssumption, String reason, List<String> evidenceRefs) {
  public PivotAssumptionChange {
    oldAssumption = PivotValues.normalize(oldAssumption);
    newAssumption = PivotValues.normalize(newAssumption);
    reason = PivotValues.required(reason, "reason");
    evidenceRefs = PivotValues.copy(evidenceRefs);
    if (oldAssumption == null && newAssumption == null) {
      throw new IllegalArgumentException("an assumption change requires an old or new assumption");
    }
    if (oldAssumption != null
        && newAssumption != null
        && ProofIdentity.normalizeText(oldAssumption)
            .equals(ProofIdentity.normalizeText(newAssumption))) {
      throw new IllegalArgumentException("assumption change cannot be a paraphrase of itself");
    }
  }

  @Override
  public List<String> evidenceRefs() {
    return List.copyOf(evidenceRefs);
  }
}
