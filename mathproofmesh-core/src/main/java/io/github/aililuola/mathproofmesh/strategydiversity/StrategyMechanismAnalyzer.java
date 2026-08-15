package io.github.aililuola.mathproofmesh.strategydiversity;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels;
import io.github.aililuola.mathproofmesh.proofcontrol.StrategyBlueprintCompiler;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Compiles server-owned structural identities; presentation metadata never enters the hard hash. */
public final class StrategyMechanismAnalyzer {
  private final CriticalClaimKeyCompiler claimKeys;
  private final StrategyMechanismStructureCompiler structures;

  public StrategyMechanismAnalyzer() {
    this(new CriticalClaimKeyCompiler(), new StrategyMechanismStructureCompiler());
  }

  public StrategyMechanismAnalyzer(CriticalClaimKeyCompiler claimKeys) {
    this(claimKeys, new StrategyMechanismStructureCompiler());
  }

  StrategyMechanismAnalyzer(
      CriticalClaimKeyCompiler claimKeys, StrategyMechanismStructureCompiler structures) {
    this.claimKeys = java.util.Objects.requireNonNull(claimKeys, "claimKeys");
    this.structures = java.util.Objects.requireNonNull(structures, "structures");
  }

  public StrategyMechanismSignature signature(
      String problemHash,
      String rootGoalHash,
      StrategyCard strategy,
      ProofControlModels.Strategy controlStrategy,
      StrategyBlueprintCompiler.Compilation compilation) {
    return signature(
        problemHash,
        rootGoalHash,
        strategy,
        controlStrategy,
        compilation,
        Map.of());
  }

  public StrategyMechanismSignature signature(
      String problemHash,
      String rootGoalHash,
      StrategyCard strategy,
      ProofControlModels.Strategy controlStrategy,
      StrategyBlueprintCompiler.Compilation compilation,
      Map<String, CriticalClaimContext> claimContexts) {
    return signature(
        problemHash,
        rootGoalHash,
        strategy,
        controlStrategy,
        compilation,
        claimContexts,
        Set.of("main-goal:" + rootGoalHash));
  }

  public StrategyMechanismSignature signature(
      String problemHash,
      String rootGoalHash,
      StrategyCard strategy,
      ProofControlModels.Strategy controlStrategy,
      StrategyBlueprintCompiler.Compilation compilation,
      Map<String, CriticalClaimContext> claimContexts,
      Set<String> canonicalTargetIds) {
    java.util.Objects.requireNonNull(strategy, "strategy");
    java.util.Objects.requireNonNull(controlStrategy, "controlStrategy");
    java.util.Objects.requireNonNull(compilation, "compilation");
    StrategyBlueprintCompiler.Blueprint blueprint = compilation.blueprint();
    StrategyMechanismStructureCompiler.Structure structure =
        structures.compile(canonicalTargetIds, strategy, blueprint);

    Set<String> requiredClaims = new LinkedHashSet<>();
    strategy.criticalClaims().stream()
        .filter(claim -> "required".equals(claim.necessity()))
        .map(
            claim ->
                claimKeys
                    .compile(
                        problemHash,
                        claim,
                        claimContexts == null
                            ? CriticalClaimContext.empty()
                            : claimContexts.getOrDefault(
                                claim.claimId(), CriticalClaimContext.empty()))
                    .semanticKey())
        .forEach(requiredClaims::add);

    Map<String, Object> structural = new LinkedHashMap<>();
    structural.put("problem_hash", problemHash);
    structural.put("root_goal_hash", rootGoalHash);
    structural.put("targets", structure.canonicalTargetIds().stream().sorted().toList());
    structural.put("required_claims", requiredClaims.stream().sorted().toList());
    structural.put("domain_roles", structure.domainRoleHash());
    structural.put("representation", structure.representation().name());
    structural.put("dependency_dag", structure.topologyHash());
    structural.put("operations", structure.operationHash());
    structural.put("operation_graph_known", structure.operationGraphKnown());
    if (!structure.operationGraphKnown()) {
      structural.put("unknown_operation_identity", strategy.strategyId());
    }
    structural.put("falsification", structure.falsificationHash());
    return new StrategyMechanismSignature(
        problemHash,
        rootGoalHash,
        structure.canonicalTargetIds(),
        requiredClaims,
        structure.domainRoleHash(),
        structure.representation().name(),
        structure.topologyHash(),
        structure.operationHash(),
        structure.falsificationHash(),
        StrategySemanticNormalizer.hash(structural),
        structure.operationGraphKnown());
  }

  public StrategyMechanismProfile profile(
      StrategyCard strategy, StrategyBlueprintCompiler.Compilation compilation) {
    java.util.Objects.requireNonNull(strategy, "strategy");
    java.util.Objects.requireNonNull(compilation, "compilation");
    EnumSet<StrategyMechanismPrimitive> result =
        EnumSet.noneOf(StrategyMechanismPrimitive.class);
    String text =
        String.join(
                " ",
                strategy.coreIdea(),
                strategy.bottleneck(),
                String.join(" ", strategy.expectedLemmas()),
                String.join(" ", strategy.tags()))
            .toLowerCase(Locale.ROOT);
    add(text, result, StrategyMechanismPrimitive.CONTRADICTION, "contradiction", "contradict");
    add(text, result, StrategyMechanismPrimitive.INDUCTION, "induction", "inductive");
    add(text, result, StrategyMechanismPrimitive.MINIMAL_COUNTEREXAMPLE, "minimal counterexample");
    add(text, result, StrategyMechanismPrimitive.EXTREMAL, "extremal", "longest", "shortest", "maximal", "minimal");
    add(text, result, StrategyMechanismPrimitive.INVARIANT, "invariant", "preserved quantity");
    add(text, result, StrategyMechanismPrimitive.CONSTRUCTIVE, "construct", "explicit witness");
    add(text, result, StrategyMechanismPrimitive.DECOMPOSITION, "decomposition", "partition", "split into");
    add(text, result, StrategyMechanismPrimitive.TRANSFORMATION, "transform", "reduction", "map to");
    add(text, result, StrategyMechanismPrimitive.DUALITY, "duality", "dual");
    add(text, result, StrategyMechanismPrimitive.ALGEBRAIC, "algebra", "matrix", "linear map");
    add(text, result, StrategyMechanismPrimitive.GEOMETRIC, "geometric", "geometry");
    add(text, result, StrategyMechanismPrimitive.SPECTRAL, "spectral", "eigenvalue");
    add(text, result, StrategyMechanismPrimitive.PROBABILISTIC, "probabilistic", "expectation", "random");
    add(text, result, StrategyMechanismPrimitive.COMBINATORIAL, "combinatorial", "counting", "pigeonhole");
    add(text, result, StrategyMechanismPrimitive.FINITE_STATE, "finite state", "automaton");
    add(text, result, StrategyMechanismPrimitive.COMPUTATIONAL_FALSIFICATION, "exhaustive search", "counterexample search");
    if (result.isEmpty()) {
      result.add(StrategyMechanismPrimitive.DIRECT);
    }
    return new StrategyMechanismProfile(result);
  }

  public StrategyMechanismRelation relation(
      StrategyMechanismSignature left,
      StrategyMechanismSignature right,
      Set<String> unresolvedRequiredClaimKeys,
      StrategyMechanismProfile leftProfile,
      StrategyMechanismProfile rightProfile) {
    if (!left.problemHash().equals(right.problemHash())
        || !left.rootGoalHash().equals(right.rootGoalHash())) {
      return StrategyMechanismRelation.UNKNOWN;
    }
    if (left.operationGraphKnown()
        && right.operationGraphKnown()
        && left.structuralSignatureHash().equals(right.structuralSignatureHash())) {
      return StrategyMechanismRelation.SAME_STRUCTURAL_MECHANISM;
    }
    Set<String> common = new LinkedHashSet<>(left.requiredClaimSemanticKeys());
    common.retainAll(right.requiredClaimSemanticKeys());
    common.retainAll(unresolvedRequiredClaimKeys == null ? Set.of() : unresolvedRequiredClaimKeys);
    if (!common.isEmpty()) {
      return StrategyMechanismRelation.SHARED_UNRESOLVED_REQUIRED_CLAIM;
    }
    Set<StrategyMechanismPrimitive> intersection = new LinkedHashSet<>(leftProfile.primitives());
    intersection.retainAll(rightProfile.primitives());
    return intersection.isEmpty()
        ? StrategyMechanismRelation.COMPLEMENTARY
        : StrategyMechanismRelation.DISTINCT;
  }

  private static void add(
      String text,
      Set<StrategyMechanismPrimitive> destination,
      StrategyMechanismPrimitive primitive,
      String... markers) {
    for (String marker : markers) {
      if (text.contains(marker)) {
        destination.add(primitive);
        return;
      }
    }
  }
}
