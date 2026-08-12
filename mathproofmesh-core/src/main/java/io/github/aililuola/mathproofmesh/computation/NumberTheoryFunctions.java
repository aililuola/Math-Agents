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
import java.util.Set;
import java.util.TreeMap;

/** Deterministic, guarded finite number-theory checks. */
public final class NumberTheoryFunctions {
  public static final BigInteger FACTORIZATION_LIMIT = BigInteger.TEN.pow(12);
  public static final BigInteger PRIMALITY_LIMIT = BigInteger.TEN.pow(18);
  public static final BigInteger ORDER_MODULUS_LIMIT = BigInteger.TEN.pow(12);
  public static final BigInteger CRT_MODULUS_LIMIT = BigInteger.TEN.pow(12);
  private static final int MAX_CRT_CONGRUENCES = 64;
  private static final long[] MILLER_RABIN_BASES =
      {2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37};
  private static final String FINITE_ONLY =
      "This certifies only the specific finite assertion checked; it cannot certify any infinite generalization of it.";
  private static final Set<String> OPERATIONS =
      Set.of(
          "multiplicative_order",
          "crt",
          "p_adic_valuation",
          "primitive_root",
          "is_prime",
          "factorization");

  private NumberTheoryFunctions() {}

  public static HandlerEvidence run(ExperimentSpec spec) {
    ObjectNode arguments = spec.arguments();
    String operation = arguments.path("operation").asText("");
    if (!OPERATIONS.contains(operation)) {
      throw new IllegalArgumentException(
          "operation must be one of: "
              + String.join(", ", new java.util.TreeSet<>(OPERATIONS)));
    }
    return switch (operation) {
      case "multiplicative_order" -> multiplicativeOrder(arguments);
      case "crt" -> crt(arguments);
      case "p_adic_valuation" -> pAdicValuation(arguments);
      case "primitive_root" -> primitiveRoot(arguments);
      case "is_prime" -> isPrime(arguments);
      case "factorization" -> factorization(arguments);
      default -> throw new IllegalStateException("unreachable number-theory operation");
    };
  }

  private static HandlerEvidence multiplicativeOrder(ObjectNode arguments) {
    BigInteger a = ComputationJson.integer(arguments.get("a"), "a");
    BigInteger n = ComputationJson.integer(arguments.get("n"), "n");
    if (n.compareTo(BigInteger.TWO) < 0) {
      throw new IllegalArgumentException("multiplicative_order requires n >= 2");
    }
    if (n.compareTo(ORDER_MODULUS_LIMIT) > 0) {
      return guarded(
          "The modulus "
              + n
              + " exceeds the exact multiplicative-order guard bound "
              + ORDER_MODULUS_LIMIT
              + ".",
          ComputationJson.object().put("operation", "multiplicative_order").put("n", n));
    }
    if (!a.gcd(n).equals(BigInteger.ONE)) {
      throw new IllegalArgumentException(
          "multiplicative_order requires gcd(a, n) = 1");
    }
    BigInteger phi = totient(n);
    BigInteger order = phi;
    for (BigInteger prime : factor(order).keySet()) {
      while (order.mod(prime).signum() == 0
          && a.mod(n).modPow(order.divide(prime), n).equals(BigInteger.ONE)) {
        order = order.divide(prime);
      }
    }
    if (!verifyOrder(a, n, order)) {
      throw new IllegalStateException(
          "the computed multiplicative order failed its independent recheck");
    }
    String statement = "ord_" + n + "(" + a.mod(n) + ") = " + order;
    ObjectNode certificate =
        ComputationJson.object()
            .put("operation", "multiplicative_order")
            .put("a", a)
            .put("n", n)
            .put("order", order)
            .put("statement", statement);
    JsonNode claimed = arguments.get("claimed");
    if (claimed != null && !claimed.isNull()) {
      BigInteger claimedOrder = ComputationJson.integer(claimed, "claimed");
      if (!claimedOrder.equals(order)) {
        certificate.put("claimed_order", claimedOrder);
        return refuted(statement, certificate);
      }
      certificate.put("claimed_order", claimedOrder);
    }
    return certified(
        statement,
        certificate,
        1,
        "The order was independently rechecked with modular exponentiation over the prime divisors of the computed order.");
  }

  private static HandlerEvidence crt(ObjectNode arguments) {
    ArrayNode residuesNode =
        ComputationJson.requiredArray(arguments.get("residues"), "residues");
    ArrayNode moduliNode =
        ComputationJson.requiredArray(arguments.get("moduli"), "moduli");
    if (residuesNode.isEmpty() || moduliNode.isEmpty()) {
      throw new IllegalArgumentException(
          "residues and moduli must be non-empty lists of integers");
    }
    if (residuesNode.size() != moduliNode.size()) {
      throw new IllegalArgumentException(
          "residues and moduli must have the same length");
    }
    if (moduliNode.size() > MAX_CRT_CONGRUENCES) {
      throw new IllegalArgumentException(
          "crt accepts at most " + MAX_CRT_CONGRUENCES + " congruences");
    }
    List<BigInteger> residues = new ArrayList<>(residuesNode.size());
    List<BigInteger> moduli = new ArrayList<>(moduliNode.size());
    for (int index = 0; index < residuesNode.size(); index++) {
      residues.add(ComputationJson.integer(residuesNode.get(index), "residues[" + index + "]"));
      BigInteger modulus =
          ComputationJson.integer(moduliNode.get(index), "moduli[" + index + "]");
      if (modulus.signum() <= 0) {
        throw new IllegalArgumentException("moduli[" + index + "] must be positive");
      }
      if (modulus.compareTo(CRT_MODULUS_LIMIT) > 0) {
        return guarded(
            "moduli["
                + index
                + "] = "
                + modulus
                + " exceeds the CRT guard bound "
                + CRT_MODULUS_LIMIT
                + ".",
            ComputationJson.object().put("operation", "crt").put("modulus", modulus));
      }
      moduli.add(modulus);
    }

    BigInteger value = BigInteger.ZERO;
    BigInteger combined = BigInteger.ONE;
    for (int index = 0; index < moduli.size(); index++) {
      BigInteger modulus = moduli.get(index);
      BigInteger residue = residues.get(index).mod(modulus);
      BigInteger gcd = combined.gcd(modulus);
      BigInteger difference = residue.subtract(value);
      if (difference.mod(gcd).signum() != 0) {
        ObjectNode witness =
            findCrtWitness(residues, moduli);
        String statement = "the declared congruence system has no solution";
        ObjectNode certificate =
            baseCrtCertificate(residues, moduli)
                .put("solvable", false)
                .put("statement", statement);
        certificate.set("inconsistency_witness", witness);
        return certified(
            statement,
            certificate,
            1,
            "Unsolvability was independently rechecked through an explicit pairwise gcd inconsistency witness.");
      }
      BigInteger reducedCombined = combined.divide(gcd);
      BigInteger reducedModulus = modulus.divide(gcd);
      BigInteger multiplier =
          difference
              .divide(gcd)
              .multiply(reducedCombined.modInverse(reducedModulus))
              .mod(reducedModulus);
      BigInteger lcm = combined.multiply(reducedModulus);
      value = value.add(combined.multiply(multiplier)).mod(lcm);
      combined = lcm;
    }
    for (int index = 0; index < moduli.size(); index++) {
      if (!value.subtract(residues.get(index)).mod(moduli.get(index)).equals(BigInteger.ZERO)) {
        throw new IllegalStateException("the CRT solution failed its independent recheck");
      }
    }
    String statement =
        "x = " + value + " (mod " + combined + ") solves the declared system";
    ObjectNode certificate =
        baseCrtCertificate(residues, moduli)
            .put("solvable", true)
            .put("solution", value)
            .put("combined_modulus", combined)
            .put("statement", statement);
    JsonNode claimed = arguments.get("claimed");
    if (claimed != null && !claimed.isNull()) {
      BigInteger claimedSolution = ComputationJson.integer(claimed, "claimed");
      if (!claimedSolution.mod(combined).equals(value.mod(combined))) {
        certificate.put("claimed_solution", claimedSolution);
        return refuted(statement, certificate);
      }
      certificate.put("claimed_solution", claimedSolution);
    }
    return certified(
        statement,
        certificate,
        moduli.size(),
        "Every declared congruence was independently rechecked with exact integer arithmetic.");
  }

  private static HandlerEvidence pAdicValuation(ObjectNode arguments) {
    BigInteger p = ComputationJson.integer(arguments.get("p"), "p");
    if (p.compareTo(PRIMALITY_LIMIT) > 0) {
      return guarded(
          "The prime candidate "
              + p
              + " exceeds the primality guard bound "
              + PRIMALITY_LIMIT
              + ".",
          ComputationJson.object().put("operation", "p_adic_valuation").put("p", p));
    }
    if (p.compareTo(BigInteger.TWO) < 0 || !millerRabin(p).prime) {
      throw new IllegalArgumentException("p must be a prime number");
    }
    String expression =
        ComputationJson.requiredText(arguments.get("expression"), "expression");
    ExactExpression parsed = ExactExpression.parse(expression, 12);
    ObjectNode rawAssignment =
        arguments.has("assignment")
            ? ComputationJson.requiredObject(arguments.get("assignment"), "assignment")
            : ComputationJson.object();
    Map<String, BigInteger> assignment = new TreeMap<>();
    for (Map.Entry<String, JsonNode> entry : rawAssignment.properties()) {
      assignment.put(
          entry.getKey(),
          ComputationJson.integer(
              entry.getValue(), "assignment['" + entry.getKey() + "']"));
    }
    if (!assignment.keySet().containsAll(parsed.variables())) {
      java.util.Set<String> missing = new java.util.TreeSet<>(parsed.variables());
      missing.removeAll(assignment.keySet());
      throw new IllegalArgumentException(
          "assignment must cover every expression variable; missing: "
              + String.join(", ", missing));
    }
    BigInteger value = parsed.evaluateInteger(assignment);
    if (value.signum() == 0) {
      return new HandlerEvidence(
          ExperimentOutcome.INCONCLUSIVE,
          EvidenceStrength.HEURISTIC,
          ComputationJson.object()
              .put("operation", "p_adic_valuation")
              .put("p", p)
              .put("value", 0),
          null,
          null,
          true,
          0,
          false,
          List.of(
              "The expression evaluates to 0, whose p-adic valuation is infinite; no finite certificate is produced."),
          null);
    }
    int valuation = 0;
    BigInteger remainder = value.abs();
    while (remainder.mod(p).signum() == 0) {
      remainder = remainder.divide(p);
      valuation++;
    }
    if (!value.mod(p.pow(valuation)).equals(BigInteger.ZERO)
        || value.mod(p.pow(valuation + 1)).equals(BigInteger.ZERO)) {
      throw new IllegalStateException(
          "the p-adic valuation failed its independent recheck");
    }
    String statement =
        "v_" + p + "(" + expression + " at " + assignment + ") = " + valuation;
    ObjectNode certificate =
        ComputationJson.object()
            .put("operation", "p_adic_valuation")
            .put("p", p)
            .put("expression", expression)
            .put("value", value)
            .put("valuation", valuation)
            .put("statement", statement);
    ObjectNode assignmentNode = certificate.putObject("assignment");
    assignment.forEach(assignmentNode::put);
    JsonNode claimed = arguments.get("claimed");
    if (claimed != null && !claimed.isNull()) {
      BigInteger claimedValue = ComputationJson.integer(claimed, "claimed");
      if (!claimedValue.equals(BigInteger.valueOf(valuation))) {
        certificate.put("claimed_valuation", claimedValue);
        return refuted(statement, certificate);
      }
      certificate.put("claimed_valuation", claimedValue);
    }
    return certified(
        statement,
        certificate,
        1,
        "Divisibility by p^valuation and non-divisibility by the next power were independently rechecked.");
  }

  private static HandlerEvidence primitiveRoot(ObjectNode arguments) {
    BigInteger n = ComputationJson.integer(arguments.get("n"), "n");
    if (n.compareTo(BigInteger.TWO) < 0) {
      throw new IllegalArgumentException("primitive_root requires n >= 2");
    }
    if (n.compareTo(ORDER_MODULUS_LIMIT) > 0) {
      return guarded(
          "The modulus "
              + n
              + " exceeds the exact primitive-root guard bound "
              + ORDER_MODULUS_LIMIT
              + ".",
          ComputationJson.object().put("operation", "primitive_root").put("n", n));
    }
    BigInteger phi = totient(n);
    JsonNode claimed = arguments.get("claimed");
    if (claimed != null && !claimed.isNull() && !claimed.isBoolean()) {
      BigInteger candidate = ComputationJson.integer(claimed, "claimed");
      boolean root = candidate.gcd(n).equals(BigInteger.ONE) && verifyOrder(candidate, n, phi);
      String statement =
          candidate + (root ? " is " : " is not ") + "a primitive root modulo " + n;
      ObjectNode certificate =
          ComputationJson.object()
              .put("operation", "primitive_root")
              .put("n", n)
              .put("claimed_root", candidate)
              .put("totient", phi)
              .put("statement", statement);
      return root
          ? certified(
              statement,
              certificate,
              1,
              "The claimed root's order was independently rechecked against the totient.")
          : refuted(statement, certificate);
    }
    boolean structureAllows = primitiveRootStructureAllows(n);
    if (!structureAllows) {
      String statement = "no primitive root exists modulo " + n;
      ObjectNode certificate =
          ComputationJson.object()
              .put("operation", "primitive_root")
              .put("n", n)
              .put("exists", false)
              .put("statement", statement);
      if (claimed != null && claimed.isBoolean() && claimed.booleanValue()) {
        return refuted(statement, certificate);
      }
      return certified(
          statement,
          certificate,
          1,
          "Nonexistence was independently checked against the exact primitive-root structure theorem.");
    }
    BigInteger root = findPrimitiveRoot(n, phi);
    if (root == null) {
      return guarded(
          "No primitive root was found within the deterministic candidate guard.",
          ComputationJson.object().put("operation", "primitive_root").put("n", n));
    }
    String statement = root + " is a primitive root modulo " + n;
    ObjectNode certificate =
        ComputationJson.object()
            .put("operation", "primitive_root")
            .put("n", n)
            .put("exists", true)
            .put("primitive_root", root)
            .put("totient", phi)
            .put("statement", statement);
    if (claimed != null && claimed.isBoolean() && !claimed.booleanValue()) {
      return refuted(statement, certificate);
    }
    return certified(
        statement,
        certificate,
        1,
        "The root's order was independently rechecked against the totient.");
  }

  private static HandlerEvidence isPrime(ObjectNode arguments) {
    BigInteger n = ComputationJson.integer(arguments.get("n"), "n");
    if (n.compareTo(PRIMALITY_LIMIT) > 0) {
      return guarded(
          "The integer "
              + n
              + " exceeds the primality guard bound "
              + PRIMALITY_LIMIT
              + ".",
          ComputationJson.object().put("operation", "is_prime").put("n", n));
    }
    PrimeResult result = millerRabin(n);
    String statement = n + (result.prime ? " is prime" : " is not prime");
    ObjectNode certificate =
        ComputationJson.object()
            .put("operation", "is_prime")
            .put("n", n)
            .put("is_prime", result.prime)
            .put("statement", statement);
    if (result.witness != null) {
      certificate.put("compositeness_witness_base", result.witness);
    }
    JsonNode claimed = arguments.get("claimed");
    if (claimed != null && !claimed.isNull()) {
      if (!claimed.isBoolean()) {
        throw new IllegalArgumentException(
            "claimed must be a boolean for is_prime");
      }
      if (claimed.booleanValue() != result.prime) {
        certificate.put("claimed", claimed.booleanValue());
        return refuted(statement, certificate);
      }
      certificate.put("claimed", claimed.booleanValue());
    }
    return certified(
        statement,
        certificate,
        1,
        "The verdict was independently replayed with the deterministic Miller-Rabin base set.");
  }

  private static HandlerEvidence factorization(ObjectNode arguments) {
    BigInteger n = ComputationJson.integer(arguments.get("n"), "n");
    if (n.compareTo(BigInteger.TWO) < 0) {
      throw new IllegalArgumentException("factorization requires n >= 2");
    }
    if (n.compareTo(FACTORIZATION_LIMIT) >= 0) {
      return guarded(
          "The integer "
              + n
              + " is not below the factorization guard bound "
              + FACTORIZATION_LIMIT
              + ".",
          ComputationJson.object().put("operation", "factorization").put("n", n));
    }
    Map<BigInteger, Integer> factors = factor(n);
    BigInteger product = BigInteger.ONE;
    for (Map.Entry<BigInteger, Integer> entry : factors.entrySet()) {
      if (!millerRabin(entry.getKey()).prime || entry.getValue() < 1) {
        throw new IllegalStateException(
            "the factorization failed its independent primality recheck");
      }
      product = product.multiply(entry.getKey().pow(entry.getValue()));
    }
    if (!product.equals(n)) {
      throw new IllegalStateException(
          "the factorization failed its independent product recheck");
    }
    String statement =
        n
            + " = "
            + factors.entrySet().stream()
                .map(
                    entry ->
                        entry.getValue() == 1
                            ? entry.getKey().toString()
                            : entry.getKey() + "^" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(" * "));
    ObjectNode certificate =
        ComputationJson.object()
            .put("operation", "factorization")
            .put("n", n)
            .put("statement", statement);
    ObjectNode factorNode = certificate.putObject("factors");
    factors.forEach((prime, exponent) -> factorNode.put(prime.toString(), exponent));
    JsonNode claimed = arguments.get("claimed");
    if (claimed != null && !claimed.isNull()) {
      ObjectNode claimedNode =
          ComputationJson.requiredObject(
              claimed, "claimed factorization");
      Map<BigInteger, Integer> claimedFactors = new TreeMap<>();
      for (Map.Entry<String, JsonNode> entry : claimedNode.properties()) {
        BigInteger prime;
        try {
          prime = new BigInteger(entry.getKey());
        } catch (NumberFormatException exception) {
          throw new IllegalArgumentException("claimed prime must be an integer", exception);
        }
        claimedFactors.put(
            prime,
            ComputationJson.integer(entry.getValue(), "claimed exponent").intValueExact());
      }
      if (!claimedFactors.equals(factors)) {
        ObjectNode claimedPayload = certificate.putObject("claimed_factors");
        claimedFactors.forEach(
            (prime, exponent) -> claimedPayload.put(prime.toString(), exponent));
        return refuted(statement, certificate);
      }
      certificate.put("claimed_matches", true);
    }
    return certified(
        statement,
        certificate,
        factors.size(),
        "The product of prime powers and each prime's primality were independently rechecked.");
  }

  private static HandlerEvidence certified(
      String statement, ObjectNode certificate, int casesChecked, String replayNote) {
    return new HandlerEvidence(
        ExperimentOutcome.CERTIFIED,
        EvidenceStrength.FORMAL_CERTIFICATE,
        ComputationJson.object(),
        null,
        certificate,
        true,
        casesChecked,
        true,
        List.of(
            "Exact finite computation: " + statement + ".",
            replayNote,
            FINITE_ONLY),
        null);
  }

  private static HandlerEvidence refuted(String statement, ObjectNode counterexample) {
    return new HandlerEvidence(
        ExperimentOutcome.COUNTEREXAMPLE_FOUND,
        EvidenceStrength.COUNTEREXAMPLE,
        ComputationJson.object(),
        counterexample,
        null,
        true,
        1,
        true,
        List.of(
            "Exact finite computation refutes the claimed value: " + statement + ".",
            FINITE_ONLY),
        null);
  }

  private static HandlerEvidence guarded(String reason, ObjectNode scope) {
    return new HandlerEvidence(
        ExperimentOutcome.INCONCLUSIVE,
        EvidenceStrength.HEURISTIC,
        scope,
        null,
        null,
        false,
        0,
        false,
        List.of(
            reason
                + " The request is refused rather than risking an unverifiable long computation."),
        null);
  }

  private static PrimeResult millerRabin(BigInteger n) {
    if (n.compareTo(BigInteger.TWO) < 0) {
      return new PrimeResult(false, null);
    }
    for (long rawBase : MILLER_RABIN_BASES) {
      BigInteger base = BigInteger.valueOf(rawBase);
      if (n.equals(base)) {
        return new PrimeResult(true, null);
      }
      if (n.mod(base).equals(BigInteger.ZERO)) {
        return new PrimeResult(false, base);
      }
    }
    BigInteger d = n.subtract(BigInteger.ONE);
    int powersOfTwo = d.getLowestSetBit();
    d = d.shiftRight(powersOfTwo);
    for (long rawBase : MILLER_RABIN_BASES) {
      BigInteger base = BigInteger.valueOf(rawBase);
      BigInteger value = base.modPow(d, n);
      if (value.equals(BigInteger.ONE) || value.equals(n.subtract(BigInteger.ONE))) {
        continue;
      }
      boolean probable = false;
      for (int index = 1; index < powersOfTwo; index++) {
        value = value.multiply(value).mod(n);
        if (value.equals(n.subtract(BigInteger.ONE))) {
          probable = true;
          break;
        }
      }
      if (!probable) {
        return new PrimeResult(false, base);
      }
    }
    return new PrimeResult(true, null);
  }

  private static Map<BigInteger, Integer> factor(BigInteger value) {
    if (value.compareTo(FACTORIZATION_LIMIT) >= 0) {
      throw new IllegalArgumentException(
          "internal factorization input exceeds deterministic guard");
    }
    Map<BigInteger, Integer> factors = new TreeMap<>();
    BigInteger remaining = value;
    BigInteger divisor = BigInteger.TWO;
    while (divisor.multiply(divisor).compareTo(remaining) <= 0) {
      int exponent = 0;
      while (remaining.mod(divisor).signum() == 0) {
        remaining = remaining.divide(divisor);
        exponent++;
      }
      if (exponent > 0) {
        factors.put(divisor, exponent);
      }
      divisor =
          divisor.equals(BigInteger.TWO)
              ? BigInteger.valueOf(3)
              : divisor.add(BigInteger.TWO);
    }
    if (remaining.compareTo(BigInteger.ONE) > 0) {
      factors.merge(remaining, 1, Integer::sum);
    }
    return Map.copyOf(factors);
  }

  private static BigInteger totient(BigInteger n) {
    BigInteger result = n;
    for (BigInteger prime : factor(n).keySet()) {
      result = result.divide(prime).multiply(prime.subtract(BigInteger.ONE));
    }
    return result;
  }

  private static boolean verifyOrder(BigInteger a, BigInteger n, BigInteger order) {
    if (order.signum() <= 0 || !a.mod(n).modPow(order, n).equals(BigInteger.ONE)) {
      return false;
    }
    for (BigInteger prime : factor(order).keySet()) {
      if (a.mod(n).modPow(order.divide(prime), n).equals(BigInteger.ONE)) {
        return false;
      }
    }
    return true;
  }

  private static boolean primitiveRootStructureAllows(BigInteger n) {
    if (n.equals(BigInteger.TWO) || n.equals(BigInteger.valueOf(4))) {
      return true;
    }
    if (n.mod(BigInteger.valueOf(4)).signum() == 0) {
      return false;
    }
    BigInteger remainder =
        n.mod(BigInteger.TWO).signum() == 0 ? n.divide(BigInteger.TWO) : n;
    Map<BigInteger, Integer> factors = factor(remainder);
    return factors.size() == 1 && factors.keySet().iterator().next().testBit(0);
  }

  private static BigInteger findPrimitiveRoot(BigInteger n, BigInteger phi) {
    if (n.equals(BigInteger.TWO)) {
      return BigInteger.ONE;
    }
    BigInteger maximum = n.subtract(BigInteger.ONE).min(BigInteger.valueOf(1_000_000));
    for (BigInteger candidate = BigInteger.TWO;
        candidate.compareTo(maximum) <= 0;
        candidate = candidate.add(BigInteger.ONE)) {
      if (candidate.gcd(n).equals(BigInteger.ONE) && verifyOrder(candidate, n, phi)) {
        return candidate;
      }
    }
    return null;
  }

  private static ObjectNode baseCrtCertificate(
      List<BigInteger> residues, List<BigInteger> moduli) {
    ObjectNode result = ComputationJson.object().put("operation", "crt");
    ArrayNode residueArray = result.putArray("residues");
    residues.forEach(residueArray::add);
    ArrayNode modulusArray = result.putArray("moduli");
    moduli.forEach(modulusArray::add);
    return result;
  }

  private static ObjectNode findCrtWitness(
      List<BigInteger> residues, List<BigInteger> moduli) {
    for (int left = 0; left < moduli.size(); left++) {
      for (int right = left + 1; right < moduli.size(); right++) {
        BigInteger gcd = moduli.get(left).gcd(moduli.get(right));
        if (!residues.get(left).subtract(residues.get(right)).mod(gcd).equals(BigInteger.ZERO)) {
          ObjectNode witness = ComputationJson.object().put("gcd", gcd);
          witness.putArray("index_pair").add(left).add(right);
          witness.putArray("moduli").add(moduli.get(left)).add(moduli.get(right));
          witness.putArray("residues").add(residues.get(left)).add(residues.get(right));
          return witness;
        }
      }
    }
    throw new IllegalStateException("CRT inconsistency had no pairwise witness");
  }

  private record PrimeResult(boolean prime, BigInteger witness) {}
}
