package io.github.aililuola.mathproofmesh.computation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Independent reference checks for the bounded native computation handlers. */
final class IndependentNativeComputationVerifier {
  private IndependentNativeComputationVerifier() {}

  static boolean verifyModular(
      ExperimentSpec spec, ComputationResultArtifact result) {
    try {
      ObjectNode arguments = spec.arguments();
      int modulus =
          ComputationJson.boundedInt(
              arguments.get("modulus"), "modulus", 2, Math.max(2, spec.maxCases()));
      ExactExpression lhs =
          ExactExpression.parse(
              ComputationJson.requiredText(arguments.get("lhs"), "lhs"), 10_000);
      ExactExpression rhs =
          ExactExpression.parse(arguments.path("rhs").asText("0"), 10_000);
      String relation = arguments.path("relation").asText("eq");
      List<String> variables = declaredVariables(arguments, lhs, rhs);
      if (result.outcome() == ExperimentOutcome.COUNTEREXAMPLE_FOUND) {
        return verifyModularCounterexample(
            spec, result.counterexample(), lhs, rhs, relation, variables, modulus);
      }
      if (result.outcome() != ExperimentOutcome.CERTIFIED
          || result.certificate() == null
          || !arguments.path("finite_reduction").asBoolean(false)
          || arguments.path("reduction_justification").asText("").isBlank()
          || !result.scope().path("complete_domain").asBoolean(false)
          || !result.scope().path("full_residue_coverage").asBoolean(false)) {
        return false;
      }
      for (String variable : variables) {
        if (!hasFullResidueCoverage(spec.domains().get(variable), modulus)) {
          return false;
        }
      }
      long expectedCases = 1L;
      for (int ignored = 0; ignored < variables.size(); ignored++) {
        expectedCases = Math.multiplyExact(expectedCases, modulus);
      }
      if (expectedCases > spec.maxCases()
          || result.casesChecked() != expectedCases
          || !result.certificate().path("all_cases_satisfied").asBoolean(false)
          || result.certificate().path("modulus").asInt(-1) != modulus
          || !jsonTextList(result.certificate().path("variables")).equals(variables)) {
        return false;
      }
      return allResidueAssignmentsHold(
          0,
          variables,
          modulus,
          new LinkedHashMap<>(),
          lhs,
          rhs,
          relation);
    } catch (RuntimeException exception) {
      return false;
    }
  }

  static boolean verifyBoundedIntegerSearch(
      ExperimentSpec spec, ComputationResultArtifact result) {
    if (result.outcome() != ExperimentOutcome.COUNTEREXAMPLE_FOUND
        || result.counterexample() == null) {
      return false;
    }
    try {
      Map<String, IntegerBounds> bounds = integerBounds(spec.domains());
      IntegerRelation target =
          IntegerRelation.parse(
              ComputationJson.requiredObject(
                  spec.arguments().get("target"), "arguments.target"));
      List<IntegerRelation> constraints = new ArrayList<>();
      JsonNode rawConstraints = spec.arguments().get("constraints");
      if (rawConstraints != null) {
        for (JsonNode raw :
            ComputationJson.requiredArray(rawConstraints, "constraints")) {
          constraints.add(
              IntegerRelation.parse(
                  ComputationJson.requiredObject(raw, "constraint")));
        }
      }
      ObjectNode witness = result.counterexample();
      ObjectNode rawAssignment =
          ComputationJson.requiredObject(
              witness.get("assignment"), "counterexample assignment");
      Map<String, BigInteger> assignment = new LinkedHashMap<>();
      rawAssignment
          .properties()
          .forEach(
              entry ->
                  assignment.put(
                      entry.getKey(),
                      ComputationJson.integer(entry.getValue(), "assignment value")));
      if (!assignment.keySet().equals(bounds.keySet())) {
        return false;
      }
      for (Map.Entry<String, BigInteger> entry : assignment.entrySet()) {
        IntegerBounds domain = bounds.get(entry.getKey());
        if (entry.getValue().compareTo(domain.lower()) < 0
            || entry.getValue().compareTo(domain.upper()) > 0) {
          return false;
        }
      }
      if (constraints.stream().anyMatch(value -> !value.holds(assignment))) {
        return false;
      }
      IntegerRelation.Values values = target.evaluate(assignment);
      return !values.holds()
          && (!witness.has("lhs_value")
              || ComputationJson.integer(witness.get("lhs_value"), "lhs_value")
                  .equals(values.left()))
          && (!witness.has("rhs_value")
              || ComputationJson.integer(witness.get("rhs_value"), "rhs_value")
                  .equals(values.right()))
          && (!witness.has("relation")
              || witness.path("relation").asText().equals(target.operator()));
    } catch (RuntimeException exception) {
      return false;
    }
  }

  static boolean verifyRecurrence(
      ExperimentSpec spec, ComputationResultArtifact result) {
    if (result.outcome() != ExperimentOutcome.COUNTEREXAMPLE_FOUND
        || result.counterexample() == null) {
      return false;
    }
    try {
      ObjectNode arguments = spec.arguments();
      List<ExactRational> initial = rationals(arguments.get("initial_values"), "initial_values");
      List<ExactRational> coefficients =
          rationals(arguments.get("coefficients"), "coefficients");
      int start = arguments.path("start_n").asInt(0);
      int end = ComputationJson.integer(arguments.get("end_n"), "end_n").intValueExact();
      int required = Math.toIntExact((long) end - start + 1L);
      if (initial.isEmpty()
          || coefficients.isEmpty()
          || initial.size() < coefficients.size()
          || required < 1
          || required > spec.maxCases()) {
        return false;
      }
      ExactExpression inhomogeneous =
          ExactExpression.parse(arguments.path("inhomogeneous").asText("0"));
      ExactExpression claimed =
          ExactExpression.parse(
              ComputationJson.requiredText(
                  arguments.get("claimed_expression"), "claimed_expression"));
      List<ExactRational> generated = new ArrayList<>(initial);
      while (generated.size() < required) {
        int n = start + generated.size();
        ExactRational next = ExactRational.ZERO;
        for (int offset = 0; offset < coefficients.size(); offset++) {
          next =
              next.add(
                  coefficients
                      .get(offset)
                      .multiply(generated.get(generated.size() - offset - 1)));
        }
        generated.add(
            next.add(
                inhomogeneous.evaluate(
                    Map.of("n", new ExactRational(BigInteger.valueOf(n))))));
      }
      ObjectNode witness = result.counterexample();
      int n = witness.path("n").asInt(Integer.MIN_VALUE);
      if (n < start || n > end) {
        return false;
      }
      ExactRational actual = generated.get(n - start);
      ExactRational expected =
          claimed.evaluate(Map.of("n", new ExactRational(BigInteger.valueOf(n))));
      return !actual.equals(expected)
          && actual.equals(ExactRational.parse(witness.get("actual"), "actual"))
          && expected.equals(ExactRational.parse(witness.get("claimed"), "claimed"));
    } catch (RuntimeException exception) {
      return false;
    }
  }

  static boolean verifyGreedySequence(
      ExperimentSpec spec, ComputationResultArtifact result) {
    if (result.outcome() != ExperimentOutcome.COUNTEREXAMPLE_FOUND
        || result.counterexample() == null) {
      return false;
    }
    try {
      GreedyResult generated = generateGreedy(spec.arguments(), spec.maxCases());
      List<BigInteger> claimed =
          integers(
              ComputationJson.requiredArray(
                  spec.arguments().get("claimed_values"), "claimed_values"),
              "claimed value");
      ObjectNode expected = ComputationJson.object();
      int shared = Math.min(generated.values().size(), claimed.size());
      for (int index = 0; index < shared; index++) {
        if (!generated.values().get(index).equals(claimed.get(index))) {
          expected
              .put("index", index)
              .put("generated", generated.values().get(index))
              .put("claimed", claimed.get(index));
          return sameJson(expected, result.counterexample());
        }
      }
      if (generated.values().size() != claimed.size()) {
        expected
            .put("index", shared)
            .put("generated_length", generated.values().size())
            .put("claimed_length", claimed.size());
        return sameJson(expected, result.counterexample());
      }
      return false;
    } catch (RuntimeException exception) {
      return false;
    }
  }

  static boolean verifyCandidatePeriod(
      ExperimentSpec spec, ComputationResultArtifact result) {
    if (result.outcome() != ExperimentOutcome.COUNTEREXAMPLE_FOUND
        || result.counterexample() == null) {
      return false;
    }
    try {
      ObjectNode arguments = spec.arguments();
      ArrayNode rawValues =
          ComputationJson.requiredArray(arguments.get("values"), "values");
      List<ExactRational> values = rationals(rawValues, "value");
      int period =
          ComputationJson.boundedInt(
              arguments.get("candidate_period"), "candidate_period", 1, Integer.MAX_VALUE);
      int start =
          arguments.has("start_index")
              ? ComputationJson.boundedInt(
                  arguments.get("start_index"), "start_index", 0, Integer.MAX_VALUE)
              : 0;
      for (int index = start + period; index < values.size(); index++) {
        int prior = index - period;
        if (!values.get(index).equals(values.get(prior))) {
          ObjectNode expected =
              ComputationJson.object()
                  .put("index", index)
                  .put("prior_index", prior)
                  .put("value", values.get(index).toString())
                  .put("prior_value", values.get(prior).toString())
                  .put("candidate_period", period);
          return sameJson(expected, result.counterexample());
        }
      }
      return false;
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private static boolean verifyModularCounterexample(
      ExperimentSpec spec,
      ObjectNode witness,
      ExactExpression lhs,
      ExactExpression rhs,
      String relation,
      List<String> variables,
      int modulus) {
    if (witness == null || witness.path("modulus").asInt(-1) != modulus) {
      return false;
    }
    ObjectNode rawAssignment =
        ComputationJson.requiredObject(witness.get("assignment"), "assignment");
    Map<String, BigInteger> assignment = new LinkedHashMap<>();
    rawAssignment
        .properties()
        .forEach(
            entry ->
                assignment.put(
                    entry.getKey(),
                    ComputationJson.integer(entry.getValue(), "assignment").mod(BigInteger.valueOf(modulus))));
    if (!assignment.keySet().equals(new LinkedHashSet<>(variables))) {
      return false;
    }
    for (Map.Entry<String, BigInteger> entry : assignment.entrySet()) {
      if (!modularValueAllowed(spec.domains().get(entry.getKey()), entry.getValue(), modulus)) {
        return false;
      }
    }
    BigInteger base = BigInteger.valueOf(modulus);
    BigInteger left = lhs.evaluateInteger(assignment).mod(base);
    BigInteger right = rhs.evaluateInteger(assignment).mod(base);
    boolean holds = relation.equals("eq") ? left.equals(right) : !left.equals(right);
    return !holds
        && left.equals(ComputationJson.integer(witness.get("lhs_mod"), "lhs_mod"))
        && right.equals(ComputationJson.integer(witness.get("rhs_mod"), "rhs_mod"));
  }

  private static List<String> declaredVariables(
      ObjectNode arguments, ExactExpression lhs, ExactExpression rhs) {
    List<String> variables = new ArrayList<>();
    if (arguments.path("variables").isArray()) {
      variables.addAll(jsonTextList(arguments.path("variables")));
    }
    if (variables.isEmpty()) {
      Set<String> names = new TreeSet<>(lhs.variables());
      names.addAll(rhs.variables());
      variables.addAll(names);
    }
    if (variables.isEmpty()
        || variables.size() != new HashSet<>(variables).size()
        || !Set.copyOf(variables).containsAll(lhs.variables())
        || !Set.copyOf(variables).containsAll(rhs.variables())) {
      throw new IllegalArgumentException("invalid modular variables");
    }
    return List.copyOf(variables);
  }

  private static boolean hasFullResidueCoverage(JsonNode domain, int modulus) {
    if (domain == null || domain.isNull()) {
      return true;
    }
    Set<BigInteger> residues = modularDomain(domain, modulus);
    return residues.size() == modulus;
  }

  private static boolean modularValueAllowed(
      JsonNode domain, BigInteger value, int modulus) {
    return domain == null
        || domain.isNull()
        || modularDomain(domain, modulus).contains(value.mod(BigInteger.valueOf(modulus)));
  }

  private static Set<BigInteger> modularDomain(JsonNode raw, int modulus) {
    Set<BigInteger> values = new TreeSet<>();
    BigInteger base = BigInteger.valueOf(modulus);
    if (raw.isArray()) {
      raw.forEach(value -> values.add(ComputationJson.integer(value, "domain").mod(base)));
    } else if (raw.isObject() && raw.has("values")) {
      raw.path("values")
          .forEach(value -> values.add(ComputationJson.integer(value, "domain").mod(base)));
    } else if (raw.isObject()) {
      BigInteger lower =
          raw.has("min") ? ComputationJson.integer(raw.get("min"), "min") : BigInteger.ZERO;
      BigInteger upper =
          raw.has("max")
              ? ComputationJson.integer(raw.get("max"), "max")
              : BigInteger.valueOf(modulus - 1L);
      BigInteger span = upper.subtract(lower).add(BigInteger.ONE);
      if (span.compareTo(base) >= 0) {
        for (int value = 0; value < modulus; value++) {
          values.add(BigInteger.valueOf(value));
        }
      } else {
        for (BigInteger value = lower;
            value.compareTo(upper) <= 0;
            value = value.add(BigInteger.ONE)) {
          values.add(value.mod(base));
        }
      }
    } else {
      throw new IllegalArgumentException("invalid modular domain");
    }
    return Set.copyOf(values);
  }

  private static boolean allResidueAssignmentsHold(
      int index,
      List<String> variables,
      int modulus,
      Map<String, BigInteger> assignment,
      ExactExpression lhs,
      ExactExpression rhs,
      String relation) {
    if (index == variables.size()) {
      BigInteger base = BigInteger.valueOf(modulus);
      BigInteger left = lhs.evaluateInteger(assignment).mod(base);
      BigInteger right = rhs.evaluateInteger(assignment).mod(base);
      return relation.equals("eq") ? left.equals(right) : !left.equals(right);
    }
    String variable = variables.get(index);
    for (int value = 0; value < modulus; value++) {
      assignment.put(variable, BigInteger.valueOf(value));
      if (!allResidueAssignmentsHold(
          index + 1, variables, modulus, assignment, lhs, rhs, relation)) {
        return false;
      }
    }
    assignment.remove(variable);
    return true;
  }

  private static Map<String, IntegerBounds> integerBounds(ObjectNode domains) {
    Map<String, IntegerBounds> bounds = new LinkedHashMap<>();
    ComputationJson.sortedFields(domains)
        .forEach(
            (variable, raw) -> {
              ObjectNode domain =
                  ComputationJson.requiredObject(raw, "domain for " + variable);
              BigInteger lower = ComputationJson.integer(domain.get("min"), "domain min");
              BigInteger upper = ComputationJson.integer(domain.get("max"), "domain max");
              if (upper.compareTo(lower) < 0) {
                throw new IllegalArgumentException("invalid integer domain");
              }
              bounds.put(variable, new IntegerBounds(lower, upper));
            });
    if (bounds.isEmpty()) {
      throw new IllegalArgumentException("integer domains are required");
    }
    return Map.copyOf(bounds);
  }

  private static GreedyResult generateGreedy(ObjectNode arguments, int maxCases) {
    List<BigInteger> values =
        new ArrayList<>(
            integers(
                ComputationJson.requiredArray(
                    arguments.get("initial_values"), "initial_values"),
                "initial value"));
    int length = arguments.path("length").asInt(values.size());
    BigInteger minimum =
        arguments.has("candidate_min")
            ? ComputationJson.integer(arguments.get("candidate_min"), "candidate_min")
            : BigInteger.ZERO;
    BigInteger maximum =
        arguments.has("candidate_max")
            ? ComputationJson.integer(arguments.get("candidate_max"), "candidate_max")
            : BigInteger.valueOf(1_000_000L);
    boolean increasing = arguments.path("strictly_increasing").asBoolean(true);
    String rule =
        arguments.path("rule").asText("avoid_three_term_arithmetic_progression");
    Set<BigInteger> forbidden = new HashSet<>();
    if (rule.equals("avoid_forbidden_differences")) {
      arguments
          .path("forbidden_differences")
          .forEach(value -> forbidden.add(ComputationJson.integer(value, "difference").abs()));
    }
    int checked = 0;
    while (values.size() < length) {
      BigInteger start = increasing ? minimum.max(values.getLast().add(BigInteger.ONE)) : minimum;
      BigInteger chosen = null;
      for (BigInteger candidate = start;
          candidate.compareTo(maximum) <= 0;
          candidate = candidate.add(BigInteger.ONE)) {
        if (++checked > maxCases) {
          throw new IllegalArgumentException("greedy verification exceeded maxCases");
        }
        if (!values.contains(candidate)
            && greedyAccepts(rule, forbidden, candidate, values)) {
          chosen = candidate;
          break;
        }
      }
      if (chosen == null) {
        throw new IllegalArgumentException("no greedy value");
      }
      values.add(chosen);
    }
    return new GreedyResult(List.copyOf(values), checked);
  }

  private static boolean greedyAccepts(
      String rule,
      Set<BigInteger> forbidden,
      BigInteger candidate,
      List<BigInteger> prior) {
    return switch (rule) {
      case "avoid_forbidden_differences" ->
          prior.stream()
              .noneMatch(value -> forbidden.contains(candidate.subtract(value).abs()));
      case "avoid_three_term_arithmetic_progression" -> {
        boolean valid = true;
        for (int left = 0; left < prior.size() && valid; left++) {
          for (int middle = left + 1; middle < prior.size(); middle++) {
            if (prior.get(left).add(candidate)
                .equals(prior.get(middle).multiply(BigInteger.TWO))) {
              valid = false;
              break;
            }
          }
        }
        yield valid;
      }
      case "coprime_to_all" ->
          prior.stream()
              .filter(value -> value.signum() != 0)
              .allMatch(value -> candidate.abs().gcd(value.abs()).equals(BigInteger.ONE));
      case "gcd_overlap_all_prior" ->
          prior.stream()
              .allMatch(
                  value -> candidate.abs().gcd(value.abs()).compareTo(BigInteger.ONE) > 0);
      default -> false;
    };
  }

  private static List<ExactRational> rationals(JsonNode raw, String label) {
    ArrayNode values = ComputationJson.requiredArray(raw, label);
    List<ExactRational> result = new ArrayList<>(values.size());
    values.forEach(value -> result.add(ExactRational.parse(value, label)));
    return List.copyOf(result);
  }

  private static List<BigInteger> integers(ArrayNode values, String label) {
    List<BigInteger> result = new ArrayList<>(values.size());
    values.forEach(value -> result.add(ComputationJson.integer(value, label)));
    return List.copyOf(result);
  }

  private static List<String> jsonTextList(JsonNode values) {
    List<String> result = new ArrayList<>();
    values.forEach(value -> result.add(value.asText()));
    return List.copyOf(result);
  }

  private static boolean sameJson(JsonNode expected, JsonNode actual) {
    return actual != null
        && CanonicalJson.stableHash(expected).equals(CanonicalJson.stableHash(actual));
  }

  private record IntegerBounds(BigInteger lower, BigInteger upper) {}

  private record GreedyResult(List<BigInteger> values, int checked) {}

  private record IntegerRelation(
      ExactExpression lhs, ExactExpression rhs, String operator) {
    private static IntegerRelation parse(ObjectNode raw) {
      return new IntegerRelation(
          ExactExpression.parse(
              ComputationJson.requiredText(raw.get("lhs"), "relation lhs"), 12),
          ExactExpression.parse(raw.path("rhs").asText("0"), 12),
          raw.path("relation").asText("eq"));
    }

    private boolean holds(Map<String, BigInteger> assignment) {
      return evaluate(assignment).holds();
    }

    private Values evaluate(Map<String, BigInteger> assignment) {
      BigInteger left = lhs.evaluateInteger(assignment);
      BigInteger right = rhs.evaluateInteger(assignment);
      int comparison = left.compareTo(right);
      boolean valid =
          switch (operator) {
            case "eq" -> comparison == 0;
            case "ne" -> comparison != 0;
            case "le" -> comparison <= 0;
            case "lt" -> comparison < 0;
            case "ge" -> comparison >= 0;
            case "gt" -> comparison > 0;
            default -> false;
          };
      return new Values(left, right, valid);
    }

    private record Values(BigInteger left, BigInteger right, boolean holds) {}
  }
}
