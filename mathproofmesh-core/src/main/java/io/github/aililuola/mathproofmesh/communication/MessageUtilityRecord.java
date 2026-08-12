package io.github.aililuola.mathproofmesh.communication;

import java.util.List;

public record MessageUtilityRecord(
    String deliveryKey,
    List<String> referencedStepIds,
    List<String> closedObligationIds,
    List<String> refutedClaimIds,
    List<String> producedMessageIds,
    List<String> blueprintRewriteRequestIds,
    boolean citedByFinalProof,
    double proofDebtReduction,
    double score) {

  public MessageUtilityRecord {
    referencedStepIds = copy(referencedStepIds);
    closedObligationIds = copy(closedObligationIds);
    refutedClaimIds = copy(refutedClaimIds);
    producedMessageIds = copy(producedMessageIds);
    blueprintRewriteRequestIds = copy(blueprintRewriteRequestIds);
    if (score < 0.0 || score > 1.0 || proofDebtReduction < 0.0) {
      throw new IllegalArgumentException("utility values are outside their valid range");
    }
  }

  private static List<String> copy(List<String> values) {
    return values == null ? List.of() : values.stream().distinct().sorted().toList();
  }

  @Override
  public List<String> referencedStepIds() {
    return List.copyOf(referencedStepIds);
  }

  @Override
  public List<String> closedObligationIds() {
    return List.copyOf(closedObligationIds);
  }

  @Override
  public List<String> refutedClaimIds() {
    return List.copyOf(refutedClaimIds);
  }

  @Override
  public List<String> producedMessageIds() {
    return List.copyOf(producedMessageIds);
  }

  @Override
  public List<String> blueprintRewriteRequestIds() {
    return List.copyOf(blueprintRewriteRequestIds);
  }
}
