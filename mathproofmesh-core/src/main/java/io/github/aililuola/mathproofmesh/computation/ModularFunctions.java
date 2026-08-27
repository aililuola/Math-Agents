package io.github.aililuola.mathproofmesh.computation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Deterministic exhaustive residue-class handler. */
public final class ModularFunctions {
  private ModularFunctions() {}

  public static HandlerEvidence run(ExperimentSpec spec) {
    ObjectNode arguments = spec.arguments();
    int modulus =
        ComputationJson.boundedInt(
            arguments.get("modulus"), "modulus", 2, Math.max(2, spec.maxCases()));
    ExactExpression lhs =
        ExactExpression.parse(ComputationJson.requiredText(arguments.get("lhs"), "lhs"), 10_000);
    ExactExpression rhs =
        ExactExpression.parse(
            arguments.has("rhs") ? arguments.get("rhs").asText() : "0", 10_000);
    String relation = arguments.has("relation") ? arguments.get("relation").asText() : "eq";
    if (!relation.equals("eq") && !relation.equals("ne")) {
      throw new IllegalArgumentException("modular_exhaustive supports only eq and ne relations");
    }

    List<String> variables = variables(arguments, lhs, rhs);
    Map<String, List<BigInteger>> domains = new LinkedHashMap<>();
    long totalCases = 1;
    for (String variable : variables) {
      List<BigInteger> values = domainValues(spec, variable, modulus);
      domains.put(variable, values);
      totalCases = Math.multiplyExact(totalCases, values.size());
      if (totalCases > spec.maxCases()) {
        throw new IllegalArgumentException(
            "requested "
                + totalCases
                + " residue assignments exceeds max_cases="
                + spec.maxCases());
      }
    }

    Search search =
        new Search(lhs, rhs, relation, BigInteger.valueOf(modulus), variables, domains);
    search.visit(0, new LinkedHashMap<>());
    if (search.counterexample != null) {
      ObjectNode counterexampleScope = ComputationJson.object();
      counterexampleScope.set("domains", spec.domains());
      counterexampleScope.put("modulus", modulus);
      return new HandlerEvidence(
          ExperimentOutcome.COUNTEREXAMPLE_FOUND,
          EvidenceStrength.COUNTEREXAMPLE,
          counterexampleScope,
          search.counterexample,
          null,
          true,
          search.checked,
          true,
          List.of("The violating residue assignment was re-evaluated exactly."),
          null);
    }

    boolean finiteReduction = arguments.path("finite_reduction").asBoolean(false);
    String reduction = arguments.path("reduction_justification").asText("").trim();
    boolean fullCoverage = domains.values().stream().allMatch(values -> values.size() == modulus);
    if (finiteReduction && !reduction.isBlank() && fullCoverage) {
      ObjectNode certificate = ComputationJson.object();
      certificate.put("modulus", modulus);
      ArrayNode variableArray = certificate.putArray("variables");
      variables.forEach(variableArray::add);
      ObjectNode certificateDomains = certificate.putObject("domains");
      for (String variable : variables) {
        certificateDomains
            .putObject(variable)
            .put("residues", "0.." + (modulus - 1))
            .put("residue_count", modulus);
      }
      certificate.put("finite_reduction_justification", reduction);
      certificate.put("all_cases_satisfied", true);
      return new HandlerEvidence(
          ExperimentOutcome.CERTIFIED,
          EvidenceStrength.EXHAUSTIVE_CERTIFICATE,
          ComputationJson.object()
              .put("finite_reduction_claimed", true)
              .put("full_residue_coverage", true)
              .put("complete_domain", true),
          null,
          certificate,
          true,
          search.checked,
          false,
          List.of(
              "All declared residue classes were checked; a reviewer must still verify the stated finite reduction."),
          null);
    }
    ObjectNode scope = ComputationJson.object();
    scope.set("domains", spec.domains());
    scope.put("modulus", modulus);
    scope.put("full_residue_coverage", fullCoverage);
    return new HandlerEvidence(
        ExperimentOutcome.NOT_REFUTED,
        EvidenceStrength.BOUNDED_EVIDENCE,
        scope,
        null,
        null,
        true,
        search.checked,
        false,
        List.of(
            "All declared residue assignments passed, but coverage of the original claim was not established."),
        null);
  }

  private static List<String> variables(
      ObjectNode arguments, ExactExpression lhs, ExactExpression rhs) {
    List<String> variables = new ArrayList<>();
    JsonNode declared = arguments.get("variables");
    if (declared != null) {
      variables.addAll(ComputationJson.textList(declared, "variables"));
    }
    if (variables.isEmpty()) {
      Set<String> names = new TreeSet<>(lhs.variables());
      names.addAll(rhs.variables());
      variables.addAll(names);
    }
    if (variables.isEmpty()) {
      throw new IllegalArgumentException(
          "modular_exhaustive requires at least one variable");
    }
    if (variables.size() != new HashSet<>(variables).size()) {
      throw new IllegalArgumentException(
          "modular_exhaustive variable names must be unique");
    }
    Set<String> expressionVariables = new HashSet<>(lhs.variables());
    expressionVariables.addAll(rhs.variables());
    if (!new HashSet<>(variables).containsAll(expressionVariables)) {
      throw new IllegalArgumentException("every expression variable must be declared");
    }
    return List.copyOf(variables);
  }

  private static List<BigInteger> domainValues(
      ExperimentSpec spec, String variable, int modulus) {
    JsonNode raw = spec.domains().get(variable);
    if (raw == null || raw.isNull()) {
      if (modulus > spec.maxCases()) {
        throw new IllegalArgumentException(
            "full residue domain for '"
                + variable
                + "' exceeds max_cases="
                + spec.maxCases());
      }
      return residueRange(modulus);
    }
    Set<BigInteger> values = new TreeSet<>();
    if (raw.isArray()) {
      for (JsonNode item : raw) {
        values.add(normalize(ComputationJson.integer(item, "domain value for '" + variable + "'"), modulus));
      }
    } else if (raw.isObject()) {
      if (raw.has("values")) {
        ArrayNode array = ComputationJson.requiredArray(raw.get("values"), "domain values");
        for (JsonNode item : array) {
          values.add(
              normalize(
                  ComputationJson.integer(item, "domain value for '" + variable + "'"),
                  modulus));
        }
      } else {
        BigInteger lower =
            raw.has("min")
                ? ComputationJson.integer(raw.get("min"), "domain minimum for '" + variable + "'")
                : BigInteger.ZERO;
        BigInteger upper =
            raw.has("max")
                ? ComputationJson.integer(raw.get("max"), "domain maximum for '" + variable + "'")
                : BigInteger.valueOf(modulus - 1L);
        if (upper.compareTo(lower) < 0) {
          throw new IllegalArgumentException(
              "domain for '" + variable + "' has max < min");
        }
        BigInteger span = upper.subtract(lower).add(BigInteger.ONE);
        if (span.compareTo(BigInteger.valueOf(modulus)) >= 0) {
          return residueRange(modulus);
        }
        if (span.compareTo(BigInteger.valueOf(spec.maxCases())) > 0) {
          throw new IllegalArgumentException(
              "domain for '" + variable + "' exceeds max_cases=" + spec.maxCases());
        }
        for (BigInteger value = lower;
            value.compareTo(upper) <= 0;
            value = value.add(BigInteger.ONE)) {
          values.add(normalize(value, modulus));
        }
      }
    } else {
      throw new IllegalArgumentException(
          "domain for '" + variable + "' must be a mapping or list");
    }
    if (values.isEmpty()) {
      throw new IllegalArgumentException("domain for '" + variable + "' cannot be empty");
    }
    return List.copyOf(values);
  }

  private static List<BigInteger> residueRange(int modulus) {
    List<BigInteger> values = new ArrayList<>(modulus);
    for (int value = 0; value < modulus; value++) {
      values.add(BigInteger.valueOf(value));
    }
    return List.copyOf(values);
  }

  private static BigInteger normalize(BigInteger value, int modulus) {
    return value.mod(BigInteger.valueOf(modulus));
  }

  private static final class Search {
    private final ExactExpression lhs;
    private final ExactExpression rhs;
    private final String relation;
    private final BigInteger modulus;
    private final List<String> variables;
    private final Map<String, List<BigInteger>> domains;
    private int checked;
    private ObjectNode counterexample;

    private Search(
        ExactExpression lhs,
        ExactExpression rhs,
        String relation,
        BigInteger modulus,
        List<String> variables,
        Map<String, List<BigInteger>> domains) {
      this.lhs = lhs;
      this.rhs = rhs;
      this.relation = relation;
      this.modulus = modulus;
      this.variables = variables;
      this.domains = domains;
    }

    private void visit(int index, Map<String, BigInteger> assignment) {
      if (counterexample != null) {
        return;
      }
      if (index < variables.size()) {
        String variable = variables.get(index);
        for (BigInteger value : domains.get(variable)) {
          assignment.put(variable, value);
          visit(index + 1, assignment);
          if (counterexample != null) {
            return;
          }
        }
        assignment.remove(variable);
        return;
      }
      BigInteger left = lhs.evaluateInteger(assignment).mod(modulus);
      BigInteger right = rhs.evaluateInteger(assignment).mod(modulus);
      checked++;
      boolean holds = relation.equals("eq") ? left.equals(right) : !left.equals(right);
      if (!holds) {
        BigInteger replayedLeft = lhs.evaluateInteger(Map.copyOf(assignment)).mod(modulus);
        BigInteger replayedRight = rhs.evaluateInteger(Map.copyOf(assignment)).mod(modulus);
        boolean replayedHolds =
            relation.equals("eq")
                ? replayedLeft.equals(replayedRight)
                : !replayedLeft.equals(replayedRight);
        if (!replayedHolds) {
          ObjectNode payload = ComputationJson.object();
          ObjectNode assignmentNode = payload.putObject("assignment");
          assignment.forEach(assignmentNode::put);
          payload.put("lhs_mod", left);
          payload.put("rhs_mod", right);
          payload.put("modulus", modulus);
          counterexample = payload;
        }
      }
    }
  }
}
