package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Compiles an auditable strategy DAG before route admission. */
public final class StrategyBlueprintCompiler {
  public record Node(
      String id,
      ProofControlModels.BlueprintNodeKind kind,
      String statement,
      String sourceField,
      String executableFirstStep,
      double semanticQuality) {
    public Node {
      ProofControlModels.required(id, "id");
      java.util.Objects.requireNonNull(kind, "kind");
      ProofControlModels.required(statement, "statement");
      ProofControlModels.required(sourceField, "sourceField");
      executableFirstStep = executableFirstStep == null ? "" : executableFirstStep.strip();
      ProofControlModels.unit(semanticQuality, "semanticQuality");
    }
  }

  public record Edge(
      String id,
      String sourceId,
      String targetId,
      String relation,
      List<String> implicationOutline,
      boolean verified,
      String origin) {
    public Edge {
      ProofControlModels.required(id, "id");
      ProofControlModels.required(sourceId, "sourceId");
      ProofControlModels.required(targetId, "targetId");
      ProofControlModels.required(relation, "relation");
      implicationOutline = List.copyOf(implicationOutline);
      ProofControlModels.required(origin, "origin");
    }

    @Override
    public List<String> implicationOutline() {
      return List.copyOf(implicationOutline);
    }
  }

  public record Blueprint(
      String id,
      String strategyId,
      String problemHash,
      List<Node> nodes,
      List<Edge> edges,
      String mainGoalNodeId,
      List<String> directTargetNodeIds,
      List<String> rootEntryNodeIds,
      List<String> openGapNodeIds,
      boolean preservesMechanism,
      boolean completePathToMainGoal,
      double confidence,
      String status) {
    public Blueprint {
      nodes = List.copyOf(nodes);
      edges = List.copyOf(edges);
      directTargetNodeIds = List.copyOf(directTargetNodeIds);
      rootEntryNodeIds = List.copyOf(rootEntryNodeIds);
      openGapNodeIds = List.copyOf(openGapNodeIds);
    }

    @Override
    public List<Node> nodes() {
      return List.copyOf(nodes);
    }

    @Override
    public List<Edge> edges() {
      return List.copyOf(edges);
    }

    @Override
    public List<String> directTargetNodeIds() {
      return List.copyOf(directTargetNodeIds);
    }

    @Override
    public List<String> rootEntryNodeIds() {
      return List.copyOf(rootEntryNodeIds);
    }

    @Override
    public List<String> openGapNodeIds() {
      return List.copyOf(openGapNodeIds);
    }
  }

  public record Compilation(
      Blueprint blueprint, List<String> reviewReasons, List<String> obligationProposals) {
    public Compilation {
      reviewReasons = List.copyOf(reviewReasons);
      obligationProposals = List.copyOf(obligationProposals);
    }
  }

  public record Assessment(boolean accepted, List<String> reasons) {
    public Assessment {
      reasons = List.copyOf(reasons);
    }
  }

  private final SemanticQualityGate semanticGate = new SemanticQualityGate();

  public Compilation compile(
      String problemHash,
      ProofControlModels.Strategy strategy,
      ProofControlModels.Obligation mainGoal) {
    ProofControlModels.required(problemHash, "problemHash");
    java.util.Objects.requireNonNull(strategy, "strategy");
    java.util.Objects.requireNonNull(mainGoal, "mainGoal");
    List<Node> nodes = new ArrayList<>();
    List<Edge> edges = new ArrayList<>();
    List<String> roots = new ArrayList<>();
    List<String> gaps = new ArrayList<>();

    for (String prerequisite : strategy.prerequisites()) {
      Node node = node(strategy.id(), "given", prerequisite,
          ProofControlModels.BlueprintNodeKind.GIVEN, "given", "");
      nodes.add(node);
      roots.add(node.id());
    }
    List<String> intermediate =
        !strategy.expectedLemmas().isEmpty()
            ? strategy.expectedLemmas()
            : !strategy.criticalClaims().isEmpty()
                ? strategy.criticalClaims()
                : List.of(
                    "Establish the mechanism-specific bridge for "
                        + strategy.mechanism()
                        + ".");
    List<Node> intermediates = new ArrayList<>();
    for (String statement : intermediate) {
      Node node =
          node(
              strategy.id(),
              "lemma",
              statement,
              ProofControlModels.BlueprintNodeKind.LEMMA,
              "expected_lemma",
              "test the first load-bearing implication");
      nodes.add(node);
      intermediates.add(node);
      gaps.add(node.id());
    }
    Node target =
        node(
            strategy.id(),
            "target",
            mainGoal.statement(),
            ProofControlModels.BlueprintNodeKind.TARGET,
            "main_goal",
            "");
    nodes.add(target);
    if (roots.isEmpty()) {
      roots.add(intermediates.getFirst().id());
    }

    for (String root : roots) {
      edges.add(edge(strategy.id(), root, intermediates.getFirst().id(), "depends_on"));
    }
    for (int index = 0; index + 1 < intermediates.size(); index++) {
      edges.add(
          edge(
              strategy.id(),
              intermediates.get(index).id(),
              intermediates.get(index + 1).id(),
              "implies"));
    }
    edges.add(
        edge(
            strategy.id(),
            intermediates.getLast().id(),
            target.id(),
            "implies"));

    String blueprintId =
        "blueprint_"
            + CanonicalJson.stableHash(
                    Map.of(
                        "problem_hash", problemHash,
                        "strategy_id", strategy.id(),
                        "nodes", nodes.stream().map(Node::statement).toList(),
                        "edges",
                            edges.stream()
                                .map(value -> value.sourceId() + ">" + value.targetId())
                                .toList()))
                .substring(0, 20);
    boolean mechanismPreserved =
        !strategy.mechanism().isBlank()
            && nodes.stream()
                .map(Node::statement)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(
                    value ->
                        strategy.mechanism().toLowerCase(Locale.ROOT).lines()
                            .anyMatch(token -> !token.isBlank() && value.contains(token.strip()))
                            || !strategy.domainObjects().isEmpty());
    boolean complete = pathExists(nodes, edges, roots, target.id());
    double confidence =
        Math.min(
            1.0d,
            nodes.stream().mapToDouble(Node::semanticQuality).average().orElse(0.0d));
    Blueprint blueprint =
        new Blueprint(
            blueprintId,
            strategy.id(),
            problemHash,
            nodes,
            edges,
            target.id(),
            List.of(intermediates.getLast().id()),
            roots,
            gaps,
            mechanismPreserved,
            complete,
            confidence,
            complete && mechanismPreserved ? "compiled" : "needs_review");
    Assessment assessment = validate(blueprint, strategy);
    Blueprint finalized =
        new Blueprint(
            blueprint.id(),
            blueprint.strategyId(),
            blueprint.problemHash(),
            blueprint.nodes(),
            blueprint.edges(),
            blueprint.mainGoalNodeId(),
            blueprint.directTargetNodeIds(),
            blueprint.rootEntryNodeIds(),
            blueprint.openGapNodeIds(),
            blueprint.preservesMechanism(),
            blueprint.completePathToMainGoal(),
            blueprint.confidence(),
            assessment.accepted() ? "accepted" : "rejected");
    List<String> proposals =
        assessment.accepted()
            ? intermediates.stream().map(Node::id).toList()
            : List.of();
    return new Compilation(finalized, assessment.reasons(), proposals);
  }

  public Assessment validate(Blueprint blueprint, ProofControlModels.Strategy strategy) {
    List<String> reasons = new ArrayList<>();
    if (!blueprint.preservesMechanism()) {
      reasons.add("strategy mechanism or domain objects were lost");
    }
    if (!blueprint.completePathToMainGoal()) {
      reasons.add("no complete path reaches the main goal");
    }
    if (blueprint.nodes().stream()
        .filter(value -> value.kind() != ProofControlModels.BlueprintNodeKind.TARGET)
        .allMatch(value -> semanticGate.assessStatement(value.statement()).placeholder())) {
      reasons.add("blueprint contains only placeholders");
    }
    if (blueprint.nodes().stream()
        .noneMatch(
            value ->
                value.kind() == ProofControlModels.BlueprintNodeKind.LEMMA
                    || value.kind() == ProofControlModels.BlueprintNodeKind.CONSTRUCTION)) {
      reasons.add("blueprint has no non-main intermediate target");
    }
    return new Assessment(reasons.isEmpty(), reasons);
  }

  private Node node(
      String strategyId,
      String suffix,
      String statement,
      ProofControlModels.BlueprintNodeKind kind,
      String source,
      String executable) {
    SemanticQualityGate.Assessment quality = semanticGate.assessStatement(statement);
    String id =
        "blueprint_node_"
            + CanonicalJson.stableHash(
                    Map.of(
                        "strategy", strategyId,
                        "suffix", suffix,
                        "statement", ProofIdentity.obligationIdentityText(statement)))
                .substring(0, 20);
    return new Node(
        id, kind, statement, source, executable, quality.score());
  }

  private static Edge edge(
      String strategyId, String source, String target, String relation) {
    String id =
        "blueprint_edge_"
            + CanonicalJson.stableHash(
                    Map.of(
                        "strategy", strategyId,
                        "source", source,
                        "target", target,
                        "relation", relation))
                .substring(0, 20);
    return new Edge(
        id,
        source,
        target,
        relation,
        List.of("explicit ordered strategy implication"),
        false,
        "explicit_strategy_outline");
  }

  private static boolean pathExists(
      List<Node> nodes, List<Edge> edges, List<String> roots, String target) {
    Set<String> nodeIds =
        nodes.stream().map(Node::id).collect(java.util.stream.Collectors.toSet());
    Map<String, List<String>> outgoing = new HashMap<>();
    for (Edge edge : edges) {
      outgoing.computeIfAbsent(edge.sourceId(), ignored -> new ArrayList<>())
          .add(edge.targetId());
    }
    ArrayDeque<String> queue = new ArrayDeque<>(roots);
    Set<String> visited = new LinkedHashSet<>();
    while (!queue.isEmpty()) {
      String current = queue.removeFirst();
      if (!nodeIds.contains(current) || !visited.add(current)) {
        continue;
      }
      if (current.equals(target)) {
        return true;
      }
      queue.addAll(outgoing.getOrDefault(current, List.of()));
    }
    return false;
  }
}
