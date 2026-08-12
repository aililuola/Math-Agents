package io.github.aililuola.mathproofmesh.communication;

import java.util.Set;

public record VerifiedDownstreamEffect(
    Set<String> committedStepIds,
    Set<String> closedObligationIds,
    Set<String> refutedClaimIds,
    Set<String> producedMessageIds,
    Set<String> blueprintRewriteRequestIds,
    boolean citedByFinalProof,
    double proofDebtBefore,
    double proofDebtAfter) {

  public VerifiedDownstreamEffect {
    committedStepIds = copy(committedStepIds);
    closedObligationIds = copy(closedObligationIds);
    refutedClaimIds = copy(refutedClaimIds);
    producedMessageIds = copy(producedMessageIds);
    blueprintRewriteRequestIds = copy(blueprintRewriteRequestIds);
  }

  private static Set<String> copy(Set<String> values) {
    return values == null ? Set.of() : Set.copyOf(values);
  }

  public static VerifiedDownstreamEffect none() {
    return new VerifiedDownstreamEffect(
        Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), false, 0.0, 0.0);
  }

  @Override
  public Set<String> committedStepIds() {
    return Set.copyOf(committedStepIds);
  }

  @Override
  public Set<String> closedObligationIds() {
    return Set.copyOf(closedObligationIds);
  }

  @Override
  public Set<String> refutedClaimIds() {
    return Set.copyOf(refutedClaimIds);
  }

  @Override
  public Set<String> producedMessageIds() {
    return Set.copyOf(producedMessageIds);
  }

  @Override
  public Set<String> blueprintRewriteRequestIds() {
    return Set.copyOf(blueprintRewriteRequestIds);
  }
}
