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

  public StrategyMechanismAnalyzer() {
    this(new CriticalClaimKeyCompiler());
  }

  public StrategyMechanismAnalyzer(CriticalClaimKeyCompiler claimKeys) {
    this.claimKeys = java.util.Objects.requireNonNull(claimKeys, "claimKeys");
  }

  public StrategyMechanismSignature signature(
      String problemHash,
      String rootGoalHash,
      StrategyCard strategy,
      ProofControlModels.Strategy controlStrategy,
      StrategyBlueprintCompiler.Compilation compilation) {
    java.util.Objects.requireNonNull(strategy, "strategy");
    java.util.Objects.requireNonNull(controlStrategy, "controlStrategy");
    java.util.Objects.requireNonNull(compilation, "compilation");
    StrategyBlueprintCompiler.Blueprint blueprint = compilation.blueprint();

    Map<String, StrategyBlueprintCompiler.Node> nodes = new LinkedHashMap<>();
    blueprint.nodes().forEach(node -> nodes.put(node.id(), node));
    Set<String> targets = new LinkedHashSet<>();
    for (String targetId : blueprint.directTargetNodeIds()) {
      StrategyBlueprintCompiler.Node target = nodes.get(targetId);
      if (target != null) {
        targets.add(StrategySemanticNormalizer.hash(StrategySemanticNormalizer.normalize(target.statement())));
      }
    }
    if (targets.isEmpty()) {
      StrategyBlueprintCompiler.Node main = nodes.get(blueprint.mainGoalNodeId());
      if (main != null) {
        targets.add(StrategySemanticNormalizer.hash(StrategySemanticNormalizer.normalize(main.statement())));
      }
    }

    Set<String> requiredClaims = new LinkedHashSet<>();
    strategy.criticalClaims().stream()
        .filter(claim -> "required".equals(claim.necessity()))
        .map(claim -> claimKeys.compile(problemHash, claim).semanticKey())
        .forEach(requiredClaims::add);

    String domainRole =
        StrategySemanticNormalizer.hash(
            Map.of(
                "domain_objects", StrategySemanticNormalizer.normalizedSet(controlStrategy.domainObjects()),
                "roles", blueprint.nodes().stream().map(node -> node.kind().name()).sorted().toList()));
    String representation =
        StrategySemanticNormalizer.hash(
            Map.of(
                "objects", StrategySemanticNormalizer.normalizedSet(controlStrategy.domainObjects()),
                "prerequisites", StrategySemanticNormalizer.normalizedSet(strategy.prerequisites())));
    String dagShape =
        StrategySemanticNormalizer.hash(
            Map.of(
                "node_kinds", blueprint.nodes().stream().map(node -> node.kind().name()).sorted().toList(),
                "edges", blueprint.edges().stream().map(edge -> edge.relation().toLowerCase(Locale.ROOT)).sorted().toList(),
                "roots", blueprint.rootEntryNodeIds().size(),
                "targets", blueprint.directTargetNodeIds().size()));
    String transformation =
        StrategySemanticNormalizer.hash(
            Map.of(
                "mechanism", StrategySemanticNormalizer.normalize(strategy.coreIdea()),
                "lemmas", StrategySemanticNormalizer.normalizedSet(strategy.expectedLemmas()),
                "bottleneck", StrategySemanticNormalizer.normalize(strategy.bottleneck())));
    String falsification =
        StrategySemanticNormalizer.hash(
            Map.of(
                "strategy_test", StrategySemanticNormalizer.normalize(strategy.falsificationTest()),
                "claim_tests",
                    strategy.criticalClaims().stream()
                        .map(claim -> StrategySemanticNormalizer.normalize(claim.falsificationTest()))
                        .sorted()
                        .toList(),
                "preferred_tools",
                    strategy.criticalClaims().stream()
                        .map(claim -> StrategySemanticNormalizer.normalize(claim.preferredTool()))
                        .filter(value -> !value.isBlank())
                        .sorted()
                        .toList()));
    Map<String, Object> structural = new LinkedHashMap<>();
    structural.put("problem_hash", problemHash);
    structural.put("root_goal_hash", rootGoalHash);
    structural.put("targets", targets.stream().sorted().toList());
    structural.put("required_claims", requiredClaims.stream().sorted().toList());
    structural.put("domain_roles", domainRole);
    structural.put("representation", representation);
    structural.put("dependency_dag", dagShape);
    structural.put("transformation", transformation);
    structural.put("falsification", falsification);
    return new StrategyMechanismSignature(
        problemHash,
        rootGoalHash,
        targets,
        requiredClaims,
        domainRole,
        representation,
        dagShape,
        transformation,
        falsification,
        StrategySemanticNormalizer.hash(structural));
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
    if (left.structuralSignatureHash().equals(right.structuralSignatureHash())) {
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
