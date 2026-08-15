package io.github.aililuola.mathproofmesh.strategydiversity;

import java.util.Set;

public record StrategyMechanismSignature(
    String problemHash,
    String rootGoalHash,
    Set<String> targetCanonicalIds,
    Set<String> requiredClaimSemanticKeys,
    String domainObjectRoleSignature,
    String representationSignature,
    String dependencyDagShapeHash,
    String proofTransformationHash,
    String falsificationContractSignature,
    String structuralSignatureHash,
    boolean operationGraphKnown) {
  public StrategyMechanismSignature {
    problemHash = StrategySemanticNormalizer.require(problemHash, "problemHash");
    rootGoalHash = StrategySemanticNormalizer.require(rootGoalHash, "rootGoalHash");
    targetCanonicalIds = targetCanonicalIds == null ? Set.of() : Set.copyOf(targetCanonicalIds);
    requiredClaimSemanticKeys =
        requiredClaimSemanticKeys == null ? Set.of() : Set.copyOf(requiredClaimSemanticKeys);
    domainObjectRoleSignature =
        StrategySemanticNormalizer.require(domainObjectRoleSignature, "domainObjectRoleSignature");
    representationSignature =
        StrategySemanticNormalizer.require(representationSignature, "representationSignature");
    dependencyDagShapeHash =
        StrategySemanticNormalizer.require(dependencyDagShapeHash, "dependencyDagShapeHash");
    proofTransformationHash =
        StrategySemanticNormalizer.require(proofTransformationHash, "proofTransformationHash");
    falsificationContractSignature =
        StrategySemanticNormalizer.require(
            falsificationContractSignature, "falsificationContractSignature");
    structuralSignatureHash =
        StrategySemanticNormalizer.require(structuralSignatureHash, "structuralSignatureHash");
  }

  public StrategyMechanismSignature(
      String problemHash,
      String rootGoalHash,
      Set<String> targetCanonicalIds,
      Set<String> requiredClaimSemanticKeys,
      String domainObjectRoleSignature,
      String representationSignature,
      String dependencyDagShapeHash,
      String proofTransformationHash,
      String falsificationContractSignature,
      String structuralSignatureHash) {
    this(
        problemHash,
        rootGoalHash,
        targetCanonicalIds,
        requiredClaimSemanticKeys,
        domainObjectRoleSignature,
        representationSignature,
        dependencyDagShapeHash,
        proofTransformationHash,
        falsificationContractSignature,
        structuralSignatureHash,
        true);
  }

  @Override
  public Set<String> targetCanonicalIds() {
    return Set.copyOf(targetCanonicalIds);
  }

  @Override
  public Set<String> requiredClaimSemanticKeys() {
    return Set.copyOf(requiredClaimSemanticKeys);
  }
}
