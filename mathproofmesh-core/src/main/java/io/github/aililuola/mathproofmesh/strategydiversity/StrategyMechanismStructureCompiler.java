package io.github.aililuola.mathproofmesh.strategydiversity;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.ToolRequest;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels;
import io.github.aililuola.mathproofmesh.proofcontrol.StrategyBlueprintCompiler;
import java.util.ArrayList;
import java.util.EnumSet;
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
    List<MechanismOperationNode> operations = operations(strategy, blueprint);
    String topologyHash = topologyHash(blueprint);
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
        falsificationHash);
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

  private static List<MechanismOperationNode> operations(
      StrategyCard strategy, StrategyBlueprintCompiler.Blueprint blueprint) {
    Map<String, StrategyBlueprintCompiler.Node> nodes = new LinkedHashMap<>();
    blueprint.nodes().forEach(node -> nodes.put(node.id(), node));
    Map<String, Long> incomingCounts =
        blueprint.edges().stream()
            .collect(
                java.util.stream.Collectors.groupingBy(
                    StrategyBlueprintCompiler.Edge::targetId,
                    LinkedHashMap::new,
                    java.util.stream.Collectors.counting()));
    List<MechanismOperationNode> operations = new ArrayList<>();
    int index = 0;
    for (StrategyBlueprintCompiler.Edge edge : blueprint.edges()) {
      StrategyBlueprintCompiler.Node source = nodes.get(edge.sourceId());
      StrategyBlueprintCompiler.Node target = nodes.get(edge.targetId());
      ProofOperationKind kind =
          operationKind(edge, source, target, incomingCounts.getOrDefault(edge.targetId(), 0L));
      operations.add(
          new MechanismOperationNode(
              "operation:" + index++,
              kind,
              Set.of(source == null ? "UNKNOWN" : boundedRole(source)),
              Set.of(target == null ? "UNKNOWN" : boundedRole(target))));
    }
    Set<String> rootRoles = Set.copyOf(boundedRoles(blueprint, blueprint.rootEntryNodeIds()));
    Set<String> targetRoles =
        Set.copyOf(boundedRoles(blueprint, blueprint.directTargetNodeIds()));
    for (ProofOperationKind kind : classifyOperations(strategy.coreIdea())) {
      operations.add(
          new MechanismOperationNode(
              "classified-operation:" + kind.name(), kind, rootRoles, targetRoles));
    }
    return List.copyOf(operations);
  }

  private static Set<ProofOperationKind> classifyOperations(String mechanism) {
    String text = mechanism == null ? "" : mechanism.toLowerCase(Locale.ROOT);
    EnumSet<ProofOperationKind> kinds = EnumSet.noneOf(ProofOperationKind.class);
    add(
        text,
        kinds,
        ProofOperationKind.INDUCTION,
        "induction",
        "induct",
        "inductive hypothesis",
        "recursively",
        "recursive step",
        "lift the result");
    add(text, kinds, ProofOperationKind.CONTRADICTION, "contradiction", "assume the negation");
    add(
        text,
        kinds,
        ProofOperationKind.MINIMAL_COUNTEREXAMPLE,
        "minimal counterexample",
        "smallest counterexample");
    add(
        text,
        kinds,
        ProofOperationKind.EXTREMAL_SELECTION,
        "longest",
        "shortest",
        "maximal",
        "extremal");
    add(
        text,
        kinds,
        ProofOperationKind.DECOMPOSITION,
        "decompose",
        "decomposition",
        "partition",
        "split into");
    add(text, kinds, ProofOperationKind.CONSTRUCTION, "construct", "explicit witness");
    add(
        text,
        kinds,
        ProofOperationKind.DUALIZATION,
        "duality",
        "dual representation",
        "pass to the dual");
    add(
        text,
        kinds,
        ProofOperationKind.REDUCTION,
        "reduce",
        "reduction",
        "delete a leaf",
        "remove a leaf",
        "remove a pendant",
        "prune a leaf",
        "prune a pendant",
        "strip one terminal",
        "take away an endpoint",
        "one-vertex-shorter",
        "smaller-order");
    add(
        text,
        kinds,
        ProofOperationKind.ALGEBRAIC_TRANSFORMATION,
        "algebraic",
        "factor",
        "linear transformation",
        "extend a basis");
    add(
        text,
        kinds,
        ProofOperationKind.COUNTING,
        "counting",
        "count vertices",
        "degree sum",
        "pigeonhole",
        "cardinality");
    add(text, kinds, ProofOperationKind.SPECTRAL_ARGUMENT, "spectral", "eigenvalue");
    add(
        text,
        kinds,
        ProofOperationKind.PROBABILISTIC_ARGUMENT,
        "probabilistic",
        "expectation",
        "random");
    if (kinds.isEmpty()) {
      kinds.add(ProofOperationKind.DIRECT);
    }
    return Set.copyOf(kinds);
  }

  private static ProofOperationKind operationKind(
      StrategyBlueprintCompiler.Edge edge,
      StrategyBlueprintCompiler.Node source,
      StrategyBlueprintCompiler.Node target,
      long targetInDegree) {
    if ((source != null && source.kind() == ProofControlModels.BlueprintNodeKind.CONSTRUCTION)
        || (target != null && target.kind() == ProofControlModels.BlueprintNodeKind.CONSTRUCTION)) {
      return ProofOperationKind.CONSTRUCTION;
    }
    if (targetInDegree > 1L) {
      return ProofOperationKind.DECOMPOSITION;
    }
    return "DEPENDS_ON".equals(normalizedRelation(edge.relation()))
        ? ProofOperationKind.REDUCTION
        : ProofOperationKind.DIRECT;
  }

  private static String topologyHash(StrategyBlueprintCompiler.Blueprint blueprint) {
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
    Map<String, String> finalLabels = labels;
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

  private static void add(
      String text, Set<ProofOperationKind> destination, ProofOperationKind kind, String... markers) {
    for (String marker : markers) {
      if (text.contains(marker)) {
        destination.add(kind);
        return;
      }
    }
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
      String falsificationHash) {
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
}
