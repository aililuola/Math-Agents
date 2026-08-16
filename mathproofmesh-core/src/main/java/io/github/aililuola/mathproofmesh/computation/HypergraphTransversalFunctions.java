package io.github.aililuola.mathproofmesh.computation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Deterministic finite transversal checks with explicit exhaustive coverage. */
public final class HypergraphTransversalFunctions {
  private HypergraphTransversalFunctions() {}

  public static HandlerEvidence run(ExperimentSpec spec) {
    ObjectNode arguments = spec.arguments();
    String operation = requiredText(arguments, "operation");
    List<String> vertices = FiniteSetMapFunctions.values(arguments.get("vertices"), "vertices");
    if (vertices.size() != new LinkedHashSet<>(vertices).size()) {
      throw new IllegalArgumentException("vertices must not contain duplicates");
    }
    List<Set<String>> edges = edges(arguments.get("edges"), Set.copyOf(vertices));
    int casesChecked = 1;
    ObjectNode certificate = ComputationJson.object();
    certificate.put("operation", operation);
    certificate.set("vertices", strings(vertices));
    certificate.set("edges", sets(edges));

    switch (operation) {
      case "is_hitting_set" -> {
        Set<String> candidate = candidate(arguments, vertices);
        certificate.set("candidate", strings(sorted(candidate)));
        certificate.put("is_hitting_set", hits(candidate, edges));
      }
      case "is_minimal_hitting_set" -> {
        Set<String> candidate = candidate(arguments, vertices);
        certificate.set("candidate", strings(sorted(candidate)));
        certificate.put("is_hitting_set", hits(candidate, edges));
        certificate.put("is_minimal_hitting_set", minimal(candidate, edges));
      }
      case "enumerate_minimal_transversals" -> {
        Enumeration enumeration = enumerate(vertices, edges, spec.maxCases());
        List<Set<String>> minimal = enumeration.minimal();
        casesChecked = enumeration.casesChecked();
        certificate.set("minimal_transversals", sets(minimal));
        certificate.put("complete_finite_coverage", true);
        certificate.put("subsets_checked", casesChecked);
        certificate.put("coverage_digest", CanonicalJson.stableHash(sets(minimal)));
      }
      default ->
          throw new IllegalArgumentException("unsupported hypergraph operation: " + operation);
    }

    ObjectNode scope =
        ComputationJson.object()
            .put("complete_domain", true)
            .put("vertex_count", vertices.size())
            .put("edge_count", edges.size())
            .put("operation", operation);
    return new HandlerEvidence(
        ExperimentOutcome.CERTIFIED,
        EvidenceStrength.EXHAUSTIVE_CERTIFICATE,
        scope,
        null,
        certificate,
        true,
        casesChecked,
        false,
        List.of("The finite traversal coverage requires independent certificate verification."),
        null);
  }

  static boolean hits(Set<String> candidate, List<Set<String>> edges) {
    return edges.stream().allMatch(edge -> !java.util.Collections.disjoint(candidate, edge));
  }

  static boolean minimal(Set<String> candidate, List<Set<String>> edges) {
    if (!hits(candidate, edges)) {
      return false;
    }
    for (String value : candidate) {
      Set<String> reduced = new LinkedHashSet<>(candidate);
      reduced.remove(value);
      if (hits(reduced, edges)) {
        return false;
      }
    }
    return true;
  }

  static Enumeration enumerate(
      List<String> vertices, List<Set<String>> edges, int maxCases) {
    if (vertices.size() > 30) {
      throw new IllegalArgumentException("finite transversal enumeration supports at most 30 vertices");
    }
    long total = 1L << vertices.size();
    if (total > maxCases) {
      throw new IllegalArgumentException("hypergraph enumeration exceeds maxCases");
    }
    List<Set<String>> result = new ArrayList<>();
    for (long mask = 0; mask < total; mask++) {
      Set<String> candidate = new LinkedHashSet<>();
      for (int index = 0; index < vertices.size(); index++) {
        if ((mask & (1L << index)) != 0L) {
          candidate.add(vertices.get(index));
        }
      }
      if (minimal(candidate, edges)) {
        result.add(Set.copyOf(candidate));
      }
    }
    result.sort(
        Comparator.comparingInt((Set<String> value) -> value.size())
            .thenComparing(value -> String.join("\u0000", sorted(value))));
    return new Enumeration(List.copyOf(result), Math.toIntExact(total));
  }

  static List<Set<String>> edges(JsonNode raw, Set<String> vertices) {
    ArrayNode array = ComputationJson.requiredArray(raw, "edges");
    List<Set<String>> result = new ArrayList<>();
    for (JsonNode item : array) {
      List<String> values = FiniteSetMapFunctions.values(item, "edge");
      Set<String> edge = new LinkedHashSet<>(values);
      if (edge.isEmpty() || edge.size() != values.size() || !vertices.containsAll(edge)) {
        throw new IllegalArgumentException("each edge must be a nonempty set of declared vertices");
      }
      result.add(Set.copyOf(edge));
    }
    return List.copyOf(result);
  }

  private static Set<String> candidate(ObjectNode arguments, List<String> vertices) {
    Set<String> candidate =
        new LinkedHashSet<>(
            FiniteSetMapFunctions.values(arguments.get("candidate"), "candidate"));
    if (!Set.copyOf(vertices).containsAll(candidate)) {
      throw new IllegalArgumentException("candidate must contain declared vertices only");
    }
    return Set.copyOf(candidate);
  }

  private static ArrayNode sets(List<? extends Set<String>> values) {
    ArrayNode result = ComputationJson.array();
    values.forEach(value -> result.add(strings(sorted(value))));
    return result;
  }

  private static ArrayNode strings(List<String> values) {
    ArrayNode result = ComputationJson.array();
    values.forEach(result::add);
    return result;
  }

  private static List<String> sorted(Set<String> values) {
    return values.stream().sorted().toList();
  }

  private static String requiredText(ObjectNode value, String field) {
    String result = value.path(field).asText("").strip();
    if (result.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return result;
  }

  record Enumeration(List<Set<String>> minimal, int casesChecked) {
    Enumeration {
      minimal = minimal == null ? List.of() : List.copyOf(minimal);
      if (casesChecked < 0) {
        throw new IllegalArgumentException("casesChecked must be nonnegative");
      }
    }

    @Override
    public List<Set<String>> minimal() {
      return List.copyOf(minimal);
    }
  }
}
