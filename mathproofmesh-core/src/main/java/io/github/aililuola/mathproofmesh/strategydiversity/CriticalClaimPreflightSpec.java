package io.github.aililuola.mathproofmesh.strategydiversity;

import io.github.aililuola.mathproofmesh.contract.CriticalClaim;
import java.util.List;

public record CriticalClaimPreflightSpec(
    String problemHash,
    CriticalClaim claim,
    CriticalClaimSemanticKey key,
    CriticalClaimContext context,
    String computationContractId,
    List<String> typedInputRefs) {
  public CriticalClaimPreflightSpec {
    problemHash = StrategySemanticNormalizer.require(problemHash, "problemHash");
    claim = java.util.Objects.requireNonNull(claim, "claim");
    key = java.util.Objects.requireNonNull(key, "key");
    context = context == null ? CriticalClaimContext.empty() : context;
    computationContractId =
        computationContractId == null ? "" : computationContractId.strip();
    typedInputRefs = typedInputRefs == null ? List.of() : List.copyOf(typedInputRefs);
    if (!StrategySemanticNormalizer.hashEquals(problemHash, key.problemHash())) {
      throw new IllegalArgumentException("preflight claim belongs to another problem");
    }
  }

  public CriticalClaimPreflightSpec(
      String problemHash,
      CriticalClaim claim,
      CriticalClaimSemanticKey key,
      String computationContractId,
      List<String> typedInputRefs) {
    this(
        problemHash,
        claim,
        key,
        CriticalClaimContext.empty(),
        computationContractId,
        typedInputRefs);
  }

  @Override
  public List<String> typedInputRefs() {
    return List.copyOf(typedInputRefs);
  }
}
