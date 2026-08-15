package io.github.aililuola.mathproofmesh.strategydiversity;

import io.github.aililuola.mathproofmesh.contract.MechanismOperationDeclaration;
import io.github.aililuola.mathproofmesh.contract.MechanismOperationKind;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.ToolRequest;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels;
import io.github.aililuola.mathproofmesh.proofcontrol.StrategyBlueprintCompiler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Compiles server-owned blueprint structure into a bounded operation graph and topology. */
final class StrategyMechanismStructureCompiler {
  Structure compile(
      Set<String> canonicalTargetIds,
      StrategyCard strategy,
      StrategyBlueprintCompiler.Blueprint blueprint) {
    StructuredRepresentationKind representation =
        representation(blueprint);
    Map<String, String> topologyLabels = topologyLabels(blueprint);
    CompiledOperations compiledOperations = operations(strategy, blueprint, topologyLabels);
    List<MechanismOperationNode> operations = compiledOperations.nodes();
    String topologyHash = topologyHash(blueprint, topologyLabels);
    String roleHash =
        StrategySemanticNormalizer.hash(
            Map.of(
                "representation", representation.name(),
                "node_roles",
                    blueprint.nodes().stream()
                        .map(StrategyMechanismStructureCompiler::boundedRole)
                        .sorted()
                        .toList(),
                "root_roles",
                    boundedRoles(blueprint, blueprint.rootEntryNodeIds()),
                "direct_target_roles",
                    boundedRoles(blueprint, blueprint.directTargetNodeIds())));
    String operationHash =
        StrategySemanticNormalizer.hash(
            operations.stream()
                .map(
                    operation ->
                        StrategySemanticNormalizer.hash(
                            Map.of(
                                "kind", operation.kind().name(),
                                "inputs", operation.inputRoleIds().stream().sorted().toList(),
                                "outputs",
                                    operation.outputRoleIds().stream().sorted().toList())))
                .sorted()
                .toList());
    String falsificationHash =
        StrategySemanticNormalizer.hash(
            strategy.calculationChecks().stream()
                .map(ToolRequest::kind)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .distinct()
                .sorted()
                .toList());
    return new Structure(
        canonicalTargetIds,
        representation,
        operations,
        roleHash,
        topologyHash,
        operationHash,
        falsificationHash,
        compiledOperations.known());
  }

  private static StructuredRepresentationKind representation(
      StrategyBlueprintCompiler.Blueprint blueprint) {
    String text =
        blueprint.nodes().stream()
            .filter(node -> node.kind() == ProofControlModels.BlueprintNodeKind.TARGET)
            .map(StrategyBlueprintCompiler.Node::statement)
            .collect(java.util.stream.Collectors.joining(" "))
            .toLowerCase(Locale.ROOT);
    if (has(text, "graph", "tree", "vertex", "vertices", "edge", "path", "leaf", "pendant")) {
      return StructuredRepresentationKind.GRAPH;
    }
    if (has(text, "kernel", "basis", "vector", "matrix", "linear map", "eigen")) {
      return StructuredRepresentationKind.LINEAR_ALGEBRA;
    }
    if (has(text, "sequence", "recurrence", "term", "a_n", "b_n")) {
      return StructuredRepresentationKind.SEQUENCE;
    }
    if (has(text, "prime", "divisor", "modulo", "congruence", "integer")) {
      return StructuredRepresentationKind.NUMBER_THEORETIC;
    }
    if (has(text, "finite set", "cardinality", "pigeonhole", "subset")) {
      return StructuredRepresentationKind.FINITE_SET;
    }
    if (has(text, "triangle", "circle", "angle", "geometric", "geometry")) {
      return StructuredRepresentationKind.GEOMETRIC;
    }
    if (has(text, "random", "probability", "expectation")) {
      return StructuredRepresentationKind.PROBABILISTIC;
    }
    if (has(text, "polynomial", "ring", "field", "group", "algebra")) {
      return StructuredRepresentationKind.ALGEBRAIC;
    }
    return StructuredRepresentationKind.ABSTRACT;
  }

  private static CompiledOperations operations(
      StrategyCard strategy,
      StrategyBlueprintCompiler.Blueprint blueprint,
      Map<String, String> topologyLabels) {
    Map<String, StrategyBlueprintCompiler.Node> nodes = new LinkedHashMap<>();
    blueprint.nodes().forEach(node -> nodes.put(node.id(), node));
    if (strategy.mechanismOperations().isEmpty()) {
      return new CompiledOperations(List.of(), false);
    }
    Map<String, List<String>> outgoing = outgoing(blueprint);
    Map<String, ProofOperationKind> declaredPairs = new LinkedHashMap<>();
    Set<String> operationIds = new java.util.LinkedHashSet<>();
    List<MechanismOperationNode> operations = new ArrayList<>();
    boolean known = true;
    for (MechanismOperationDeclaration declaration : strategy.mechanismOperations()) {
      if (!operationIds.add(declaration.operationId())) {
        throw new IllegalArgumentException(
            "duplicate mechanism operation declaration: " + declaration.operationId());
      }
      ProofOperationKind kind = operationKind(declaration.kind());
      known &= kind != ProofOperationKind.UNKNOWN;
      Set<String> inputs =
          resolveNodeIds(declaration.inputBlueprintNodeIds(), blueprint, nodes);
      Set<String> outputs =
          resolveNodeIds(declaration.outputBlueprintNodeIds(), blueprint, nodes);
      validateReachability(declaration.operationId(), inputs, outputs, outgoing);
      for (String input : inputs) {
        for (String output : outputs) {
          if (!pathExists(input, output, outgoing)) {
            continue;
          }
          String pair = input + ">" + output;
          ProofOperationKind previous = declaredPairs.putIfAbsent(pair, kind);
          if (previous != null && previous != kind) {
            throw new IllegalArgumentException(
                "mechanism operation declarations assign conflicting kinds to " + pair);
          }
        }
      }
      operations.add(
          new MechanismOperationNode(
              declaration.operationId(),
              kind,
              inputs.stream().map(topologyLabels::get).collect(java.util.stream.Collectors.toSet()),
              outputs.stream()
                  .map(topologyLabels::get)
                  .collect(java.util.stream.Collectors.toSet())));
    }
    return new CompiledOperations(operations, known);
  }

  private static ProofOperationKind operationKind(MechanismOperationKind kind) {
    return ProofOperationKind.valueOf(kind.name());
  }

  private static Set<String> resolveNodeIds(
      List<String> references,
      StrategyBlueprintCompiler.Blueprint blueprint,
      Map<String, StrategyBlueprintCompiler.Node> nodes) {
    Set<String> resolved = new java.util.LinkedHashSet<>();
    for (String raw : references) {
      String reference = raw == null ? "" : raw.strip();
      switch (reference) {
        case "@roots" -> resolved.addAll(blueprint.rootEntryNodeIds());
        case "@direct_targets" -> resolved.addAll(blueprint.directTargetNodeIds());
        case "@main_goal" -> resolved.add(blueprint.mainGoalNodeId());
        case "@all_intermediates" ->
            blueprint.nodes().stream()
                .filter(node -> node.kind() == ProofControlModels.BlueprintNodeKind.LEMMA)
                .map(StrategyBlueprintCompiler.Node::id)
                .forEach(resolved::add);
        default -> {
          if (!nodes.containsKey(reference)) {
            throw new IllegalArgumentException(
                "mechanism operation declaration references an unknown blueprint node: "
                    + reference);
          }
          resolved.add(reference);
        }
      }
    }
    if (resolved.isEmpty()) {
      throw new IllegalArgumentException("mechanism operation declaration resolves to no nodes");
    }
    return Set.copyOf(resolved);
  }

  private static void validateReachability(
      String operationId,
      Set<String> inputs,
      Set<String> outputs,
      Map<String, List<String>> outgoing) {
    for (String input : inputs) {
      if (outputs.stream().noneMatch(output -> pathExists(input, output, outgoing))) {
        throw new IllegalArgumentException(
            "mechanism operation input cannot reach an output: " + operationId);
      }
    }
    for (String output : outputs) {
      if (inputs.stream().noneMatch(input -> pathExists(input, output, outgoing))) {
        throw new IllegalArgumentException(
            "mechanism operation output is not reachable from an input: " + operationId);
      }
    }
  }

  private static boolean pathExists(
      String source, String target, Map<String, List<String>> outgoing) {
    java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(List.of(source));
    Set<String> visited = new java.util.HashSet<>();
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

  private static Map<String, List<String>> outgoing(
      StrategyBlueprintCompiler.Blueprint blueprint) {
    Map<String, List<String>> outgoing = new LinkedHashMap<>();
    blueprint.edges().forEach(
        edge ->
            outgoing.computeIfAbsent(edge.sourceId(), ignored -> new ArrayList<>())
                .add(edge.targetId()));
    return outgoing;
  }

  private static Map<String, String> topologyLabels(
      StrategyBlueprintCompiler.Blueprint blueprint) {
    Map<String, StrategyBlueprintCompiler.Node> nodes = new LinkedHashMap<>();
    blueprint.nodes().forEach(node -> nodes.put(node.id(), node));
    Map<String, List<StrategyBlueprintCompiler.Edge>> incoming = new HashMap<>();
    Map<String, List<StrategyBlueprintCompiler.Edge>> outgoing = new HashMap<>();
    blueprint.edges().forEach(
        edge -> {
          incoming.computeIfAbsent(edge.targetId(), ignored -> new ArrayList<>()).add(edge);
          outgoing.computeIfAbsent(edge.sourceId(), ignored -> new ArrayList<>()).add(edge);
        });
    Map<String, String> labels = new LinkedHashMap<>();
    for (Map.Entry<String, StrategyBlueprintCompiler.Node> entry : nodes.entrySet()) {
      String id = entry.getKey();
      labels.put(
          id,
          StrategySemanticNormalizer.hash(
              Map.of(
                  "role", boundedRole(entry.getValue()),
                  "root", blueprint.rootEntryNodeIds().contains(id),
                  "direct_target", blueprint.directTargetNodeIds().contains(id),
                  "main_goal", blueprint.mainGoalNodeId().equals(id))));
    }
    for (int round = 0; round < Math.max(1, nodes.size()); round++) {
      Map<String, String> currentLabels = labels;
      Map<String, String> refined = new LinkedHashMap<>();
      for (Map.Entry<String, StrategyBlueprintCompiler.Node> entry : nodes.entrySet()) {
        String id = entry.getKey();
        List<String> predecessors =
            incoming.getOrDefault(id, List.of()).stream()
                .map(
                    edge ->
                        normalizedRelation(edge.relation())
                            + ":"
                            + currentLabels.get(edge.sourceId()))
                .sorted()
                .toList();
        List<String> successors =
            outgoing.getOrDefault(id, List.of()).stream()
                .map(
                    edge ->
                        normalizedRelation(edge.relation())
                            + ":"
                            + currentLabels.get(edge.targetId()))
                .sorted()
                .toList();
        refined.put(
            id,
            StrategySemanticNormalizer.hash(
                Map.of(
                    "self", currentLabels.get(id),
                    "incoming", predecessors,
                    "outgoing", successors)));
      }
      labels = refined;
    }
    return Map.copyOf(labels);
  }

  private static String topologyHash(
      StrategyBlueprintCompiler.Blueprint blueprint, Map<String, String> finalLabels) {
    List<String> edges =
        blueprint.edges().stream()
            .map(
                edge ->
                    finalLabels.get(edge.sourceId())
                        + ">"
                        + normalizedRelation(edge.relation())
                        + ">"
                        + finalLabels.get(edge.targetId()))
            .sorted()
            .toList();
    return StrategySemanticNormalizer.hash(
        Map.of(
            "nodes", finalLabels.values().stream().sorted().toList(),
            "edges", edges,
            "roots",
                blueprint.rootEntryNodeIds().stream().map(finalLabels::get).sorted().toList(),
            "direct_targets",
                blueprint.directTargetNodeIds().stream()
                    .map(finalLabels::get)
                    .sorted()
                    .toList(),
            "main_goal", finalLabels.get(blueprint.mainGoalNodeId())));
  }

  private static String boundedRole(StrategyBlueprintCompiler.Node node) {
    return node.kind().name() + ':' + boundedSource(node.sourceField());
  }

  private static String boundedSource(String sourceField) {
    String value = sourceField == null ? "" : sourceField.strip();
    return switch (value) {
      case "given" -> "GIVEN";
      case "expected_lemma" -> "EXPECTED_LEMMA";
      case "critical_claim" -> "CRITICAL_CLAIM";
      case "main_goal" -> "MAIN_GOAL";
      default -> "SERVER_COMPILED";
    };
  }

  private static List<String> boundedRoles(
      StrategyBlueprintCompiler.Blueprint blueprint, List<String> ids) {
    Map<String, StrategyBlueprintCompiler.Node> nodes =
        blueprint.nodes().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    StrategyBlueprintCompiler.Node::id,
                    value -> value,
                    (left, right) -> left,
                    LinkedHashMap::new));
    return ids.stream()
        .map(nodes::get)
        .filter(java.util.Objects::nonNull)
        .map(StrategyMechanismStructureCompiler::boundedRole)
        .sorted()
        .toList();
  }

  private static String normalizedRelation(String relation) {
    String value = relation == null ? "" : relation.strip();
    return switch (value) {
      case "implies", "entails", "yields" -> "IMPLIES";
      case "depends_on", "requires", "uses" -> "DEPENDS_ON";
      default -> "STRUCTURAL_EDGE";
    };
  }

  private static boolean has(String text, String... markers) {
    for (String marker : markers) {
      if (text.contains(marker)) {
        return true;
      }
    }
    return false;
  }

  record Structure(
      Set<String> canonicalTargetIds,
      StructuredRepresentationKind representation,
      List<MechanismOperationNode> operations,
      String domainRoleHash,
      String topologyHash,
      String operationHash,
      String falsificationHash,
      boolean operationGraphKnown) {
    Structure {
      canonicalTargetIds = Set.copyOf(canonicalTargetIds);
      representation = java.util.Objects.requireNonNull(representation, "representation");
      operations = List.copyOf(operations);
    }

    @Override
    public Set<String> canonicalTargetIds() {
      return Set.copyOf(canonicalTargetIds);
    }

    @Override
    public List<MechanismOperationNode> operations() {
      return List.copyOf(operations);
    }
  }

  private record CompiledOperations(List<MechanismOperationNode> nodes, boolean known) {
    private CompiledOperations {
      nodes = List.copyOf(nodes);
    }
  }
}
