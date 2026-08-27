package io.github.aililuola.mathproofmesh.computation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Exact rational linear-recurrence checker over a declared finite interval. */
public final class RecurrenceFunctions {
  private RecurrenceFunctions() {}

  public static HandlerEvidence run(ExperimentSpec spec) {
    ObjectNode arguments = spec.arguments();
    List<ExactRational> initial =
        rationals(
            ComputationJson.requiredArray(arguments.get("initial_values"), "initial_values"),
            "initial_values item");
    List<ExactRational> coefficients =
        rationals(
            ComputationJson.requiredArray(arguments.get("coefficients"), "coefficients"),
            "coefficients item");
    if (initial.isEmpty() || coefficients.isEmpty()) {
      throw new IllegalArgumentException(
          "initial_values and coefficients must be non-empty");
    }
    if (initial.size() < coefficients.size()) {
      throw new IllegalArgumentException(
          "initial_values must contain at least recurrence order values");
    }
    int start =
        arguments.has("start_n")
            ? ComputationJson.integer(arguments.get("start_n"), "start_n").intValueExact()
            : 0;
    int end = ComputationJson.integer(arguments.get("end_n"), "end_n").intValueExact();
    long requiredLong = (long) end - start + 1;
    if (end < start || requiredLong > spec.maxCases() || requiredLong > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("invalid or over-budget recurrence range");
    }
    int required = (int) requiredLong;
    ExactExpression inhomogeneous =
        ExactExpression.parse(
            arguments.has("inhomogeneous")
                ? arguments.get("inhomogeneous").asText()
                : "0");
    requireOnlyN(inhomogeneous, "inhomogeneous");
    List<ExactRational> generated = new ArrayList<>(initial);
    while (generated.size() < required) {
      int index = start + generated.size();
      ExactRational value = ExactRational.ZERO;
      for (int offset = 0; offset < coefficients.size(); offset++) {
        value =
            value.add(
                coefficients
                    .get(offset)
                    .multiply(generated.get(generated.size() - offset - 1)));
      }
      ExactRational extra =
          inhomogeneous.evaluate(
              Map.of("n", new ExactRational(BigInteger.valueOf(index))));
      generated.add(value.add(extra));
    }
    List<ExactRational> sequence = List.copyOf(generated.subList(0, required));
    JsonNode claimedNode = arguments.get("claimed_expression");
    if (claimedNode == null || claimedNode.isNull()) {
      ObjectNode certificate =
          ComputationJson.object().put("start_n", start).put("end_n", end);
      ArrayNode values = certificate.putArray("values");
      sequence.forEach(value -> values.add(value.toString()));
      return new HandlerEvidence(
          ExperimentOutcome.NOT_REFUTED,
          EvidenceStrength.BOUNDED_EVIDENCE,
          ComputationJson.object().put("start_n", start).put("end_n", end),
          null,
          certificate,
          true,
          sequence.size(),
          false,
          List.of(
              "The recurrence values were generated exactly; no general claim was certified."),
          null);
    }

    String claimedText = claimedNode.asText();
    ExactExpression claimed = ExactExpression.parse(claimedText);
    requireOnlyN(claimed, "claimed_expression");
    for (int offset = 0; offset < sequence.size(); offset++) {
      int index = start + offset;
      Map<String, ExactRational> assignment =
          Map.of("n", new ExactRational(BigInteger.valueOf(index)));
      ExactRational expected = claimed.evaluate(assignment);
      ExactRational actual = sequence.get(offset);
      if (!actual.equals(expected)) {
        ExactRational replayed = claimed.evaluate(Map.copyOf(assignment));
        if (!actual.equals(replayed)) {
          return new HandlerEvidence(
              ExperimentOutcome.COUNTEREXAMPLE_FOUND,
              EvidenceStrength.COUNTEREXAMPLE,
              ComputationJson.object().put("start_n", start).put("end_n", end),
              ComputationJson.object()
                  .put("n", index)
                  .put("actual", actual.toString())
                  .put("claimed", expected.toString()),
              null,
              true,
              offset + 1,
              true,
              List.of(
                  "The first mismatch was independently re-substituted using exact rational arithmetic."),
              null);
        }
      }
    }
    ObjectNode certificate =
        ComputationJson.object().put("claimed_expression", claimedText);
    ArrayNode values = certificate.putArray("values");
    sequence.forEach(value -> values.add(value.toString()));
    return new HandlerEvidence(
        ExperimentOutcome.NOT_REFUTED,
        EvidenceStrength.BOUNDED_EVIDENCE,
        ComputationJson.object().put("start_n", start).put("end_n", end),
        null,
        certificate,
        true,
        sequence.size(),
        false,
        List.of(
            "The claimed formula matched only the declared finite interval; induction or another proof is still required."),
        null);
  }

  private static List<ExactRational> rationals(ArrayNode values, String label) {
    List<ExactRational> result = new ArrayList<>(values.size());
    for (JsonNode value : values) {
      result.add(ExactRational.parse(value, label));
    }
    return List.copyOf(result);
  }

  private static void requireOnlyN(ExactExpression expression, String label) {
    if (!SetSupport.only(expression.variables(), "n")) {
      throw new IllegalArgumentException(label + " may use only the variable n");
    }
  }

  private static final class SetSupport {
    private SetSupport() {}

    private static boolean only(java.util.Set<String> values, String allowed) {
      return values.isEmpty() || (values.size() == 1 && values.contains(allowed));
    }
  }
}
