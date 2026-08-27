package io.github.aililuola.mathproofmesh.strategydiversity;

import java.util.Set;

/** One server-classified proof operation and its bounded input/output roles. */
public record MechanismOperationNode(
    String nodeId,
    ProofOperationKind kind,
    Set<String> inputRoleIds,
    Set<String> outputRoleIds) {
  public MechanismOperationNode {
    nodeId = StrategySemanticNormalizer.require(nodeId, "nodeId");
    kind = java.util.Objects.requireNonNull(kind, "kind");
    inputRoleIds = inputRoleIds == null ? Set.of() : Set.copyOf(inputRoleIds);
    outputRoleIds = outputRoleIds == null ? Set.of() : Set.copyOf(outputRoleIds);
  }

  @Override
  public Set<String> inputRoleIds() {
    return Set.copyOf(inputRoleIds);
  }

  @Override
  public Set<String> outputRoleIds() {
    return Set.copyOf(outputRoleIds);
  }
}
