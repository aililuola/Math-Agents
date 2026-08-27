package io.github.aililuola.mathproofmesh.computation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Pre-execution schemas for every registered computation method. */
public final class ContractsFunctions {
  private static final Set<String> RELATIONS = Set.of("eq", "ne", "le", "lt", "ge", "gt");
  private static final Set<String> GREEDY_RULES =
      Set.of(
          "avoid_forbidden_differences",
          "avoid_three_term_arithmetic_progression",
          "coprime_to_all",
          "gcd_overlap_all_prior");
  private static final Set<String> NUMBER_THEORY_OPERATIONS =
      Set.of(
          "multiplicative_order",
          "crt",
          "p_adic_valuation",
          "primitive_root",
          "is_prime",
          "factorization");
  private static final Set<String> REAL_DOMAIN_KEYS =
      Set.of(
          "min",
          "max",
          "min_exclusive",
          "max_exclusive",
          "positive",
          "nonnegative",
          "nonzero");
  private ContractsFunctions() {}

  public static ContractMode computationContractMode(ExperimentSpec spec) {
    return spec.purpose() == ComputationPurpose.DISCOVER_PATTERN
        ? ContractMode.DISCOVERY
        : ContractMode.ASSERTION;
  }

  /**
   * Safely converts an unclaimed greedy generator into discovery mode instead of treating its
   * generated values as an asserted theorem.
   */
  public static Normalization normalizeExploratoryContract(ExperimentSpec spec) {
    if (spec.method() != ComputationMethod.BOUNDED_GREEDY_SEQUENCE
        || spec.arguments().has("claimed_values")
        || spec.purpose() == ComputationPurpose.DISCOVER_PATTERN) {
      return new Normalization(spec, false);
    }
    ExperimentSpec normalized =
        new ExperimentSpec(
            spec.arguments(),
            spec.assumptions(),
            true,
            spec.decisionIfConfirmed(),
            spec.decisionIfRefuted(),
            spec.domains(),
            spec.exactArithmetic(),
            null,
            spec.experimentId(),
            spec.maxCases(),
            spec.method(),
            spec.noncomputationalAlternative(),
            spec.parentCheckpointId(),
            spec.pathId(),
            ComputationPurpose.DISCOVER_PATTERN,
            spec.reasoningBasis(),
            null,
            spec.requestedBy(),
            ComputationJson.object(),
            spec.seed(),
            spec.targetClaim(),
            spec.typedToolGap(),
            spec.whyComputationIsNeeded());
    return new Normalization(normalized, true);
  }

  public static List<String> validateExperimentContract(ExperimentSpec spec) {
    List<String> issues = new ArrayList<>();
    RegisteredComputationCapability capability =
        ComputationCapabilityRegistry.javaOnly().capability(spec.method());
    Set<String> allowed = capability.allowedArguments();
    Set<String> unknown = new TreeSet<>();
    spec.arguments().properties().forEach(entry -> {
      if (!allowed.contains(entry.getKey())) {
        unknown.add(entry.getKey());
      }
    });
    if (!unknown.isEmpty()) {
      issues.add("unsupported arguments: " + String.join(", ", unknown));
    }
    if (!capability.acceptsDomains() && !spec.domains().isEmpty()) {
      issues.add(spec.method().value() + " does not accept domains");
    }

    ObjectNode arguments = spec.arguments();
    switch (spec.method()) {
      case SYMPY_SIMPLIFY, POLYNOMIAL_FACTOR ->
          requireText(arguments, "expression", issues, "");
      case SYMPY_EQUIVALENT -> {
        requireText(arguments, "lhs", issues, "");
        requireText(arguments, "rhs", issues, "");
      }
      case NUMERIC_COUNTEREXAMPLE -> validateNumeric(arguments, issues);
      case MODULAR_EXHAUSTIVE -> validateModular(spec, issues);
      case BOUNDED_INTEGER_SEARCH -> validateIntegerSearch(spec, issues);
      case GRAPH_CERTIFICATE -> validateGraph(arguments, issues);
      case RECURRENCE_CHECK -> validateRecurrence(arguments, issues);
      case BOUNDED_GREEDY_SEQUENCE -> validateGreedy(spec, issues);
      case CANDIDATE_PERIOD_CHECK -> validatePeriod(arguments, issues);
      case EXACT_GEOMETRY -> validateGeometry(arguments, issues);
      case REAL_INEQUALITY -> validateRealInequality(spec, issues);
      case NUMBER_THEORY_CHECK -> validateNumberTheory(arguments, issues);
      case EXACT_LINEAR_ALGEBRA -> validateExactLinearAlgebra(arguments, issues);
      case FINITE_SET_MAP_CHECK -> validateFiniteSetMap(arguments, issues);
      case HYPERGRAPH_TRANSVERSAL -> validateHypergraph(arguments, issues);
      case SANDBOXED_PYTHON -> {
        if (!arguments.path("input").isObject()) {
          issues.add("input must be a JSON object");
        }
      }
      case LEAN_CHECK -> requireText(arguments, "source", issues, "");
    }
    return List.copyOf(issues);
  }

  public static List<ObjectNode> experimentToolCatalog(Set<String> allowedTools) {
    List<ObjectNode> catalog =
        new ArrayList<>(ComputationCapabilityRegistry.javaOnly().promptCatalog(allowedTools));
    for (ObjectNode item : catalog) {
      ComputationMethod method = ComputationMethod.fromValue(item.path("method").asText());
      if (method == ComputationMethod.SANDBOXED_PYTHON) {
        item.putArray("optional_arguments");
        item.put("domains", "Must be empty; put all bounded input under arguments.input.");
      }
      if (method == ComputationMethod.BOUNDED_GREEDY_SEQUENCE) {
        ArrayNode rules = item.putArray("allowed_rules");
        new TreeSet<>(GREEDY_RULES).forEach(rules::add);
        item.put(
            "unknown_alias_policy",
            "Aliases such as a1, max_n, and max_terms are rejected.");
      }
    }
    return List.copyOf(catalog);
  }

  private static void validateExactLinearAlgebra(
      ObjectNode arguments, List<String> issues) {
    requireText(arguments, "operation", issues, "");
    String operation = arguments.path("operation").asText();
    if (!Set.of("determinant", "rank", "solve", "nullspace", "span_membership")
        .contains(operation)) {
      issues.add("unsupported exact linear algebra operation");
    }
    JsonNode matrix = arguments.get("matrix");
    if (matrix == null || !matrix.isArray() || matrix.isEmpty()) {
      issues.add("matrix must be a non-empty finite rational matrix");
    }
    if (operation.equals("solve")
        && (arguments.get("rhs") == null || !arguments.get("rhs").isArray())) {
      issues.add("solve requires an rhs vector");
    }
    if (operation.equals("span_membership")
        && (arguments.get("vector") == null || !arguments.get("vector").isArray())) {
      issues.add("span_membership requires a vector");
    }
  }

  private static void validateFiniteSetMap(
      ObjectNode arguments, List<String> issues) {
    requireText(arguments, "operation", issues, "");
    if (!Set.of(
            "injective",
            "surjective",
            "bijective",
            "image",
            "preimage",
            "cardinality_equality")
        .contains(arguments.path("operation").asText())) {
      issues.add("unsupported finite set-map operation");
    }
    if (!arguments.path("domain").isArray() || !arguments.path("codomain").isArray()) {
      issues.add("domain and codomain must be finite lists");
    }
    if (!arguments.path("mapping").isObject()) {
      issues.add("mapping must be a total finite object");
    }
    if (arguments.path("operation").asText().equals("preimage")) {
      requireText(arguments, "target", issues, "");
    }
  }

  private static void validateHypergraph(ObjectNode arguments, List<String> issues) {
    requireText(arguments, "operation", issues, "");
    if (!Set.of(
            "is_hitting_set", "is_minimal_hitting_set", "enumerate_minimal_transversals")
        .contains(arguments.path("operation").asText())) {
      issues.add("unsupported hypergraph transversal operation");
    }
    if (!arguments.path("vertices").isArray() || !arguments.path("edges").isArray()) {
      issues.add("vertices and edges must be finite lists");
    }
    if (!arguments.path("operation").asText().equals("enumerate_minimal_transversals")
        && !arguments.path("candidate").isArray()) {
      issues.add("hitting-set checks require a finite candidate");
    }
  }

  private static void validateGreedy(ExperimentSpec spec, List<String> issues) {
    ObjectNode arguments = spec.arguments();
    JsonNode initial = arguments.get("initial_values");
    if (initial == null || !initial.isArray() || initial.isEmpty()) {
      issues.add("initial_values must be a non-empty list");
    } else if (!allExactIntegers(initial)) {
      issues.add("initial_values must contain only exact integers");
    }
    JsonNode length = arguments.get("length");
    if (length == null) {
      issues.add("length is required");
    } else if (!isExactInteger(length)) {
      issues.add("length must be an integer");
    } else if (initial != null
        && initial.isArray()
        && integer(length).compareTo(BigInteger.valueOf(initial.size())) < 0) {
      issues.add("length must be at least the number of initial_values");
    }
    String rule = arguments.path("rule").asText("");
    if (!GREEDY_RULES.contains(rule)) {
      issues.add("rule must be one of: " + String.join(", ", new TreeSet<>(GREEDY_RULES)));
    }
    if (rule.equals("avoid_forbidden_differences")) {
      JsonNode forbidden = arguments.get("forbidden_differences");
      if (forbidden == null || !forbidden.isArray() || forbidden.isEmpty()) {
        issues.add("forbidden_differences must be a non-empty list for this rule");
      }
    }
    JsonNode claimed = arguments.get("claimed_values");
    if (claimed != null && !claimed.isNull() && !claimed.isArray()) {
      issues.add("claimed_values must be a list when supplied");
    }
    if (computationContractMode(spec) == ContractMode.ASSERTION
        && (claimed == null || claimed.isNull())) {
      issues.add(
          "assertion-mode bounded_greedy_sequence requires claimed_values; use purpose=discover_pattern for exploratory prefix generation");
    }
    for (String name : List.of("candidate_min", "candidate_max")) {
      if (arguments.has(name) && !isExactInteger(arguments.get(name))) {
        issues.add(name + " must be an integer");
      }
    }
    if (isExactInteger(arguments.path("candidate_min"))
        && isExactInteger(arguments.path("candidate_max"))
        && integer(arguments.get("candidate_max"))
                .compareTo(integer(arguments.get("candidate_min")))
            < 0) {
      issues.add("candidate_max must not be below candidate_min");
    }
    if (arguments.has("strictly_increasing")
        && !arguments.get("strictly_increasing").isBoolean()) {
      issues.add("strictly_increasing must be a boolean");
    }
  }

  private static void validateNumeric(ObjectNode arguments, List<String> issues) {
    requireText(arguments, "lhs", issues, "");
    requireText(arguments, "rhs", issues, "");
    validateRelation(arguments.path("relation").asText("eq"), "relation", issues);
    validateVariables(arguments.get("variables"), "variables", issues);
    JsonNode ranges = arguments.get("ranges");
    if (ranges != null && !ranges.isNull()) {
      if (!ranges.isObject()) {
        issues.add("ranges must map variable names to [lower, upper]");
      } else {
        ranges.properties().forEach(entry -> {
          if (!entry.getValue().isArray() || entry.getValue().size() != 2) {
            issues.add("range for '" + entry.getKey() + "' must be [lower, upper]");
          }
        });
      }
    }
    if (arguments.has("samples")
        && (!isExactInteger(arguments.get("samples"))
            || integer(arguments.get("samples")).signum() <= 0)) {
      issues.add("samples must be a positive integer");
    }
    if (arguments.has("tolerance")) {
      JsonNode tolerance = arguments.get("tolerance");
      if (tolerance.isBoolean() || !tolerance.isNumber() || tolerance.decimalValue().signum() < 0) {
        issues.add("tolerance must be a nonnegative number");
      }
    }
  }

  private static void validateModular(ExperimentSpec spec, List<String> issues) {
    ObjectNode arguments = spec.arguments();
    requireText(arguments, "lhs", issues, "");
    if (!isExactInteger(arguments.get("modulus"))) {
      issues.add("modulus must be an integer");
    } else if (integer(arguments.get("modulus")).compareTo(BigInteger.TWO) < 0) {
      issues.add("modulus must be at least 2");
    }
    String relation = arguments.path("relation").asText("eq");
    if (!relation.equals("eq") && !relation.equals("ne")) {
      issues.add("relation must be eq or ne");
    }
    validateVariables(arguments.get("variables"), "variables", issues);
    spec.domains().properties().forEach(entry -> {
      JsonNode domain = entry.getValue();
      if (domain.isArray()) {
        if (domain.isEmpty()) {
          issues.add("domain for '" + entry.getKey() + "' cannot be empty");
        }
      } else if (!domain.isObject()) {
        issues.add("domain for '" + entry.getKey() + "' must be a mapping or list");
      } else if (domain.has("values")) {
        if (!domain.get("values").isArray() || domain.get("values").isEmpty()) {
          issues.add("domain values for '" + entry.getKey() + "' must be a non-empty list");
        }
      } else if ((domain.has("min") && !isExactInteger(domain.get("min")))
          || (domain.has("max") && !isExactInteger(domain.get("max")))) {
        issues.add("domain min/max for '" + entry.getKey() + "' must be integers");
      }
    });
  }

  private static void validateIntegerSearch(ExperimentSpec spec, List<String> issues) {
    if (spec.domains().isEmpty()) {
      issues.add("bounded_integer_search requires finite variable domains");
    }
    spec.domains().properties().forEach(entry -> {
      JsonNode domain = entry.getValue();
      if (!domain.isObject() || !domain.has("min") || !domain.has("max")) {
        issues.add("domain for '" + entry.getKey() + "' requires integer min and max");
      } else if (!isExactInteger(domain.get("min")) || !isExactInteger(domain.get("max"))) {
        issues.add("domain min/max for '" + entry.getKey() + "' must be integers");
      } else if (integer(domain.get("max")).compareTo(integer(domain.get("min"))) < 0) {
        issues.add("domain for '" + entry.getKey() + "' has max < min");
      }
    });
    validateRelationMapping(spec.arguments().get("target"), "target", issues);
    JsonNode constraints = spec.arguments().path("constraints");
    if (!constraints.isMissingNode() && !constraints.isArray()) {
      issues.add("constraints must be a list of typed relation mappings");
    } else if (constraints.isArray()) {
      for (int index = 0; index < constraints.size(); index++) {
        validateRelationMapping(constraints.get(index), "constraints[" + index + "]", issues);
      }
    }
  }

  private static void validateGraph(ObjectNode arguments, List<String> issues) {
    JsonNode graph = arguments.get("graph");
    if (graph == null || !graph.isObject()) {
      issues.add("graph must be a typed mapping");
    } else {
      JsonNode nodes = graph.get("nodes");
      if (nodes == null || !nodes.isArray()) {
        issues.add("graph.nodes must be a list");
      } else {
        Set<String> names = new HashSet<>();
        nodes.forEach(node -> names.add(node.asText()));
        if (names.size() != nodes.size()) {
          issues.add("graph node identifiers must be unique");
        }
      }
      JsonNode edges = graph.get("edges");
      if (edges == null
          || !edges.isArray()
          || java.util.stream.StreamSupport.stream(edges.spliterator(), false)
              .anyMatch(edge -> !edge.isArray() || edge.size() != 2)) {
        issues.add("graph.edges must contain two-endpoint pairs");
      }
    }
    if (!Set.of("proper_coloring", "path", "cycle", "matching", "connected")
        .contains(arguments.path("property").asText(""))) {
      issues.add(
          "property must be proper_coloring, path, cycle, matching, or connected");
    }
    if (!arguments.path("certificate").isObject()) {
      issues.add("certificate must be a typed mapping");
    }
  }

  private static void validateRecurrence(ObjectNode arguments, List<String> issues) {
    JsonNode initial = arguments.get("initial_values");
    JsonNode coefficients = arguments.get("coefficients");
    if (initial == null || !initial.isArray() || initial.isEmpty()) {
      issues.add("initial_values must be a non-empty list");
    }
    if (coefficients == null || !coefficients.isArray() || coefficients.isEmpty()) {
      issues.add("coefficients must be a non-empty list");
    }
    if (initial != null
        && initial.isArray()
        && coefficients != null
        && coefficients.isArray()
        && !coefficients.isEmpty()
        && initial.size() < coefficients.size()) {
      issues.add("initial_values must contain at least recurrence order values");
    }
    if (!isExactInteger(arguments.get("end_n"))) {
      issues.add("end_n must be an integer");
    }
    if (arguments.has("start_n") && !isExactInteger(arguments.get("start_n"))) {
      issues.add("start_n must be an integer");
    }
    if (isExactInteger(arguments.get("end_n"))
        && (!arguments.has("start_n") || isExactInteger(arguments.get("start_n")))) {
      BigInteger start =
          arguments.has("start_n") ? integer(arguments.get("start_n")) : BigInteger.ZERO;
      if (integer(arguments.get("end_n")).compareTo(start) < 0) {
        issues.add("end_n must not be below start_n");
      }
    }
  }

  private static void validatePeriod(ObjectNode arguments, List<String> issues) {
    JsonNode values = arguments.get("values");
    if (values == null || !values.isArray() || values.isEmpty()) {
      issues.add("values must be a non-empty list");
    }
    if (!isExactInteger(arguments.get("candidate_period"))) {
      issues.add("candidate_period must be an integer");
    } else if (integer(arguments.get("candidate_period")).signum() <= 0) {
      issues.add("candidate_period must be positive");
    }
    if (arguments.has("start_index")
        && (!isExactInteger(arguments.get("start_index"))
            || integer(arguments.get("start_index")).signum() < 0)) {
      issues.add("start_index must be a nonnegative integer");
    }
    if (values != null
        && values.isArray()
        && !values.isEmpty()
        && isExactInteger(arguments.get("candidate_period"))
        && (!arguments.has("start_index") || isExactInteger(arguments.get("start_index")))) {
      BigInteger start =
          arguments.has("start_index") ? integer(arguments.get("start_index")) : BigInteger.ZERO;
      if (start.add(integer(arguments.get("candidate_period")))
              .compareTo(BigInteger.valueOf(values.size()))
          >= 0) {
        issues.add("candidate_period/start_index leave no comparable pair");
      }
    }
  }

  private static void validateGeometry(ObjectNode arguments, List<String> issues) {
    JsonNode points = arguments.get("points");
    if (points == null || !points.isObject() || points.isEmpty()) {
      issues.add("points must map names to exact coordinate pairs");
    } else if (java.util.stream.StreamSupport.stream(points.spliterator(), false)
        .anyMatch(point -> !point.isArray() || point.size() != 2)) {
      issues.add("each point must contain exactly two coordinates");
    }
    JsonNode assertion = arguments.get("assertion");
    if (assertion == null || !assertion.isObject()) {
      issues.add("assertion must be a typed mapping");
      return;
    }
    Map<String, Integer> counts =
        Map.of(
            "collinear", 3,
            "orientation", 3,
            "equal_distance", 4,
            "point_on_segment", 3,
            "concyclic", 4,
            "parallel", 4,
            "perpendicular", 4,
            "equal_angle", 6);
    String kind = assertion.path("kind").asText("");
    Integer count = counts.get(kind);
    if (count == null) {
      issues.add(
          "assertion.kind must be collinear, orientation, equal_distance, point_on_segment, concyclic, parallel, perpendicular, or equal_angle");
      return;
    }
    JsonNode names = assertion.get("points");
    if (names == null || !names.isArray() || names.size() != count) {
      issues.add(kind + " requires exactly " + count + " point names");
    } else if (points != null
        && points.isObject()
        && java.util.stream.StreamSupport.stream(names.spliterator(), false)
            .anyMatch(name -> !points.has(name.asText()))) {
      issues.add("geometry assertion references an undeclared point");
    }
  }

  private static void validateRealInequality(ExperimentSpec spec, List<String> issues) {
    ObjectNode arguments = spec.arguments();
    requireText(arguments, "lhs", issues, "");
    if (arguments.has("rhs")) {
      requireText(arguments, "rhs", issues, "");
    }
    validateRelation(arguments.path("relation").asText("ge"), "relation", issues);
    validateVariables(arguments.get("variables"), "variables", issues);
    if (arguments.has("max_runtime_ms")) {
      JsonNode timeout = arguments.get("max_runtime_ms");
      if (!timeout.isIntegralNumber() || timeout.isBoolean() || timeout.longValue() < 1) {
        issues.add("max_runtime_ms must be an integer >= 1");
      }
    }
    spec.domains().properties().forEach(entry -> {
      JsonNode domain = entry.getValue();
      if (!domain.isObject()) {
        issues.add("domain for '" + entry.getKey() + "' must be a mapping");
        return;
      }
      Set<String> unknown = new TreeSet<>();
      domain.properties().forEach(field -> {
        if (!REAL_DOMAIN_KEYS.contains(field.getKey())) {
          unknown.add(field.getKey());
        }
      });
      if (!unknown.isEmpty()) {
        issues.add(
            "domain for '"
                + entry.getKey()
                + "' has unsupported keys: "
                + String.join(", ", unknown));
      }
      for (String key : List.of("min", "max")) {
        if (domain.has(key) && !isExactRational(domain.get(key))) {
          issues.add(
              "domain " + key + " for '" + entry.getKey() + "' must be an exact rational number");
        }
      }
      for (String key :
          List.of(
              "min_exclusive",
              "max_exclusive",
              "positive",
              "nonnegative",
              "nonzero")) {
        if (domain.has(key) && !domain.get(key).isBoolean()) {
          issues.add("domain " + key + " for '" + entry.getKey() + "' must be a boolean");
        }
      }
    });
  }

  private static void validateNumberTheory(ObjectNode arguments, List<String> issues) {
    String operation = arguments.path("operation").asText("");
    if (!NUMBER_THEORY_OPERATIONS.contains(operation)) {
      issues.add(
          "operation must be one of: "
              + String.join(", ", new TreeSet<>(NUMBER_THEORY_OPERATIONS)));
      return;
    }
    switch (operation) {
      case "multiplicative_order" -> {
        exactInteger(arguments, "a", issues);
        exactInteger(arguments, "n", issues);
      }
      case "crt" -> {
        JsonNode residues = arguments.get("residues");
        JsonNode moduli = arguments.get("moduli");
        validateIntegerList(residues, "residues", issues);
        validateIntegerList(moduli, "moduli", issues);
        if (residues != null
            && residues.isArray()
            && !residues.isEmpty()
            && moduli != null
            && moduli.isArray()
            && !moduli.isEmpty()
            && residues.size() != moduli.size()) {
          issues.add("residues and moduli must have the same length");
        }
      }
      case "p_adic_valuation" -> {
        exactInteger(arguments, "p", issues);
        requireText(arguments, "expression", issues, "");
        JsonNode assignment = arguments.get("assignment");
        if (assignment != null && !assignment.isNull()) {
          if (!assignment.isObject()) {
            issues.add("assignment must map variable names to integers");
          } else if (java.util.stream.StreamSupport.stream(assignment.spliterator(), false)
              .anyMatch(value -> !isExactInteger(value))) {
            issues.add("assignment values must be exact integers");
          }
        }
      }
      case "primitive_root", "is_prime", "factorization" ->
          exactInteger(arguments, "n", issues);
      default -> throw new IllegalStateException("unreachable number-theory validation");
    }
  }

  private static void validateRelationMapping(
      JsonNode value, String label, List<String> issues) {
    if (value == null || !value.isObject()) {
      issues.add(label + " must be a typed relation mapping");
      return;
    }
    requireText((ObjectNode) value, "lhs", issues, label + ".");
    validateRelation(value.path("relation").asText("eq"), label + ".relation", issues);
  }

  private static void validateRelation(String relation, String label, List<String> issues) {
    if (!RELATIONS.contains(relation)) {
      issues.add(label + " must be one of: " + String.join(", ", new TreeSet<>(RELATIONS)));
    }
  }

  private static void validateVariables(
      JsonNode variables, String label, List<String> issues) {
    if (variables == null || variables.isNull()) {
      return;
    }
    if (!variables.isArray()
        || java.util.stream.StreamSupport.stream(variables.spliterator(), false)
            .anyMatch(value -> !value.isTextual() || value.textValue().isBlank())) {
      issues.add(label + " must be a list of non-empty names");
      return;
    }
    Set<String> unique = new HashSet<>();
    variables.forEach(value -> unique.add(value.asText()));
    if (unique.size() != variables.size()) {
      issues.add(label + " must be unique");
    }
  }

  private static void requireText(
      ObjectNode mapping, String name, List<String> issues, String prefix) {
    JsonNode value = mapping.get(name);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      issues.add(prefix + name + " must be a non-empty string");
    }
  }

  private static void exactInteger(
      ObjectNode mapping, String name, List<String> issues) {
    if (!isExactInteger(mapping.get(name))) {
      issues.add(name + " must be an integer");
    }
  }

  private static void validateIntegerList(
      JsonNode values, String label, List<String> issues) {
    if (values == null || !values.isArray() || values.isEmpty()) {
      issues.add(label + " must be a non-empty list of integers");
    } else if (!allExactIntegers(values)) {
      issues.add(label + " must contain only exact integers");
    }
  }

  private static boolean allExactIntegers(JsonNode values) {
    return java.util.stream.StreamSupport.stream(values.spliterator(), false)
        .allMatch(ContractsFunctions::isExactInteger);
  }

  private static boolean isExactInteger(JsonNode value) {
    if (value == null || value.isNull() || value.isBoolean() || value.isFloatingPointNumber()) {
      return false;
    }
    try {
      ExactRational rational = ExactRational.parse(value, "value");
      return rational.denominator().equals(BigInteger.ONE);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static boolean isExactRational(JsonNode value) {
    if (value == null || value.isNull() || value.isBoolean() || value.isFloatingPointNumber()) {
      return false;
    }
    try {
      ExactRational.parse(value, "value");
      return true;
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static BigInteger integer(JsonNode value) {
    return ExactRational.parse(value, "value").toBigIntegerExact("value");
  }

  public enum ContractMode {
    DISCOVERY,
    ASSERTION
  }

  public record Normalization(ExperimentSpec spec, boolean changed) {}
}
