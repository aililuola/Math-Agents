package io.github.aililuola.mathproofmesh.computation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exhaustive Java-native search over an explicitly bounded integer product domain. */
public final class IntegerSearchFunctions {
  private IntegerSearchFunctions() {}

  public static HandlerEvidence run(ExperimentSpec spec) {
    if (spec.domains().isEmpty()) {
      throw new IllegalArgumentException(
          "bounded_integer_search requires finite variable domains");
    }
    Map<String, Bounds> bounds = new LinkedHashMap<>();
    BigInteger totalCases = BigInteger.ONE;
    for (Map.Entry<String, JsonNode> entry :
        ComputationJson.sortedFields(spec.domains()).entrySet()) {
      ObjectNode domain =
          ComputationJson.requiredObject(
              entry.getValue(), "domain for '" + entry.getKey() + "'");
      if (!domain.has("min") || !domain.has("max")) {
        throw new IllegalArgumentException(
            "domain for '" + entry.getKey() + "' requires integer min and max");
      }
      BigInteger lower =
          ComputationJson.integer(
              domain.get("min"), "domain minimum for '" + entry.getKey() + "'");
      BigInteger upper =
          ComputationJson.integer(
              domain.get("max"), "domain maximum for '" + entry.getKey() + "'");
      if (upper.compareTo(lower) < 0) {
        throw new IllegalArgumentException(
            "domain for '" + entry.getKey() + "' has max < min");
      }
      bounds.put(entry.getKey(), new Bounds(lower, upper));
      totalCases = totalCases.multiply(upper.subtract(lower).add(BigInteger.ONE));
    }
    if (totalCases.compareTo(BigInteger.valueOf(spec.maxCases())) > 0) {
      throw new IllegalArgumentException(
          "bounded domain has "
              + totalCases
              + " cases, exceeding max_cases="
              + spec.maxCases());
    }

    ObjectNode arguments = spec.arguments();
    Relation target =
        Relation.parse(
            ComputationJson.requiredObject(arguments.get("target"), "arguments.target"));
    target.requireVariables(bounds.keySet());
    List<Relation> constraints = new ArrayList<>();
    JsonNode rawConstraints = arguments.get("constraints");
    if (rawConstraints != null) {
      ArrayNode array = ComputationJson.requiredArray(rawConstraints, "constraints");
      for (JsonNode constraint : array) {
        Relation parsed =
            Relation.parse(
                ComputationJson.requiredObject(
                    constraint, "each constraint"));
        parsed.requireVariables(bounds.keySet());
        constraints.add(parsed);
      }
    }

    Search search =
        new Search(List.copyOf(bounds.keySet()), bounds, constraints, target);
    search.visit(0, new LinkedHashMap<>());
    if (search.counterexample != null) {
      ObjectNode scope = ComputationJson.object();
      scope.set("domains", spec.domains());
      scope.set(
          "constraints",
          rawConstraints == null ? ComputationJson.array() : rawConstraints.deepCopy());
      return new HandlerEvidence(
          ExperimentOutcome.COUNTEREXAMPLE_FOUND,
          EvidenceStrength.COUNTEREXAMPLE,
          scope,
          search.counterexample,
          null,
          true,
          1,
          true,
          List.of(
              "The lexicographically first violating tuple was re-evaluated by the exact integer evaluator."),
          null);
    }
    ObjectNode certificate = ComputationJson.object();
    certificate.put("solver_status", "exhaustive_no_counterexample");
    certificate.set("domains", spec.domains());
    ObjectNode scope = ComputationJson.object();
    scope.set("domains", spec.domains());
    scope.put("total_domain_cases", totalCases);
    return new HandlerEvidence(
        ExperimentOutcome.NOT_REFUTED,
        EvidenceStrength.BOUNDED_EVIDENCE,
        scope,
        null,
        certificate,
        true,
        totalCases.intValueExact(),
        false,
        List.of(
            "No violating assignment exists in the declared finite domain; this does not prove claims outside that domain."),
        null);
  }

  private record Bounds(BigInteger lower, BigInteger upper) {}

  private record Relation(ExactExpression lhs, ExactExpression rhs, String operator) {
    private static Relation parse(ObjectNode raw) {
      ExactExpression lhs =
          ExactExpression.parse(ComputationJson.requiredText(raw.get("lhs"), "relation lhs"), 12);
      ExactExpression rhs =
          ExactExpression.parse(raw.has("rhs") ? raw.get("rhs").asText() : "0", 12);
      String operator = raw.has("relation") ? raw.get("relation").asText() : "eq";
      if (!List.of("eq", "ne", "le", "lt", "ge", "gt").contains(operator)) {
        throw new IllegalArgumentException("unsupported relation: " + operator);
      }
      return new Relation(lhs, rhs, operator);
    }

    private void requireVariables(java.util.Set<String> declared) {
      java.util.Set<String> used = new java.util.HashSet<>(lhs.variables());
      used.addAll(rhs.variables());
      if (!declared.containsAll(used)) {
        used.removeAll(declared);
        throw new IllegalArgumentException("undeclared integer variable: " + used.iterator().next());
      }
    }

    private Values evaluate(Map<String, BigInteger> assignment) {
      BigInteger left = lhs.evaluateInteger(assignment);
      BigInteger right = rhs.evaluateInteger(assignment);
      int comparison = left.compareTo(right);
      boolean holds =
          switch (operator) {
            case "eq" -> comparison == 0;
            case "ne" -> comparison != 0;
            case "le" -> comparison <= 0;
            case "lt" -> comparison < 0;
            case "ge" -> comparison >= 0;
            case "gt" -> comparison > 0;
            default -> throw new IllegalStateException("unknown relation: " + operator);
          };
      return new Values(left, right, holds);
    }
  }

  private record Values(BigInteger left, BigInteger right, boolean holds) {}

  private static final class Search {
    private final List<String> variables;
    private final Map<String, Bounds> bounds;
    private final List<Relation> constraints;
    private final Relation target;
    private ObjectNode counterexample;

    private Search(
        List<String> variables,
        Map<String, Bounds> bounds,
        List<Relation> constraints,
        Relation target) {
      this.variables = variables;
      this.bounds = bounds;
      this.constraints = constraints;
      this.target = target;
    }

    private void visit(int index, Map<String, BigInteger> assignment) {
      if (counterexample != null) {
        return;
      }
      if (index < variables.size()) {
        String variable = variables.get(index);
        Bounds domain = bounds.get(variable);
        for (BigInteger value = domain.lower;
            value.compareTo(domain.upper) <= 0;
            value = value.add(BigInteger.ONE)) {
          assignment.put(variable, value);
          visit(index + 1, assignment);
          if (counterexample != null) {
            return;
          }
        }
        assignment.remove(variable);
        return;
      }
      if (constraints.stream().anyMatch(relation -> !relation.evaluate(assignment).holds)) {
        return;
      }
      Values values = target.evaluate(assignment);
      if (!values.holds) {
        Map<String, BigInteger> replay = Map.copyOf(assignment);
        boolean constraintsHold =
            constraints.stream().allMatch(relation -> relation.evaluate(replay).holds);
        Values replayed = target.evaluate(replay);
        if (constraintsHold && !replayed.holds) {
          ObjectNode payload = ComputationJson.object();
          ObjectNode assignmentNode = payload.putObject("assignment");
          assignment.forEach(assignmentNode::put);
          payload.put("lhs_value", values.left);
          payload.put("rhs_value", values.right);
          payload.put("relation", target.operator);
          counterexample = payload;
        }
      }
    }
  }
}
