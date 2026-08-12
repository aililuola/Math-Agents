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
import java.util.List;
import java.util.Set;

/** Exact finite sequence generators and candidate-period checkers. */
public final class SequenceFunctions {
  private SequenceFunctions() {}

  public static HandlerEvidence runBoundedGreedySequence(ExperimentSpec spec) {
    Generated generated = generate(spec.arguments(), spec.maxCases());
    JsonNode claimed = spec.arguments().get("claimed_values");
    if (claimed != null && !claimed.isNull()) {
      ArrayNode array = ComputationJson.requiredArray(claimed, "claimed_values");
      List<BigInteger> expected = integers(array, "claimed_values item");
      int shared = Math.min(generated.values.size(), expected.size());
      for (int index = 0; index < shared; index++) {
        if (!generated.values.get(index).equals(expected.get(index))) {
          Generated replay = generate(spec.arguments(), spec.maxCases());
          if (!replay.values.get(index).equals(expected.get(index))) {
            ObjectNode counterexample =
                ComputationJson.object()
                    .put("index", index)
                    .put("generated", generated.values.get(index))
                    .put("claimed", expected.get(index));
            return counterexample(generated, counterexample);
          }
        }
      }
      if (expected.size() != generated.values.size()) {
        ObjectNode counterexample =
            ComputationJson.object()
                .put("index", shared)
                .put("generated_length", generated.values.size())
                .put("claimed_length", expected.size());
        return counterexample(generated, counterexample);
      }
    }
    ObjectNode certificate = ComputationJson.object();
    ArrayNode values = certificate.putArray("values");
    generated.values.forEach(values::add);
    certificate.put("rule", generated.rule);
    return new HandlerEvidence(
        ExperimentOutcome.NOT_REFUTED,
        EvidenceStrength.BOUNDED_EVIDENCE,
        ComputationJson.object()
            .put("generated_length", generated.values.size())
            .put("rule", generated.rule),
        null,
        certificate,
        true,
        generated.checked,
        false,
        List.of(
            "This is only a deterministic finite prefix; it does not prove an infinite pattern."),
        null);
  }

  public static HandlerEvidence runCandidatePeriodCheck(ExperimentSpec spec) {
    ObjectNode arguments = spec.arguments();
    ArrayNode rawValues = ComputationJson.requiredArray(arguments.get("values"), "values");
    if (rawValues.isEmpty()) {
      throw new IllegalArgumentException("values must be non-empty");
    }
    List<ExactRational> values = new ArrayList<>(rawValues.size());
    for (JsonNode value : rawValues) {
      values.add(ExactRational.parse(value, "values item"));
    }
    int period =
        ComputationJson.boundedInt(
            arguments.get("candidate_period"), "candidate_period", 1, Integer.MAX_VALUE);
    int start =
        arguments.has("start_index")
            ? ComputationJson.boundedInt(
                arguments.get("start_index"), "start_index", 0, Integer.MAX_VALUE)
            : 0;
    if ((long) start + period >= values.size()) {
      throw new IllegalArgumentException(
          "candidate_period/start_index leave no comparable pair");
    }
    int comparisons = values.size() - (start + period);
    if (comparisons > spec.maxCases()) {
      throw new IllegalArgumentException("period check exceeds max_cases");
    }
    for (int index = start + period; index < values.size(); index++) {
      int priorIndex = index - period;
      if (values.get(index).equals(values.get(priorIndex))) {
        continue;
      }
      ExactRational current = ExactRational.parse(rawValues.get(index), "rechecked current value");
      ExactRational prior = ExactRational.parse(rawValues.get(priorIndex), "rechecked prior value");
      if (!current.equals(prior)) {
        ObjectNode counterexample =
            ComputationJson.object()
                .put("index", index)
                .put("prior_index", priorIndex)
                .put("value", current.toString())
                .put("prior_value", prior.toString())
                .put("candidate_period", period);
        return new HandlerEvidence(
            ExperimentOutcome.COUNTEREXAMPLE_FOUND,
            EvidenceStrength.COUNTEREXAMPLE,
            ComputationJson.object()
                .put("start_index", start)
                .put("end_index", values.size() - 1)
                .put("candidate_period", period),
            counterexample,
            null,
            true,
            index - (start + period) + 1,
            true,
            List.of(
                "The violating pair was independently re-read with exact rational arithmetic."),
            null);
      }
    }
    return new HandlerEvidence(
        ExperimentOutcome.NOT_REFUTED,
        EvidenceStrength.BOUNDED_EVIDENCE,
        ComputationJson.object()
            .put("start_index", start)
            .put("end_index", values.size() - 1)
            .put("candidate_period", period),
        null,
        ComputationJson.object()
            .put("candidate_period", period)
            .put("matching_comparisons", comparisons),
        true,
        comparisons,
        false,
        List.of(
            "The candidate period matched only this finite list and remains not_refuted, not proved."),
        null);
  }

  private static HandlerEvidence counterexample(Generated generated, ObjectNode payload) {
    return new HandlerEvidence(
        ExperimentOutcome.COUNTEREXAMPLE_FOUND,
        EvidenceStrength.COUNTEREXAMPLE,
        ComputationJson.object()
            .put("generated_length", generated.values.size())
            .put("rule", generated.rule),
        payload,
        null,
        true,
        generated.checked,
        true,
        List.of(
            "The first mismatch was independently regenerated by the same deterministic typed rule."),
        null);
  }

  private static Generated generate(ObjectNode arguments, int maxCases) {
    ArrayNode initial =
        ComputationJson.requiredArray(arguments.get("initial_values"), "initial_values");
    List<BigInteger> values = new ArrayList<>(integers(initial, "initial_values item"));
    if (values.isEmpty()) {
      throw new IllegalArgumentException("initial_values must be non-empty");
    }
    int length =
        arguments.has("length")
            ? ComputationJson.boundedInt(
                arguments.get("length"), "length", 1, Integer.MAX_VALUE)
            : values.size();
    if (length < values.size()) {
      throw new IllegalArgumentException(
          "length must be at least the number of initial values");
    }
    BigInteger candidateMin =
        arguments.has("candidate_min")
            ? ComputationJson.integer(arguments.get("candidate_min"), "candidate_min")
            : BigInteger.ZERO;
    BigInteger candidateMax =
        arguments.has("candidate_max")
            ? ComputationJson.integer(arguments.get("candidate_max"), "candidate_max")
            : BigInteger.valueOf(1_000_000);
    if (candidateMax.compareTo(candidateMin) < 0) {
      throw new IllegalArgumentException("candidate_max must not be below candidate_min");
    }
    boolean strictlyIncreasing = arguments.path("strictly_increasing").asBoolean(true);
    String rule =
        arguments.has("rule")
            ? arguments.get("rule").asText()
            : "avoid_three_term_arithmetic_progression";
    Set<BigInteger> forbidden = new HashSet<>();
    if (rule.equals("avoid_forbidden_differences")) {
      ArrayNode raw =
          ComputationJson.requiredArray(
              arguments.get("forbidden_differences"), "forbidden_differences");
      for (JsonNode value : raw) {
        forbidden.add(ComputationJson.integer(value, "forbidden_differences item").abs());
      }
      if (forbidden.isEmpty()) {
        throw new IllegalArgumentException(
            "forbidden_differences must be non-empty for this rule");
      }
    } else if (!List.of(
            "avoid_three_term_arithmetic_progression",
            "coprime_to_all",
            "gcd_overlap_all_prior")
        .contains(rule)) {
      throw new IllegalArgumentException(
          "rule must be avoid_forbidden_differences, avoid_three_term_arithmetic_progression, coprime_to_all, or gcd_overlap_all_prior");
    }

    int checked = 0;
    while (values.size() < length) {
      BigInteger start = candidateMin;
      if (strictlyIncreasing) {
        start = start.max(values.getLast().add(BigInteger.ONE));
      }
      BigInteger chosen = null;
      for (BigInteger candidate = start;
          candidate.compareTo(candidateMax) <= 0;
          candidate = candidate.add(BigInteger.ONE)) {
        checked++;
        if (checked > maxCases) {
          throw new IllegalArgumentException("bounded greedy search exceeded max_cases");
        }
        if (!values.contains(candidate) && accepts(rule, forbidden, candidate, values)) {
          chosen = candidate;
          break;
        }
      }
      if (chosen == null) {
        throw new IllegalArgumentException(
            "no admissible next value exists in the declared finite domain");
      }
      values.add(chosen);
    }
    return new Generated(List.copyOf(values), checked, rule);
  }

  private static boolean accepts(
      String rule,
      Set<BigInteger> forbidden,
      BigInteger candidate,
      List<BigInteger> prior) {
    return switch (rule) {
      case "avoid_forbidden_differences" ->
          prior.stream().noneMatch(value -> forbidden.contains(candidate.subtract(value).abs()));
      case "avoid_three_term_arithmetic_progression" -> {
        boolean accepted = true;
        for (int leftIndex = 0; leftIndex < prior.size() && accepted; leftIndex++) {
          for (int middleIndex = leftIndex + 1;
              middleIndex < prior.size();
              middleIndex++) {
            if (prior.get(leftIndex).add(candidate)
                .equals(prior.get(middleIndex).multiply(BigInteger.TWO))) {
              accepted = false;
              break;
            }
          }
        }
        yield accepted;
      }
      case "coprime_to_all" ->
          prior.stream()
              .filter(value -> value.signum() != 0)
              .allMatch(value -> candidate.abs().gcd(value.abs()).equals(BigInteger.ONE));
      case "gcd_overlap_all_prior" ->
          prior.stream()
              .allMatch(
                  value ->
                      candidate.abs().gcd(value.abs()).compareTo(BigInteger.ONE) > 0);
      default -> throw new IllegalStateException("unknown greedy rule: " + rule);
    };
  }

  private static List<BigInteger> integers(ArrayNode values, String label) {
    List<BigInteger> result = new ArrayList<>(values.size());
    for (JsonNode value : values) {
      result.add(ComputationJson.integer(value, label));
    }
    return List.copyOf(result);
  }

  private record Generated(List<BigInteger> values, int checked, String rule) {}
}
