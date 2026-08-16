package io.github.aililuola.mathproofmesh.computation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Direct graph-certificate validation with no heuristic graph inference. */
public final class GraphFunctions {
  private static final Set<String> EXISTENCE_PROPERTIES =
      Set.of("proper_coloring", "path", "cycle", "matching");

  private GraphFunctions() {}

  public static HandlerEvidence run(ExperimentSpec spec) {
    ObjectNode arguments = spec.arguments();
    ObjectNode graph =
        ComputationJson.requiredObject(arguments.get("graph"), "graph");
    ObjectNode certificate =
        ComputationJson.requiredObject(arguments.get("certificate"), "certificate");
    String property =
        arguments.has("property") ? arguments.get("property").asText() : "";
    List<String> nodes = scalarStrings(graph.get("nodes"), "graph.nodes");
    if (nodes.size() != new HashSet<>(nodes).size()) {
      throw new IllegalArgumentException("graph node identifiers must be unique");
    }
    boolean directed = graph.path("directed").asBoolean(false);
    List<Edge> edges = edges(graph.get("edges"), "graph.edges");
    Set<String> nodeSet = Set.copyOf(nodes);
    if (edges.stream().anyMatch(edge -> !nodeSet.contains(edge.left) || !nodeSet.contains(edge.right))) {
      throw new IllegalArgumentException("all edge endpoints must be declared nodes");
    }
    Set<Edge> edgeSet = new HashSet<>();
    for (Edge edge : edges) {
      edgeSet.add(edge.normalized(directed));
    }

    Verification verification =
        switch (property) {
          case "proper_coloring" -> verifyColoring(nodes, edges, certificate);
          case "path" -> verifyWalk(nodes, edgeSet, certificate, directed, false);
          case "cycle" -> verifyWalk(nodes, edgeSet, certificate, directed, true);
          case "matching" -> verifyMatching(edgeSet, certificate, directed);
          case "connected" -> verifyConnected(nodes, edges, directed);
          default ->
              throw new IllegalArgumentException(
                  "unsupported graph certificate property: " + property);
        };

    ObjectNode scope =
        ComputationJson.object()
            .put("complete_domain", true)
            .put("node_count", nodes.size())
            .put("edge_count", edges.size())
            .put("directed", directed);
    if (!verification.valid) {
      if (EXISTENCE_PROPERTIES.contains(property)) {
        ObjectNode raw = ComputationJson.object();
        raw.put("property", property);
        raw.set("supplied_certificate", certificate);
        raw.set("details", verification.details);
        return new HandlerEvidence(
            ExperimentOutcome.INCONCLUSIVE,
            EvidenceStrength.HEURISTIC,
            scope,
            null,
            null,
            true,
            1,
            false,
            List.of(
                "The supplied certificate is invalid; this does not refute the existence claim.",
                "The independent property-specific checker rejected the supplied certificate."),
            raw);
      }
      ObjectNode counterexample = ComputationJson.object();
      counterexample.put("property", property);
      counterexample.set("supplied_certificate", certificate);
      counterexample.set("details", verification.details);
      return new HandlerEvidence(
          ExperimentOutcome.COUNTEREXAMPLE_FOUND,
          EvidenceStrength.COUNTEREXAMPLE,
          scope,
          counterexample,
          null,
          true,
          1,
          true,
          List.of("An independent direct traversal rejected the declared graph property."),
          null);
    }
    ObjectNode accepted = ComputationJson.object().put("property", property);
    accepted.setAll(verification.details);
    return new HandlerEvidence(
        ExperimentOutcome.CERTIFIED,
        EvidenceStrength.EXHAUSTIVE_CERTIFICATE,
        scope,
        null,
        accepted,
        true,
        1,
        true,
        List.of("The certificate passed an independent property-specific checker."),
        null);
  }

  private static Verification verifyColoring(
      List<String> nodes, List<Edge> edges, ObjectNode certificate) {
    JsonNode rawColors = certificate.get("colors");
    if (rawColors == null || !rawColors.isObject()) {
      return new Verification(false, ComputationJson.object().put("color_count", 0));
    }
    ObjectNode colors = (ObjectNode) rawColors;
    Set<String> names = new HashSet<>();
    colors.properties().forEach(entry -> names.add(entry.getKey()));
    boolean valid = names.equals(Set.copyOf(nodes));
    if (valid) {
      for (Edge edge : edges) {
        if (colors.get(edge.left).equals(colors.get(edge.right))) {
          valid = false;
          break;
        }
      }
    }
    Set<JsonNode> distinct = new HashSet<>();
    colors.properties().forEach(entry -> distinct.add(entry.getValue()));
    ObjectNode details = ComputationJson.object();
    details.set("colors", colors);
    details.put("color_count", distinct.size());
    return new Verification(valid, details);
  }

  private static Verification verifyWalk(
      List<String> nodes,
      Set<Edge> edgeSet,
      ObjectNode certificate,
      boolean directed,
      boolean cycle) {
    List<String> vertices = scalarStrings(certificate.get("vertices"), "certificate.vertices");
    List<Edge> pairs = new ArrayList<>();
    for (int index = 1; index < vertices.size(); index++) {
      pairs.add(new Edge(vertices.get(index - 1), vertices.get(index)));
    }
    if (cycle && !vertices.isEmpty()) {
      pairs.add(new Edge(vertices.getLast(), vertices.getFirst()));
    }
    int minimum = cycle ? (directed ? 2 : 3) : 1;
    boolean valid =
        vertices.size() >= minimum
            && vertices.size() == new HashSet<>(vertices).size()
            && Set.copyOf(nodes).containsAll(vertices);
    if (valid) {
      valid = pairs.stream().allMatch(edge -> edgeSet.contains(edge.normalized(directed)));
    }
    ObjectNode details = ComputationJson.object().put("length", pairs.size());
    ArrayNode sequence = details.putArray("vertices");
    vertices.forEach(sequence::add);
    return new Verification(valid, details);
  }

  private static Verification verifyMatching(
      Set<Edge> edgeSet, ObjectNode certificate, boolean directed) {
    if (directed) {
      throw new IllegalArgumentException(
          "matching certificates currently require an undirected graph");
    }
    List<Edge> matching = edges(certificate.get("edges"), "certificate.edges");
    Set<String> used = new HashSet<>();
    boolean valid = true;
    for (Edge edge : matching) {
      if (!edgeSet.contains(edge.normalized(false))
          || !used.add(edge.left)
          || !used.add(edge.right)) {
        valid = false;
        break;
      }
    }
    ObjectNode details = ComputationJson.object().put("size", matching.size());
    ArrayNode array = details.putArray("edges");
    matching.forEach(
        edge -> {
          ArrayNode pair = array.addArray();
          pair.add(edge.left);
          pair.add(edge.right);
        });
    return new Verification(valid, details);
  }

  private static Verification verifyConnected(
      List<String> nodes, List<Edge> edges, boolean directed) {
    boolean valid = !nodes.isEmpty();
    if (valid) {
      Map<String, Set<String>> forward = adjacency(nodes, edges, directed, false);
      valid = traverse(nodes.getFirst(), forward).size() == nodes.size();
      if (directed && valid) {
        Map<String, Set<String>> reverse = adjacency(nodes, edges, true, true);
        valid = traverse(nodes.getFirst(), reverse).size() == nodes.size();
      }
    }
    return new Verification(
        valid,
        ComputationJson.object()
            .put("node_count", nodes.size())
            .put("edge_count", edges.size()));
  }

  private static Map<String, Set<String>> adjacency(
      List<String> nodes, List<Edge> edges, boolean directed, boolean reverse) {
    Map<String, Set<String>> adjacency = new HashMap<>();
    nodes.forEach(node -> adjacency.put(node, new LinkedHashSet<>()));
    for (Edge edge : edges) {
      String left = reverse ? edge.right : edge.left;
      String right = reverse ? edge.left : edge.right;
      adjacency.get(left).add(right);
      if (!directed) {
        adjacency.get(right).add(left);
      }
    }
    return adjacency;
  }

  private static Set<String> traverse(String start, Map<String, Set<String>> adjacency) {
    Set<String> seen = new HashSet<>();
    ArrayDeque<String> queue = new ArrayDeque<>();
    seen.add(start);
    queue.add(start);
    while (!queue.isEmpty()) {
      String current = queue.removeFirst();
      for (String neighbor : adjacency.get(current)) {
        if (seen.add(neighbor)) {
          queue.addLast(neighbor);
        }
      }
    }
    return seen;
  }

  private static List<String> scalarStrings(JsonNode raw, String label) {
    ArrayNode array = ComputationJson.requiredArray(raw, label);
    List<String> values = new ArrayList<>(array.size());
    for (JsonNode item : array) {
      if (!item.isValueNode() || item.isContainerNode()) {
        throw new IllegalArgumentException(label + " entries must be scalar identifiers");
      }
      values.add(item.asText());
    }
    return List.copyOf(values);
  }

  private static List<Edge> edges(JsonNode raw, String label) {
    ArrayNode array = ComputationJson.requiredArray(raw, label);
    List<Edge> result = new ArrayList<>(array.size());
    for (JsonNode item : array) {
      if (!item.isArray() || item.size() != 2) {
        throw new IllegalArgumentException(label + " entries must contain two endpoints");
      }
      result.add(new Edge(item.get(0).asText(), item.get(1).asText()));
    }
    return List.copyOf(result);
  }

  private record Verification(boolean valid, ObjectNode details) {}

  private record Edge(String left, String right) {
    private Edge normalized(boolean directed) {
      return directed || left.compareTo(right) <= 0 ? this : new Edge(right, left);
    }
  }
}
