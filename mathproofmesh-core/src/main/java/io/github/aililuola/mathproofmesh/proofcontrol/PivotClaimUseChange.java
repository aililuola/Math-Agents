package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.Objects;

public record PivotClaimUseChange(
    String claimId,
    String claimStatementHash,
    PivotClaimUsageAction action,
    String reason,
    PivotProposedClaimDraft proposedClaim) {
  public PivotClaimUseChange(
      String claimId,
      String claimStatementHash,
      PivotClaimUsageAction action,
      String reason) {
    this(claimId, claimStatementHash, action, reason, null);
  }

  public PivotClaimUseChange {
    claimId = PivotValues.required(claimId, "claimId");
    claimStatementHash = PivotValues.required(claimStatementHash, "claimStatementHash");
    action = Objects.requireNonNull(action, "action");
    reason = PivotValues.required(reason, "reason");
  }
}
