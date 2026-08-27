package io.github.aililuola.mathproofmesh.computation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Independent exact replay for finite number-theory certificates and witnesses. */
final class IndependentNumberTheoryCertificateVerifier {
  private static final long[] BASES =
      {2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37};

  private IndependentNumberTheoryCertificateVerifier() {}

  static boolean verify(ExperimentSpec spec, ComputationResultArtifact result) {
    try {
      ObjectNode arguments = spec.arguments();
      ObjectNode payload =
          result.outcome() == ExperimentOutcome.COUNTEREXAMPLE_FOUND
              ? result.counterexample()
              : result.outcome() == ExperimentOutcome.CERTIFIED
                  ? result.certificate()
                  : null;
      if (payload == null) {
        return false;
      }
      String operation = arguments.path("operation").asText("");
      if (!operation.equals(payload.path("operation").asText(""))) {
        return false;
      }
      return switch (operation) {
        case "multiplicative_order" -> verifyOrder(arguments, result, payload);
        case "crt" -> verifyCrt(arguments, result, payload);
        case "p_adic_valuation" -> verifyValuation(arguments, result, payload);
        case "primitive_root" -> verifyPrimitiveRoot(arguments, result, payload);
        case "is_prime" -> verifyPrimality(arguments, result, payload);
        case "factorization" -> verifyFactorization(arguments, result, payload);
        default -> false;
      };
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private static boolean verifyOrder(
      ObjectNode arguments,
      ComputationResultArtifact result,
      ObjectNode payload) {
    BigInteger a = ComputationJson.integer(arguments.get("a"), "a");
    BigInteger n = ComputationJson.integer(arguments.get("n"), "n");
    if (n.compareTo(BigInteger.TWO) < 0
        || n.compareTo(NumberTheoryFunctions.ORDER_MODULUS_LIMIT) > 0
        || !a.gcd(n).equals(BigInteger.ONE)) {
      return false;
    }
    BigInteger phi = totient(n);
    BigInteger order = phi;
    for (BigInteger prime : factor(phi).keySet()) {
      while (order.mod(prime).signum() == 0
          && a.mod(n).modPow(order.divide(prime), n).equals(BigInteger.ONE)) {
        order = order.divide(prime);
      }
    }
    boolean mismatch =
        arguments.has("claimed")
            && !ComputationJson.integer(arguments.get("claimed"), "claimed").equals(order);
    return outcomeMatches(result, mismatch)
        && payload.path("a").bigIntegerValue().equals(a)
        && payload.path("n").bigIntegerValue().equals(n)
        && payload.path("order").bigIntegerValue().equals(order);
  }

  private static boolean verifyCrt(
      ObjectNode arguments,
      ComputationResultArtifact result,
      ObjectNode payload) {
    ArrayNode rawResidues =
        ComputationJson.requiredArray(arguments.get("residues"), "residues");
    ArrayNode rawModuli =
        ComputationJson.requiredArray(arguments.get("moduli"), "moduli");
    if (rawResidues.isEmpty() || rawResidues.size() != rawModuli.size()) {
      return false;
    }
    List<BigInteger> residues = new ArrayList<>(rawResidues.size());
    List<BigInteger> moduli = new ArrayList<>(rawModuli.size());
    for (int index = 0; index < rawResidues.size(); index++) {
      residues.add(ComputationJson.integer(rawResidues.get(index), "residue"));
      BigInteger modulus = ComputationJson.integer(rawModuli.get(index), "modulus");
      if (modulus.signum() <= 0
          || modulus.compareTo(NumberTheoryFunctions.CRT_MODULUS_LIMIT) > 0) {
        return false;
      }
      moduli.add(modulus);
    }
    CrtSolution solution = solveCrt(residues, moduli);
    if (payload.path("solvable").asBoolean(false) != solution.solvable()) {
      return false;
    }
    if (!solution.solvable()) {
      JsonNode witness = payload.path("inconsistency_witness");
      if (!witness.isObject() || !witness.path("index_pair").isArray()) {
        return false;
      }
      int left = witness.path("index_pair").path(0).asInt(-1);
      int right = witness.path("index_pair").path(1).asInt(-1);
      return result.outcome() == ExperimentOutcome.CERTIFIED
          && left >= 0
          && right > left
          && right < moduli.size()
          && !residues
              .get(left)
              .subtract(residues.get(right))
              .mod(moduli.get(left).gcd(moduli.get(right)))
              .equals(BigInteger.ZERO);
    }
    boolean mismatch =
        arguments.has("claimed")
            && !ComputationJson.integer(arguments.get("claimed"), "claimed")
                .mod(solution.modulus())
                .equals(solution.value());
    return outcomeMatches(result, mismatch)
        && payload.path("solution").bigIntegerValue().equals(solution.value())
        && payload.path("combined_modulus").bigIntegerValue().equals(solution.modulus());
  }

  private static boolean verifyValuation(
      ObjectNode arguments,
      ComputationResultArtifact result,
      ObjectNode payload) {
    BigInteger p = ComputationJson.integer(arguments.get("p"), "p");
    if (!prime(p)) {
      return false;
    }
    ExactExpression expression =
        ExactExpression.parse(
            ComputationJson.requiredText(arguments.get("expression"), "expression"), 12);
    Map<String, BigInteger> assignment = new TreeMap<>();
    if (arguments.path("assignment").isObject()) {
      arguments
          .path("assignment")
          .properties()
          .forEach(
              entry ->
                  assignment.put(
                      entry.getKey(),
                      ComputationJson.integer(entry.getValue(), "assignment")));
    }
    BigInteger value = expression.evaluateInteger(assignment);
    if (value.signum() == 0) {
      return false;
    }
    int valuation = 0;
    BigInteger remaining = value.abs();
    while (remaining.mod(p).signum() == 0) {
      valuation++;
      remaining = remaining.divide(p);
    }
    boolean mismatch =
        arguments.has("claimed")
            && !ComputationJson.integer(arguments.get("claimed"), "claimed")
                .equals(BigInteger.valueOf(valuation));
    return outcomeMatches(result, mismatch)
        && payload.path("p").bigIntegerValue().equals(p)
        && payload.path("value").bigIntegerValue().equals(value)
        && payload.path("valuation").asInt(-1) == valuation;
  }

  private static boolean verifyPrimitiveRoot(
      ObjectNode arguments,
      ComputationResultArtifact result,
      ObjectNode payload) {
    BigInteger n = ComputationJson.integer(arguments.get("n"), "n");
    if (n.compareTo(BigInteger.TWO) < 0
        || n.compareTo(NumberTheoryFunctions.ORDER_MODULUS_LIMIT) > 0) {
      return false;
    }
    BigInteger phi = totient(n);
    JsonNode claimed = arguments.get("claimed");
    if (claimed != null && !claimed.isNull() && !claimed.isBoolean()) {
      BigInteger candidate = ComputationJson.integer(claimed, "claimed");
      boolean root = candidate.gcd(n).equals(BigInteger.ONE) && hasExactOrder(candidate, n, phi);
      return outcomeMatches(result, !root)
          && payload.path("claimed_root").bigIntegerValue().equals(candidate)
          && payload.path("totient").bigIntegerValue().equals(phi);
    }
    boolean allowed = primitiveRootStructureAllows(n);
    BigInteger root = allowed ? findPrimitiveRoot(n, phi) : null;
    boolean actualExists = root != null;
    boolean mismatch =
        claimed != null && claimed.isBoolean() && claimed.booleanValue() != actualExists;
    if (!outcomeMatches(result, mismatch)) {
      return false;
    }
    if (!actualExists) {
      return !payload.path("exists").asBoolean(true);
    }
    return payload.path("exists").asBoolean(false)
        && payload.path("primitive_root").bigIntegerValue().equals(root)
        && payload.path("totient").bigIntegerValue().equals(phi);
  }

  private static boolean verifyPrimality(
      ObjectNode arguments,
      ComputationResultArtifact result,
      ObjectNode payload) {
    BigInteger n = ComputationJson.integer(arguments.get("n"), "n");
    if (n.compareTo(NumberTheoryFunctions.PRIMALITY_LIMIT) > 0) {
      return false;
    }
    boolean actual = prime(n);
    JsonNode claimed = arguments.get("claimed");
    boolean mismatch =
        claimed != null && !claimed.isNull() && claimed.booleanValue() != actual;
    return outcomeMatches(result, mismatch)
        && payload.path("n").bigIntegerValue().equals(n)
        && payload.path("is_prime").asBoolean(!actual) == actual;
  }

  private static boolean verifyFactorization(
      ObjectNode arguments,
      ComputationResultArtifact result,
      ObjectNode payload) {
    BigInteger n = ComputationJson.integer(arguments.get("n"), "n");
    if (n.compareTo(BigInteger.TWO) < 0
        || n.compareTo(NumberTheoryFunctions.FACTORIZATION_LIMIT) >= 0) {
      return false;
    }
    Map<BigInteger, Integer> actual = factor(n);
    Map<BigInteger, Integer> reported = factors(payload.path("factors"));
    if (!actual.equals(reported)) {
      return false;
    }
    boolean mismatch = false;
    if (arguments.path("claimed").isObject()) {
      mismatch = !actual.equals(factors(arguments.path("claimed")));
    }
    return outcomeMatches(result, mismatch);
  }

  private static boolean outcomeMatches(
      ComputationResultArtifact result, boolean claimedMismatch) {
    return claimedMismatch
        ? result.outcome() == ExperimentOutcome.COUNTEREXAMPLE_FOUND
        : result.outcome() == ExperimentOutcome.CERTIFIED;
  }

  private static CrtSolution solveCrt(
      List<BigInteger> residues, List<BigInteger> moduli) {
    BigInteger value = BigInteger.ZERO;
    BigInteger combined = BigInteger.ONE;
    for (int index = 0; index < moduli.size(); index++) {
      BigInteger modulus = moduli.get(index);
      BigInteger residue = residues.get(index).mod(modulus);
      BigInteger gcd = combined.gcd(modulus);
      BigInteger difference = residue.subtract(value);
      if (difference.mod(gcd).signum() != 0) {
        return new CrtSolution(false, BigInteger.ZERO, BigInteger.ZERO);
      }
      BigInteger reducedModulus = modulus.divide(gcd);
      BigInteger multiplier =
          reducedModulus.equals(BigInteger.ONE)
              ? BigInteger.ZERO
              : difference
                  .divide(gcd)
                  .multiply(combined.divide(gcd).modInverse(reducedModulus))
                  .mod(reducedModulus);
      BigInteger lcm = combined.multiply(reducedModulus);
      value = value.add(combined.multiply(multiplier)).mod(lcm);
      combined = lcm;
    }
    return new CrtSolution(true, value, combined);
  }

  private static boolean prime(BigInteger n) {
    if (n.compareTo(BigInteger.TWO) < 0) {
      return false;
    }
    for (long raw : BASES) {
      BigInteger base = BigInteger.valueOf(raw);
      if (n.equals(base)) {
        return true;
      }
      if (n.mod(base).equals(BigInteger.ZERO)) {
        return false;
      }
    }
    BigInteger d = n.subtract(BigInteger.ONE);
    int powers = d.getLowestSetBit();
    d = d.shiftRight(powers);
    for (long raw : BASES) {
      BigInteger value = BigInteger.valueOf(raw).modPow(d, n);
      if (value.equals(BigInteger.ONE) || value.equals(n.subtract(BigInteger.ONE))) {
        continue;
      }
      boolean probable = false;
      for (int index = 1; index < powers; index++) {
        value = value.multiply(value).mod(n);
        if (value.equals(n.subtract(BigInteger.ONE))) {
          probable = true;
          break;
        }
      }
      if (!probable) {
        return false;
      }
    }
    return true;
  }

  private static Map<BigInteger, Integer> factor(BigInteger value) {
    Map<BigInteger, Integer> result = new TreeMap<>();
    BigInteger remaining = value;
    for (BigInteger divisor = BigInteger.TWO;
        divisor.multiply(divisor).compareTo(remaining) <= 0;
        divisor = divisor.equals(BigInteger.TWO) ? BigInteger.valueOf(3) : divisor.add(BigInteger.TWO)) {
      int exponent = 0;
      while (remaining.mod(divisor).signum() == 0) {
        remaining = remaining.divide(divisor);
        exponent++;
      }
      if (exponent > 0) {
        result.put(divisor, exponent);
      }
    }
    if (remaining.compareTo(BigInteger.ONE) > 0) {
      result.merge(remaining, 1, Integer::sum);
    }
    return Map.copyOf(result);
  }

  private static Map<BigInteger, Integer> factors(JsonNode raw) {
    if (!raw.isObject()) {
      return Map.of();
    }
    Map<BigInteger, Integer> result = new TreeMap<>();
    raw.properties()
        .forEach(
            entry ->
                result.put(
                    new BigInteger(entry.getKey()),
                    ComputationJson.integer(entry.getValue(), "exponent").intValueExact()));
    return Map.copyOf(result);
  }

  private static BigInteger totient(BigInteger n) {
    BigInteger result = n;
    for (BigInteger prime : factor(n).keySet()) {
      result = result.divide(prime).multiply(prime.subtract(BigInteger.ONE));
    }
    return result;
  }

  private static boolean hasExactOrder(
      BigInteger value, BigInteger modulus, BigInteger order) {
    if (!value.mod(modulus).modPow(order, modulus).equals(BigInteger.ONE)) {
      return false;
    }
    for (BigInteger prime : factor(order).keySet()) {
      if (value.mod(modulus).modPow(order.divide(prime), modulus).equals(BigInteger.ONE)) {
        return false;
      }
    }
    return true;
  }

  private static boolean primitiveRootStructureAllows(BigInteger n) {
    if (n.equals(BigInteger.TWO) || n.equals(BigInteger.valueOf(4L))) {
      return true;
    }
    if (n.mod(BigInteger.valueOf(4L)).signum() == 0) {
      return false;
    }
    BigInteger remaining =
        n.mod(BigInteger.TWO).signum() == 0 ? n.divide(BigInteger.TWO) : n;
    Map<BigInteger, Integer> factors = factor(remaining);
    return factors.size() == 1 && factors.keySet().iterator().next().testBit(0);
  }

  private static BigInteger findPrimitiveRoot(BigInteger n, BigInteger phi) {
    if (n.equals(BigInteger.TWO)) {
      return BigInteger.ONE;
    }
    BigInteger maximum = n.subtract(BigInteger.ONE).min(BigInteger.valueOf(1_000_000L));
    for (BigInteger candidate = BigInteger.TWO;
        candidate.compareTo(maximum) <= 0;
        candidate = candidate.add(BigInteger.ONE)) {
      if (candidate.gcd(n).equals(BigInteger.ONE) && hasExactOrder(candidate, n, phi)) {
        return candidate;
      }
    }
    return null;
  }

  private record CrtSolution(boolean solvable, BigInteger value, BigInteger modulus) {}
}
