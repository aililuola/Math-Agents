package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.List;

public record PivotDirectionChange(
    String oldDirectionSignature,
    String newDirectionSignature,
    String mathematicalReason,
    List<String> evidenceRefs) {
  public PivotDirectionChange {
    oldDirectionSignature = PivotValues.required(oldDirectionSignature, "oldDirectionSignature");
    newDirectionSignature = PivotValues.required(newDirectionSignature, "newDirectionSignature");
    mathematicalReason = PivotValues.required(mathematicalReason, "mathematicalReason");
    evidenceRefs = PivotValues.copy(evidenceRefs);
    if (ProofIdentity.normalizeText(oldDirectionSignature)
        .equals(ProofIdentity.normalizeText(newDirectionSignature))) {
      throw new IllegalArgumentException("direction signatures must differ");
    }
  }

  @Override
  public List<String> evidenceRefs() {
    return List.copyOf(evidenceRefs);
  }
}
