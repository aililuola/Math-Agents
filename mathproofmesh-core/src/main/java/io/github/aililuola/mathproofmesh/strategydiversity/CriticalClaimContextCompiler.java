package io.github.aililuola.mathproofmesh.strategydiversity;

import io.github.aililuola.mathproofmesh.contract.CriticalClaim;
import io.github.aililuola.mathproofmesh.contract.CriticalClaimContextBinding;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import io.github.aililuola.mathproofmesh.proofcontrol.StrategyBlueprintCompiler;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compiles server-validated, claim-local semantic contexts from a strategy blueprint. */
public final class CriticalClaimContextCompiler {
  public Map<String, CriticalClaimContext> compile(
      StrategyCard strategy,
      StrategyBlueprintCompiler.Compilation compilation,
      CriticalClaimContext rootContext) {
    return compile(strategy, compilation, rootContext, false);
  }

  /** Compiles a newly generated strategy and fails closed when any claim lacks a binding. */
  public Map<String, CriticalClaimContext> compileNewCandidate(
      StrategyCard strategy,
      StrategyBlueprintCompiler.Compilation compilation,
      CriticalClaimContext rootContext) {
    return compile(strategy, compilation, rootContext, true);
  }

  private static Map<String, CriticalClaimContext> compile(
      StrategyCard strategy,
      StrategyBlueprintCompiler.Compilation compilation,
      CriticalClaimContext rootContext,
      boolean requireExplicitBindings) {
    java.util.Objects.requireNonNull(strategy, "strategy");
    java.util.Objects.requireNonNull(compilation, "compilation");
    CriticalClaimContext root =
        rootContext == null ? CriticalClaimContext.empty() : rootContext;
    StrategyBlueprintCompiler.Blueprint blueprint = compilation.blueprint();
    Map<String, CriticalClaimContextBinding> bindings = bindings(strategy);
    if (requireExplicitBindings) {
      for (CriticalClaim claim : strategy.criticalClaims()) {
        if (!bindings.containsKey(claim.claimId())) {
          throw new IllegalArgumentException(
              "MISSING_CRITICAL_CLAIM_CONTEXT_BINDING:" + claim.claimId());
        }
      }
    }
    Map<String, StrategyBlueprintCompiler.Node> nodes = nodes(blueprint);
    List<StrategyBlueprintCompiler.Node> claimNodes =
        blueprint.nodes().stream()
            .filter(node -> "critical_claim".equals(node.sourceField()))
            .toList();
    Map<String, List<String>> incoming = incoming(blueprint);
    Map<String, List<String>> outgoing = outgoing(blueprint);
    Map<String, CriticalClaimContext> result = new LinkedHashMap<>();
    for (int index = 0; index < strategy.criticalClaims().size(); index++) {
      CriticalClaim claim = strategy.criticalClaims().get(index);
      CriticalClaimContextBinding binding = bindings.get(claim.claimId());
      result.put(
          claim.claimId(),
          binding == null
              ? root
              : compileBoundContext(
                  claim,
                  binding,
                  index,
                  root,
                  blueprint,
                  nodes,
                  claimNodes,
                  incoming,
                  outgoing));
    }
    return Map.copyOf(result);
  }

  private static CriticalClaimContext compileBoundContext(
      CriticalClaim claim,
      CriticalClaimContextBinding binding,
      int claimIndex,
      CriticalClaimContext root,
      StrategyBlueprintCompiler.Blueprint blueprint,
      Map<String, StrategyBlueprintCompiler.Node> nodes,
      List<StrategyBlueprintCompiler.Node> claimNodes,
      Map<String, List<String>> incoming,
      Map<String, List<String>> outgoing) {
    String claimNodeId =
        resolveClaimNode(binding, claimIndex, blueprint, nodes, claimNodes);
    LinkedHashSet<String> assumptions = new LinkedHashSet<>(root.assumptions());
    for (String dependencyId : incoming.getOrDefault(claimNodeId, List.of())) {
      assumptions.add(nodes.get(dependencyId).statement());
    }
    for (String nodeId :
        resolveAssumptionNodes(binding.localAssumptionNodeIds(), blueprint, nodes)) {
      if (!pathExists(nodeId, claimNodeId, outgoing)) {
        throw new IllegalArgumentException(
            "claim-local assumption node does not reach its bound claim: " + claim.claimId());
      }
      assumptions.add(nodes.get(nodeId).statement());
    }
    assumptions.addAll(binding.localAssumptions());

    List<QuantifierSpec> quantifiers = new ArrayList<>(root.quantifiers());
    Set<String> variableIds = new LinkedHashSet<>();
    root.quantifiers().forEach(value -> variableIds.add(value.variableId()));
    for (QuantifierSpec local : binding.quantifiers()) {
      if (!variableIds.add(local.variableId())) {
        throw new IllegalArgumentException(
            "claim-local quantifier reuses an authoritative variable id: "
                + local.variableId());
      }
      quantifiers.add(
          new QuantifierSpec(
              local.displayName(),
              local.domain(),
              local.kind(),
              quantifiers.size(),
              local.restrictions(),
              local.variableId()));
    }

    List<VariableBinding> variableBindings = new ArrayList<>(root.variableBindings());
    Set<String> bindingIds = new LinkedHashSet<>();
    root.variableBindings().forEach(value -> bindingIds.add(value.variableId()));
    for (VariableBinding local : binding.variableBindings()) {
      if (!bindingIds.add(local.variableId())) {
        throw new IllegalArgumentException(
            "claim-local variable binding reuses an authoritative variable id: "
                + local.variableId());
      }
      variableBindings.add(local);
    }

    LinkedHashSet<String> scope = new LinkedHashSet<>(root.scopeLimitations());
    scope.addAll(binding.scopeLimitations());
    scope.add("claim_node=" + boundedNodeRole(nodes.get(claimNodeId)));
    return new CriticalClaimContext(
        List.copyOf(assumptions),
        quantifiers,
        List.copyOf(scope),
        variableBindings,
        binding.polarity());
  }

  private static Map<String, CriticalClaimContextBinding> bindings(StrategyCard strategy) {
    Set<String> claimIds =
        strategy.criticalClaims().stream()
            .map(CriticalClaim::claimId)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    Map<String, CriticalClaimContextBinding> result = new LinkedHashMap<>();
    for (CriticalClaimContextBinding binding : strategy.criticalClaimContextBindings()) {
      if (!claimIds.contains(binding.claimId())) {
        throw new IllegalArgumentException(
            "critical claim context binding references an unknown claim: " + binding.claimId());
      }
      if (result.putIfAbsent(binding.claimId(), binding) != null) {
        throw new IllegalArgumentException(
            "DUPLICATE_CRITICAL_CLAIM_CONTEXT_BINDING:" + binding.claimId());
      }
    }
    return result;
  }

  private static String resolveClaimNode(
      CriticalClaimContextBinding binding,
      int claimIndex,
      StrategyBlueprintCompiler.Blueprint blueprint,
      Map<String, StrategyBlueprintCompiler.Node> nodes,
      List<StrategyBlueprintCompiler.Node> claimNodes) {
    String reference = binding.claimBlueprintNodeId();
    if (reference == null || reference.isBlank() || "@claim".equals(reference)) {
      if (claimIndex < claimNodes.size()) {
        return claimNodes.get(claimIndex).id();
      }
      if (!blueprint.directTargetNodeIds().isEmpty()) {
        return blueprint.directTargetNodeIds().getFirst();
      }
      throw new IllegalArgumentException(
          "UNBOUND_CRITICAL_CLAIM:" + binding.claimId());
    }
    if ("@main_goal".equals(reference)) {
      return blueprint.mainGoalNodeId();
    }
    if (!nodes.containsKey(reference)) {
      throw new IllegalArgumentException(
          "critical claim context binding references an unknown blueprint node: " + reference);
    }
    return reference;
  }

  private static Set<String> resolveAssumptionNodes(
      List<String> references,
      StrategyBlueprintCompiler.Blueprint blueprint,
      Map<String, StrategyBlueprintCompiler.Node> nodes) {
    Set<String> result = new LinkedHashSet<>();
    for (String raw : references) {
      String reference = raw == null ? "" : raw.strip();
      if ("@roots".equals(reference)) {
        result.addAll(blueprint.rootEntryNodeIds());
      } else if (!nodes.containsKey(reference)) {
        throw new IllegalArgumentException(
            "claim-local assumption references an unknown blueprint node: " + reference);
      } else {
        result.add(reference);
      }
    }
    return Set.copyOf(result);
  }

  private static Map<String, StrategyBlueprintCompiler.Node> nodes(
      StrategyBlueprintCompiler.Blueprint blueprint) {
    return blueprint.nodes().stream()
        .collect(
            java.util.stream.Collectors.toMap(
                StrategyBlueprintCompiler.Node::id,
                value -> value,
                (left, right) -> {
                  throw new IllegalArgumentException("blueprint contains duplicate node ids");
                },
                LinkedHashMap::new));
  }

  private static Map<String, List<String>> incoming(
      StrategyBlueprintCompiler.Blueprint blueprint) {
    Map<String, List<String>> result = new LinkedHashMap<>();
    blueprint.edges().forEach(
        edge ->
            result.computeIfAbsent(edge.targetId(), ignored -> new ArrayList<>())
                .add(edge.sourceId()));
    return result;
  }

  private static Map<String, List<String>> outgoing(
      StrategyBlueprintCompiler.Blueprint blueprint) {
    Map<String, List<String>> result = new LinkedHashMap<>();
    blueprint.edges().forEach(
        edge ->
            result.computeIfAbsent(edge.sourceId(), ignored -> new ArrayList<>())
                .add(edge.targetId()));
    return result;
  }

  private static boolean pathExists(
      String source, String target, Map<String, List<String>> outgoing) {
    ArrayDeque<String> queue = new ArrayDeque<>(List.of(source));
    Set<String> visited = new LinkedHashSet<>();
    while (!queue.isEmpty()) {
      String current = queue.removeFirst();
      if (!visited.add(current)) {
        continue;
      }
      if (current.equals(target)) {
        return true;
      }
      queue.addAll(outgoing.getOrDefault(current, List.of()));
    }
    return false;
  }

  private static String boundedNodeRole(StrategyBlueprintCompiler.Node node) {
    return node.kind().name().toLowerCase(java.util.Locale.ROOT)
        + ':'
        + node.sourceField();
  }
}
