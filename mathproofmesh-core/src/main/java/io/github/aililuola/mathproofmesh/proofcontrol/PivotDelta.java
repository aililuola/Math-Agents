package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Server-identified, immutable mathematical state delta for one route strategy epoch. */
public record PivotDelta(
    String pivotId,
    String problemHash,
    String rootGoalHash,
    String routeId,
    String sourceStrategyId,
    String proposedStrategyId,
    List<PivotTransformationType> transformationTypes,
    List<PivotObstructionRef> obstructionRefs,
    List<MathematicalObjectChange> objectChanges,
    List<PivotDirectionChange> directionChanges,
    List<PivotAssumptionChange> assumptionChanges,
    List<PivotClaimUseChange> claimUseChanges,
    List<PivotObligationChange> obligationChanges,
    StrategyCard proposedStrategy,
    String rationale,
    String structuralDeltaHash) {
  public PivotDelta {
    problemHash = PivotValues.required(problemHash, "problemHash");
    rootGoalHash = PivotValues.required(rootGoalHash, "rootGoalHash");
    routeId = PivotValues.required(routeId, "routeId");
    sourceStrategyId = PivotValues.required(sourceStrategyId, "sourceStrategyId");
    proposedStrategyId = PivotValues.required(proposedStrategyId, "proposedStrategyId");
    transformationTypes = unique("transformationTypes", transformationTypes);
    obstructionRefs = uniqueBy("obstructionRefs", obstructionRefs, PivotObstructionRef::obstructionId);
    objectChanges =
        uniqueBy(
            "objectChanges",
            objectChanges,
            change -> Objects.toString(change.oldObjectId(), "") + "->" + Objects.toString(change.newObjectId(), ""));
    directionChanges = PivotValues.copy(directionChanges);
    assumptionChanges = PivotValues.copy(assumptionChanges);
    claimUseChanges = uniqueBy("claimUseChanges", claimUseChanges, PivotClaimUseChange::claimId);
    obligationChanges =
        uniqueBy("obligationChanges", obligationChanges, PivotObligationChange::obligationId);
    proposedStrategy = Objects.requireNonNull(proposedStrategy, "proposedStrategy");
    rationale = PivotValues.required(rationale, "rationale");
    if (!proposedStrategyId.equals(proposedStrategy.strategyId())) {
      throw new IllegalArgumentException("proposedStrategyId must match proposed strategy");
    }
    if (!proposedStrategy.parentStrategyIds().contains(sourceStrategyId)) {
      throw new IllegalArgumentException("pivot strategy must retain its source strategy as parent");
    }
    String computedDeltaHash = computeStructuralHash(
        transformationTypes,
        objectChanges,
        directionChanges,
        assumptionChanges,
        claimUseChanges,
        obligationChanges,
        proposedStrategy);
    if (structuralDeltaHash != null
        && !structuralDeltaHash.isBlank()
        && !sameServerOwnedValue(computedDeltaHash, structuralDeltaHash.strip())) {
      throw new IllegalArgumentException("structuralDeltaHash is server-owned");
    }
    structuralDeltaHash = computedDeltaHash;
    String computedPivotId = computePivotId(
        problemHash, routeId, sourceStrategyId, obstructionRefs, structuralDeltaHash);
    if (pivotId != null
        && !pivotId.isBlank()
        && !sameServerOwnedValue(computedPivotId, pivotId.strip())) {
      throw new IllegalArgumentException("pivotId is server-owned");
    }
    pivotId = computedPivotId;
  }

  public static PivotDelta create(
      String problemHash,
      String rootGoalHash,
      String routeId,
      String sourceStrategyId,
      List<PivotTransformationType> transformationTypes,
      List<PivotObstructionRef> obstructionRefs,
      List<MathematicalObjectChange> objectChanges,
      List<PivotDirectionChange> directionChanges,
      List<PivotAssumptionChange> assumptionChanges,
      List<PivotClaimUseChange> claimUseChanges,
      List<PivotObligationChange> obligationChanges,
      StrategyCard proposedStrategy,
      String rationale) {
    return new PivotDelta(
        null,
        problemHash,
        rootGoalHash,
        routeId,
        sourceStrategyId,
        proposedStrategy.strategyId(),
        transformationTypes,
        obstructionRefs,
        objectChanges,
        directionChanges,
        assumptionChanges,
        claimUseChanges,
        obligationChanges,
        proposedStrategy,
        rationale,
        null);
  }

  @Override
  public List<PivotTransformationType> transformationTypes() {
    return List.copyOf(transformationTypes);
  }

  @Override
  public List<PivotObstructionRef> obstructionRefs() {
    return List.copyOf(obstructionRefs);
  }

  @Override
  public List<MathematicalObjectChange> objectChanges() {
    return List.copyOf(objectChanges);
  }

  @Override
  public List<PivotDirectionChange> directionChanges() {
    return List.copyOf(directionChanges);
  }

  @Override
  public List<PivotAssumptionChange> assumptionChanges() {
    return List.copyOf(assumptionChanges);
  }

  @Override
  public List<PivotClaimUseChange> claimUseChanges() {
    return List.copyOf(claimUseChanges);
  }

  @Override
  public List<PivotObligationChange> obligationChanges() {
    return List.copyOf(obligationChanges);
  }

  private static String computeStructuralHash(
      List<PivotTransformationType> types,
      List<MathematicalObjectChange> objects,
      List<PivotDirectionChange> directions,
      List<PivotAssumptionChange> assumptions,
      List<PivotClaimUseChange> claims,
      List<PivotObligationChange> obligations,
      StrategyCard strategy) {
    return CanonicalJson.stableHash(
        Map.of(
            "types", types.stream().map(Enum::name).sorted().toList(),
            "objects", objects,
            "directions", directions,
            "assumptions", assumptions,
            "claims", claims,
            "obligations", obligations,
            "strategy_semantics",
                Map.of(
                    "bottleneck", ProofIdentity.normalizeText(strategy.bottleneck()),
                    "critical_claims", strategy.criticalClaims(),
                    "expected_lemmas", ProofIdentity.canonicalStrings(strategy.expectedLemmas()),
                    "prerequisites", ProofIdentity.canonicalStrings(strategy.prerequisites()),
                    "falsification", ProofIdentity.normalizeText(strategy.falsificationTest()))));
  }

  private static String computePivotId(
      String problemHash,
      String routeId,
      String sourceStrategyId,
      List<PivotObstructionRef> obstructionRefs,
      String deltaHash) {
    return "pivot_"
        + CanonicalJson.stableHash(
                Map.of(
                    "problem_hash", problemHash,
                    "route_id", routeId,
                    "source_strategy_id", sourceStrategyId,
                    "obstruction_ids",
                        obstructionRefs.stream()
                            .map(PivotObstructionRef::obstructionId)
                            .sorted()
                            .toList(),
                    "structural_delta_hash", deltaHash))
            .substring(0, 24);
  }

  private static <T> List<T> unique(String name, List<T> values) {
    List<T> copy = PivotValues.copy(values);
    if (new LinkedHashSet<>(copy).size() != copy.size()) {
      throw new IllegalArgumentException(name + " contains duplicates");
    }
    return copy;
  }

  private static boolean sameServerOwnedValue(String expected, String supplied) {
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
  }

  private static <T> List<T> uniqueBy(
      String name, List<T> values, java.util.function.Function<T, String> key) {
    List<T> copy = PivotValues.copy(values);
    LinkedHashSet<String> keys = new LinkedHashSet<>();
    for (T value : copy) {
      if (!keys.add(PivotValues.required(key.apply(value), name + " key"))) {
        throw new IllegalArgumentException(name + " contains duplicate identities");
      }
    }
    return copy;
  }
}
