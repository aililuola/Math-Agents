package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import java.util.List;
import java.util.Objects;

public record PivotObligationChange(
    String obligationId,
    String canonicalTargetId,
    PivotObligationAction action,
    String proposedStatement,
    ObligationKind proposedKind,
    List<String> assumptions,
    List<String> dependencyIds,
    String reason) {
  public PivotObligationChange {
    obligationId = PivotValues.required(obligationId, "obligationId");
    canonicalTargetId = PivotValues.normalize(canonicalTargetId);
    action = Objects.requireNonNull(action, "action");
    proposedStatement = PivotValues.normalize(proposedStatement);
    assumptions = PivotValues.copy(assumptions);
    dependencyIds = PivotValues.copy(dependencyIds);
    reason = PivotValues.required(reason, "reason");
    if (action == PivotObligationAction.ADD_NEW_OBLIGATION) {
      PivotValues.required(proposedStatement, "proposedStatement");
      proposedKind = Objects.requireNonNull(proposedKind, "proposedKind");
    } else if (proposedStatement != null || proposedKind != null) {
      throw new IllegalArgumentException("existing obligation focus changes cannot rewrite the obligation");
    }
  }

  @Override
  public List<String> assumptions() {
    return List.copyOf(assumptions);
  }

  @Override
  public List<String> dependencyIds() {
    return List.copyOf(dependencyIds);
  }
}
