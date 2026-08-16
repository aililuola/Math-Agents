package io.github.aililuola.mathproofmesh.computation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ComputationCertificateEnvelope;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ComputationVerificationReceipt;
import io.github.aililuola.mathproofmesh.contract.ComputationVerificationStatus;
import io.github.aililuola.mathproofmesh.contract.ComputationVerifiedAuthority;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Certificate verifier that never calls a capability producer. */
final class IndependentComputationCertificateVerifier
    implements ComputationCertificateVerifier {
  private static final String CREATED_AT = "1970-01-01T00:00:00Z";

  @Override
  public ComputationVerificationReceipt verify(
      ValidatedComputationRequest request,
      ComputationResultArtifact result,
      ComputationCertificateEnvelope certificate) {
    List<String> diagnostics = new ArrayList<>();
    boolean bindingsValid = bindingsValid(request, result, certificate, diagnostics);
    boolean mathematicsValid = bindingsValid && mathematicalCertificateValid(request, result, diagnostics);
    ComputationVerifiedAuthority authority =
        mathematicsValid ? authority(result, request.spec().method()) : ComputationVerifiedAuthority.AUDIT_ONLY;
    boolean ceilingValid = request.capability().authorityCeiling().permits(authority);
    if (!ceilingValid) {
      diagnostics.add("AUTHORITY_CEILING_EXCEEDED");
      mathematicsValid = false;
      authority = ComputationVerifiedAuthority.AUDIT_ONLY;
    }
    ComputationVerificationStatus status =
        mathematicsValid
            ? ComputationVerificationStatus.VALID
            : result.error().contains("BACKEND_UNAVAILABLE")
                ? ComputationVerificationStatus.BACKEND_UNAVAILABLE
                : ComputationVerificationStatus.INVALID;
    if (diagnostics.isEmpty()) {
      diagnostics.add("CERTIFICATE_VERIFIED_INDEPENDENTLY");
    }
    String receiptId =
        "verification-"
            + CanonicalJson.stableHash(
                Map.of(
                    "certificate_hash", certificate.certificateHash(),
                    "verifier_id", request.capability().verifierId(),
                    "verifier_version", request.capability().verifierVersion()));
    return new ComputationVerificationReceipt(
        receiptId,
        certificate.certificateHash(),
        request.capability().verifierId(),
        request.capability().verifierVersion(),
        status,
        mathematicsValid,
        authority,
        certificate.scopeHash(),
        diagnostics,
        CREATED_AT,
        null);
  }

  private static boolean bindingsValid(
      ValidatedComputationRequest request,
      ComputationResultArtifact result,
      ComputationCertificateEnvelope certificate,
      List<String> diagnostics) {
    boolean valid = true;
    valid &= match(certificate.requestHash(), request.spec().requestHash(), "REQUEST_HASH_MISMATCH", diagnostics);
    valid &= match(certificate.executionHash(), request.spec().executionHash(), "EXECUTION_HASH_MISMATCH", diagnostics);
    valid &= match(certificate.capabilityId(), request.capability().capabilityId(), "CAPABILITY_ID_MISMATCH", diagnostics);
    valid &= match(certificate.capabilityVersion(), request.capability().capabilityVersion(), "CAPABILITY_VERSION_MISMATCH", diagnostics);
    valid &= match(certificate.resultHash(), result.artifactHash(), "RESULT_HASH_MISMATCH", diagnostics);
    valid &= match(certificate.scopeHash(), CanonicalJson.stableHash(result.scope()), "SCOPE_HASH_MISMATCH", diagnostics);
    valid &= match(certificate.domainHash(), CanonicalJson.stableHash(request.spec().domains()), "DOMAIN_HASH_MISMATCH", diagnostics);
    valid &= match(certificate.producerId(), request.capability().producerId(), "PRODUCER_ID_MISMATCH", diagnostics);
    valid &= match(certificate.producerVersion(), request.capability().producerVersion(), "PRODUCER_VERSION_MISMATCH", diagnostics);
    ObjectNode expectedWitness =
        result.counterexample() != null
            ? result.counterexample()
            : result.certificate() != null ? result.certificate() : result.scope();
    valid &= match(CanonicalJson.stableHash(certificate.witness()), CanonicalJson.stableHash(expectedWitness), "WITNESS_HASH_MISMATCH", diagnostics);
    return valid;
  }

  private static boolean mathematicalCertificateValid(
      ValidatedComputationRequest request,
      ComputationResultArtifact result,
      List<String> diagnostics) {
    if (result.outcome() == ExperimentOutcome.ERROR
        || result.outcome() == ExperimentOutcome.INCONCLUSIVE) {
      diagnostics.add("NO_MATHEMATICAL_CERTIFICATE");
      return false;
    }
    if (result.outcome() == ExperimentOutcome.NOT_REFUTED) {
      return result.evidenceStrength() == EvidenceStrength.HEURISTIC
          || result.evidenceStrength() == EvidenceStrength.BOUNDED_EVIDENCE;
    }
    ComputationMethod method = request.spec().method();
    boolean valid =
        switch (method) {
          case MODULAR_EXHAUSTIVE ->
              IndependentNativeComputationVerifier.verifyModular(request.spec(), result);
          case BOUNDED_INTEGER_SEARCH ->
              IndependentNativeComputationVerifier.verifyBoundedIntegerSearch(
                  request.spec(), result);
          case RECURRENCE_CHECK ->
              IndependentNativeComputationVerifier.verifyRecurrence(request.spec(), result);
          case BOUNDED_GREEDY_SEQUENCE ->
              IndependentNativeComputationVerifier.verifyGreedySequence(request.spec(), result);
          case CANDIDATE_PERIOD_CHECK ->
              IndependentNativeComputationVerifier.verifyCandidatePeriod(request.spec(), result);
          case EXACT_GEOMETRY ->
              IndependentGeometryCertificateVerifier.verify(request.spec(), result);
          case NUMBER_THEORY_CHECK ->
              IndependentNumberTheoryCertificateVerifier.verify(request.spec(), result);
          case EXACT_LINEAR_ALGEBRA -> verifyLinearAlgebra(request.spec().arguments(), result.certificate());
          case FINITE_SET_MAP_CHECK -> verifyFiniteMap(request.spec().arguments(), result.certificate());
          case HYPERGRAPH_TRANSVERSAL -> verifyHypergraph(request.spec().arguments(), result.certificate(), request.spec().maxCases());
          case GRAPH_CERTIFICATE -> verifyGraph(request.spec().arguments(), result);
          case NUMERIC_COUNTEREXAMPLE, REAL_INEQUALITY ->
              verifyRelationalCounterexample(request, result);
          case SANDBOXED_PYTHON -> result.outcome() == ExperimentOutcome.NOT_REFUTED;
          case LEAN_CHECK ->
              result.certificate() != null
                  && result.certificate().path("kernel_verified").asBoolean(false);
          case SYMPY_SIMPLIFY,
              SYMPY_EQUIVALENT,
              POLYNOMIAL_FACTOR -> false;
        };
    if (!valid) {
      diagnostics.add("INDEPENDENT_CERTIFICATE_CHECK_FAILED");
    }
    return valid;
  }

  static boolean hasExplicitNativeVerifier(ComputationMethod method) {
    return switch (method) {
      case MODULAR_EXHAUSTIVE,
          BOUNDED_INTEGER_SEARCH,
          GRAPH_CERTIFICATE,
          RECURRENCE_CHECK,
          BOUNDED_GREEDY_SEQUENCE,
          CANDIDATE_PERIOD_CHECK,
          EXACT_GEOMETRY,
          NUMBER_THEORY_CHECK,
          EXACT_LINEAR_ALGEBRA,
          FINITE_SET_MAP_CHECK,
          HYPERGRAPH_TRANSVERSAL -> true;
      default -> false;
    };
  }

  private static ComputationVerifiedAuthority authority(
      ComputationResultArtifact result, ComputationMethod method) {
    if (result.outcome() == ExperimentOutcome.COUNTEREXAMPLE_FOUND
        && result.evidenceStrength() == EvidenceStrength.COUNTEREXAMPLE) {
      return ComputationVerifiedAuthority.EXACT_COUNTEREXAMPLE;
    }
    if (result.outcome() == ExperimentOutcome.CERTIFIED
        && result.evidenceStrength() == EvidenceStrength.FORMAL_CERTIFICATE
        && method == ComputationMethod.LEAN_CHECK) {
      return ComputationVerifiedAuthority.FORMAL_CERTIFICATE;
    }
    if (result.outcome() == ExperimentOutcome.CERTIFIED
        && result.evidenceStrength() == EvidenceStrength.EXHAUSTIVE_CERTIFICATE
        && result.scope().path("complete_domain").asBoolean(false)) {
      return ComputationVerifiedAuthority.FINITE_DOMAIN_CERTIFICATE;
    }
    if (result.outcome() == ExperimentOutcome.NOT_REFUTED
        || result.evidenceStrength() == EvidenceStrength.BOUNDED_EVIDENCE) {
      return ComputationVerifiedAuthority.BOUNDED_OBSERVATION;
    }
    return ComputationVerifiedAuthority.AUDIT_ONLY;
  }

  private static boolean verifyRelationalCounterexample(
      ValidatedComputationRequest request, ComputationResultArtifact result) {
    if (result.outcome() != ExperimentOutcome.COUNTEREXAMPLE_FOUND
        || result.counterexample() == null) {
      return false;
    }
    try {
      ObjectNode arguments = request.spec().arguments();
      ExactExpression lhs = ExactExpression.parse(arguments.path("lhs").asText(), 100);
      ExactExpression rhs = ExactExpression.parse(arguments.path("rhs").asText("0"), 100);
      String relation = arguments.path("relation").asText("eq");
      ObjectNode counterexample = result.counterexample();
      if (counterexample.has("relation")
          && !relation.equals(counterexample.path("relation").asText())) {
        return false;
      }
      JsonNode rawAssignment = counterexample.get("assignment");
      if (rawAssignment == null || !rawAssignment.isObject()) {
        return false;
      }
      Map<String, ExactRational> assignment = new LinkedHashMap<>();
      rawAssignment
          .properties()
          .forEach(
              entry ->
                  assignment.put(
                      entry.getKey(),
                      ExactRational.parse(entry.getValue(), "counterexample assignment")));
      Set<String> declared = declaredVariables(arguments, lhs, rhs);
      if (!assignment.keySet().equals(declared)
          || !declared.containsAll(lhs.variables())
          || !declared.containsAll(rhs.variables())
          || !assignmentWithinScope(request, assignment)) {
        return false;
      }
      ExactRational left = lhs.evaluate(assignment);
      ExactRational right = rhs.evaluate(assignment);
      if (counterexample.has("lhs_value")
          && !left.equals(
              ExactRational.parse(counterexample.get("lhs_value"), "counterexample lhs"))) {
        return false;
      }
      if (counterexample.has("rhs_value")
          && !right.equals(
              ExactRational.parse(counterexample.get("rhs_value"), "counterexample rhs"))) {
        return false;
      }
      return !relationHolds(left, right, relation);
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private static Set<String> declaredVariables(
      ObjectNode arguments, ExactExpression lhs, ExactExpression rhs) {
    Set<String> variables = new LinkedHashSet<>();
    JsonNode declared = arguments.get("variables");
    if (declared != null && declared.isArray()) {
      declared.forEach(value -> variables.add(value.asText()));
    } else {
      variables.addAll(lhs.variables());
      variables.addAll(rhs.variables());
    }
    return Set.copyOf(variables);
  }

  private static boolean assignmentWithinScope(
      ValidatedComputationRequest request, Map<String, ExactRational> assignment) {
    if (request.spec().method() == ComputationMethod.NUMERIC_COUNTEREXAMPLE) {
      JsonNode ranges = request.spec().arguments().path("ranges");
      for (Map.Entry<String, ExactRational> entry : assignment.entrySet()) {
        JsonNode range = ranges.path(entry.getKey());
        ExactRational lower =
            range.isArray()
                ? ExactRational.parse(range.get(0), "range lower bound")
                : new ExactRational(java.math.BigInteger.valueOf(-5L));
        ExactRational upper =
            range.isArray()
                ? ExactRational.parse(range.get(1), "range upper bound")
                : new ExactRational(java.math.BigInteger.valueOf(5L));
        if (entry.getValue().compareTo(lower) < 0 || entry.getValue().compareTo(upper) > 0) {
          return false;
        }
      }
      return true;
    }
    for (Map.Entry<String, ExactRational> entry : assignment.entrySet()) {
      JsonNode domain = request.spec().domains().path(entry.getKey());
      if (!realDomainContains(domain, entry.getValue())) {
        return false;
      }
    }
    return true;
  }

  private static boolean realDomainContains(JsonNode domain, ExactRational value) {
    if (!domain.isObject()) {
      return domain.isMissingNode();
    }
    if (domain.has("min")) {
      int comparison = value.compareTo(ExactRational.parse(domain.get("min"), "domain min"));
      if (comparison < 0 || (comparison == 0 && domain.path("min_exclusive").asBoolean(false))) {
        return false;
      }
    }
    if (domain.has("max")) {
      int comparison = value.compareTo(ExactRational.parse(domain.get("max"), "domain max"));
      if (comparison > 0 || (comparison == 0 && domain.path("max_exclusive").asBoolean(false))) {
        return false;
      }
    }
    return (!domain.path("positive").asBoolean(false) || value.signum() > 0)
        && (!domain.path("nonnegative").asBoolean(false) || value.signum() >= 0)
        && (!domain.path("nonzero").asBoolean(false) || value.signum() != 0);
  }

  private static boolean relationHolds(
      ExactRational left, ExactRational right, String relation) {
    int comparison = left.compareTo(right);
    return switch (relation) {
      case "eq" -> comparison == 0;
      case "ne" -> comparison != 0;
      case "le" -> comparison <= 0;
      case "lt" -> comparison < 0;
      case "ge" -> comparison >= 0;
      case "gt" -> comparison > 0;
      default -> false;
    };
  }

  private static boolean verifyLinearAlgebra(ObjectNode arguments, ObjectNode certificate) {
    if (certificate == null) {
      return false;
    }
    ExactRational[][] matrix = ExactLinearAlgebraFunctions.matrix(arguments.get("matrix"), "matrix");
    String operation = arguments.path("operation").asText();
    return switch (operation) {
      case "determinant" ->
          matrix.length == matrix[0].length
              && ExactRational.parse(certificate.get("determinant"), "determinant")
                  .equals(determinantByElimination(matrix));
      case "rank" -> certificate.path("rank").asInt(-1) == independentRank(matrix);
      case "solve" -> verifySolution(matrix, arguments.get("rhs"), certificate);
      case "nullspace" -> verifyNullspace(matrix, certificate);
      case "span_membership" -> verifySpanMembership(matrix, arguments.get("vector"), certificate);
      default -> false;
    };
  }

  private static ExactRational determinantByElimination(ExactRational[][] matrix) {
    if (matrix.length != matrix[0].length) {
      return ExactRational.ZERO;
    }
    ExactRational[][] values = copy(matrix);
    ExactRational determinant = ExactRational.ONE;
    int sign = 1;
    for (int column = 0; column < values.length; column++) {
      int pivot = column;
      while (pivot < values.length && values[pivot][column].signum() == 0) {
        pivot++;
      }
      if (pivot == values.length) {
        return ExactRational.ZERO;
      }
      if (pivot != column) {
        ExactRational[] swap = values[column];
        values[column] = values[pivot];
        values[pivot] = swap;
        sign = -sign;
      }
      ExactRational pivotValue = values[column][column];
      determinant = determinant.multiply(pivotValue);
      for (int row = column + 1; row < values.length; row++) {
        if (values[row][column].signum() == 0) {
          continue;
        }
        ExactRational factor = values[row][column].divide(pivotValue);
        for (int index = column + 1; index < values.length; index++) {
          values[row][index] =
              values[row][index].subtract(factor.multiply(values[column][index]));
        }
      }
    }
    return sign < 0 ? ExactRational.ZERO.subtract(determinant) : determinant;
  }

  private static int independentRank(ExactRational[][] matrix) {
    ExactRational[][] values = copy(matrix);
    int rank = 0;
    for (int column = 0; column < values[0].length && rank < values.length; column++) {
      int pivot = rank;
      while (pivot < values.length && values[pivot][column].signum() == 0) {
        pivot++;
      }
      if (pivot == values.length) {
        continue;
      }
      ExactRational[] swap = values[rank];
      values[rank] = values[pivot];
      values[pivot] = swap;
      for (int row = rank + 1; row < values.length; row++) {
        if (values[row][column].signum() == 0) {
          continue;
        }
        ExactRational factor = values[row][column].divide(values[rank][column]);
        for (int index = column; index < values[row].length; index++) {
          values[row][index] = values[row][index].subtract(factor.multiply(values[rank][index]));
        }
      }
      rank++;
    }
    return rank;
  }

  private static boolean verifySolution(
      ExactRational[][] matrix, JsonNode rawRhs, ObjectNode certificate) {
    ExactRational[] rhs = ExactLinearAlgebraFunctions.vector(rawRhs, "rhs");
    int rank = independentRank(matrix);
    int augmentedRank = independentRank(augment(matrix, rhs));
    boolean actualConsistent = rank == augmentedRank;
    boolean claimedConsistent = certificate.path("consistent").asBoolean(false);
    if (claimedConsistent != actualConsistent) {
      return false;
    }
    if (!actualConsistent) {
      return true;
    }
    boolean actualUnique = rank == matrix[0].length;
    if (certificate.path("unique").asBoolean(false) != actualUnique) {
      return false;
    }
    if (!actualUnique) {
      return true;
    }
    ExactRational[] solution = ExactLinearAlgebraFunctions.vector(certificate.get("solution"), "solution");
    return equal(ExactLinearAlgebraFunctions.multiply(matrix, solution), rhs);
  }

  private static boolean verifyNullspace(
      ExactRational[][] matrix, ObjectNode certificate) {
    JsonNode raw = certificate.get("nullspace_basis");
    if (raw == null || !raw.isArray()) {
      return false;
    }
    List<ExactRational[]> basis = new ArrayList<>();
    for (JsonNode vector : raw) {
      basis.add(ExactLinearAlgebraFunctions.vector(vector, "basis"));
    }
    boolean nullVectors =
        basis.stream()
            .map(value -> ExactLinearAlgebraFunctions.multiply(matrix, value))
            .flatMap(value -> java.util.Arrays.stream(value))
            .allMatch(value -> value.signum() == 0);
    if (!nullVectors || basis.size() != matrix[0].length - independentRank(matrix)) {
      return false;
    }
    if (basis.isEmpty()) {
      return true;
    }
    ExactRational[][] basisMatrix = new ExactRational[matrix[0].length][basis.size()];
    for (int column = 0; column < basis.size(); column++) {
      for (int row = 0; row < matrix[0].length; row++) {
        basisMatrix[row][column] = basis.get(column)[row];
      }
    }
    return independentRank(basisMatrix) == basis.size();
  }

  private static boolean verifySpanMembership(
      ExactRational[][] matrix, JsonNode rawTarget, ObjectNode certificate) {
    ExactRational[] target = ExactLinearAlgebraFunctions.vector(rawTarget, "vector");
    boolean actual = independentRank(matrix) == independentRank(augment(matrix, target));
    if (certificate.path("member").asBoolean(false) != actual) {
      return false;
    }
    if (!actual) {
      return true;
    }
    ExactRational[] coefficients =
        ExactLinearAlgebraFunctions.vector(certificate.get("coefficients"), "coefficients");
    return equal(ExactLinearAlgebraFunctions.multiply(matrix, coefficients), target);
  }

  private static ExactRational[][] augment(
      ExactRational[][] matrix, ExactRational[] column) {
    ExactRational[][] result = new ExactRational[matrix.length][matrix[0].length + 1];
    for (int row = 0; row < matrix.length; row++) {
      System.arraycopy(matrix[row], 0, result[row], 0, matrix[0].length);
      result[row][matrix[0].length] = column[row];
    }
    return result;
  }

  private static boolean equal(ExactRational[] left, ExactRational[] right) {
    return java.util.Arrays.equals(left, right);
  }

  private static ExactRational[][] copy(ExactRational[][] matrix) {
    ExactRational[][] result = new ExactRational[matrix.length][];
    for (int row = 0; row < matrix.length; row++) {
      result[row] = matrix[row].clone();
    }
    return result;
  }

  private static boolean verifyFiniteMap(ObjectNode arguments, ObjectNode certificate) {
    if (certificate == null || !certificate.path("complete_finite_coverage").asBoolean(false)) {
      return false;
    }
    List<String> domain = FiniteSetMapFunctions.values(arguments.get("domain"), "domain");
    List<String> codomain = FiniteSetMapFunctions.values(arguments.get("codomain"), "codomain");
    Map<String, String> mapping = FiniteSetMapFunctions.mapping(arguments.get("mapping"), domain, codomain);
    Set<String> images = new HashSet<>(mapping.values());
    boolean injective = images.size() == mapping.size();
    boolean surjective = images.equals(Set.copyOf(codomain));
    if (certificate.path("injective").asBoolean(false) != injective
        || certificate.path("surjective").asBoolean(false) != surjective
        || certificate.path("bijective").asBoolean(false) != (injective && surjective)) {
      return false;
    }
    String operation = arguments.path("operation").asText();
    return switch (operation) {
      case "image" -> jsonSet(certificate.get("image")).equals(images);
      case "preimage" -> {
        String target = arguments.path("target").asText();
        Set<String> expected = new HashSet<>();
        mapping.forEach((key, value) -> {
          if (value.equals(target)) {
            expected.add(key);
          }
        });
        yield jsonSet(certificate.get("preimage")).equals(expected);
      }
      case "cardinality_equality" ->
          certificate.path("cardinality_equal").asBoolean(false)
              == (domain.size() == codomain.size());
      case "injective", "surjective", "bijective" -> true;
      default -> false;
    };
  }

  private static boolean verifyHypergraph(
      ObjectNode arguments, ObjectNode certificate, int maxCases) {
    if (certificate == null) {
      return false;
    }
    List<String> vertices = FiniteSetMapFunctions.values(arguments.get("vertices"), "vertices");
    List<Set<String>> edges = HypergraphTransversalFunctions.edges(arguments.get("edges"), Set.copyOf(vertices));
    String operation = arguments.path("operation").asText();
    if (operation.equals("is_hitting_set") || operation.equals("is_minimal_hitting_set")) {
      Set<String> candidate = jsonSet(arguments.get("candidate"));
      boolean hitting = independentHits(candidate, edges);
      if (certificate.path("is_hitting_set").asBoolean(false) != hitting) {
        return false;
      }
      return !operation.equals("is_minimal_hitting_set")
          || certificate.path("is_minimal_hitting_set").asBoolean(false)
              == independentMinimal(candidate, edges);
    }
    if (!operation.equals("enumerate_minimal_transversals")
        || !certificate.path("complete_finite_coverage").asBoolean(false)) {
      return false;
    }
    long total = 1L << vertices.size();
    if (vertices.size() > 30 || total > maxCases) {
      return false;
    }
    Set<Set<String>> expected = new HashSet<>();
    for (long mask = 0; mask < total; mask++) {
      Set<String> candidate = new LinkedHashSet<>();
      for (int index = 0; index < vertices.size(); index++) {
        if ((mask & (1L << index)) != 0L) {
          candidate.add(vertices.get(index));
        }
      }
      if (independentMinimal(candidate, edges)) {
        expected.add(Set.copyOf(candidate));
      }
    }
    Set<Set<String>> actual = new HashSet<>();
    for (JsonNode item : certificate.path("minimal_transversals")) {
      actual.add(Set.copyOf(jsonSet(item)));
    }
    return actual.equals(expected) && certificate.path("subsets_checked").asLong(-1L) == total;
  }

  private static boolean independentHits(Set<String> candidate, List<Set<String>> edges) {
    for (Set<String> edge : edges) {
      boolean intersects = false;
      for (String value : candidate) {
        intersects |= edge.contains(value);
      }
      if (!intersects) {
        return false;
      }
    }
    return true;
  }

  private static boolean independentMinimal(Set<String> candidate, List<Set<String>> edges) {
    if (!independentHits(candidate, edges)) {
      return false;
    }
    for (String removed : candidate) {
      Set<String> smaller = new HashSet<>(candidate);
      smaller.remove(removed);
      if (independentHits(smaller, edges)) {
        return false;
      }
    }
    return true;
  }

  private static boolean verifyGraph(ObjectNode arguments, ComputationResultArtifact result) {
    JsonNode rawGraph = arguments.get("graph");
    if (rawGraph == null || !rawGraph.isObject()) {
      return false;
    }
    ObjectNode graph = (ObjectNode) rawGraph;
    Set<String> nodes = jsonSet(graph.get("nodes"));
    if (nodes.size() != graph.path("nodes").size()) {
      return false;
    }
    boolean directed = graph.path("directed").asBoolean(false);
    Set<String> forwardEdges = new HashSet<>();
    Set<String> reverseEdges = new HashSet<>();
    for (JsonNode edge : graph.path("edges")) {
      if (!edge.isArray() || edge.size() != 2) {
        return false;
      }
      String left = edge.get(0).asText();
      String right = edge.get(1).asText();
      if (!nodes.contains(left) || !nodes.contains(right)) {
        return false;
      }
      forwardEdges.add(left + "\u0000" + right);
      reverseEdges.add(right + "\u0000" + left);
      if (!directed) {
        forwardEdges.add(right + "\u0000" + left);
        reverseEdges.add(left + "\u0000" + right);
      }
    }
    String property = arguments.path("property").asText();
    ObjectNode proposed =
        arguments.path("certificate").isObject()
            ? (ObjectNode) arguments.path("certificate")
            : ComputationJson.object();
    boolean propertyHolds;
    if (property.equals("connected")) {
      if (nodes.isEmpty()) {
        return false;
      }
      String start = nodes.iterator().next();
      propertyHolds = reachable(start, nodes, forwardEdges).equals(nodes)
          && (!directed || reachable(start, nodes, reverseEdges).equals(nodes));
    } else if (property.equals("proper_coloring")) {
      propertyHolds = verifyColoring(nodes, forwardEdges, proposed.path("colors"));
    } else if (property.equals("path") || property.equals("cycle")) {
      propertyHolds =
          verifyGraphWalk(
              nodes,
              forwardEdges,
              proposed.path("vertices"),
              property.equals("cycle"),
              directed);
    } else if (property.equals("matching")) {
      propertyHolds = !directed && verifyMatching(forwardEdges, proposed.path("edges"));
    } else {
      return false;
    }
    return result.exactArithmetic()
        && ((result.outcome() == ExperimentOutcome.CERTIFIED && propertyHolds)
            || (result.outcome() == ExperimentOutcome.COUNTEREXAMPLE_FOUND
                && property.equals("connected")
                && !propertyHolds));
  }

  private static boolean verifyColoring(
      Set<String> nodes, Set<String> edges, JsonNode rawColors) {
    if (!rawColors.isObject()) {
      return false;
    }
    Set<String> colored = new HashSet<>();
    rawColors.properties().forEach(entry -> colored.add(entry.getKey()));
    if (!colored.equals(nodes)) {
      return false;
    }
    for (String edge : edges) {
      int separator = edge.indexOf('\u0000');
      String left = edge.substring(0, separator);
      String right = edge.substring(separator + 1);
      if (rawColors.path(left).equals(rawColors.path(right))) {
        return false;
      }
    }
    return true;
  }

  private static boolean verifyGraphWalk(
      Set<String> nodes,
      Set<String> edges,
      JsonNode rawVertices,
      boolean cycle,
      boolean directed) {
    if (!rawVertices.isArray()) {
      return false;
    }
    List<String> vertices = new ArrayList<>();
    rawVertices.forEach(value -> vertices.add(value.asText()));
    int minimum = cycle ? (directed ? 2 : 3) : 1;
    if (vertices.size() < minimum
        || new HashSet<>(vertices).size() != vertices.size()
        || !nodes.containsAll(vertices)) {
      return false;
    }
    for (int index = 1; index < vertices.size(); index++) {
      if (!edges.contains(vertices.get(index - 1) + "\u0000" + vertices.get(index))) {
        return false;
      }
    }
    return !cycle
        || edges.contains(vertices.getLast() + "\u0000" + vertices.getFirst());
  }

  private static boolean verifyMatching(Set<String> edges, JsonNode rawMatching) {
    if (!rawMatching.isArray()) {
      return false;
    }
    Set<String> used = new HashSet<>();
    for (JsonNode edge : rawMatching) {
      if (!edge.isArray() || edge.size() != 2) {
        return false;
      }
      String left = edge.get(0).asText();
      String right = edge.get(1).asText();
      if (!edges.contains(left + "\u0000" + right)
          || !used.add(left)
          || !used.add(right)) {
        return false;
      }
    }
    return true;
  }

  private static Set<String> reachable(
      String start, Set<String> nodes, Set<String> edges) {
    Set<String> seen = new HashSet<>();
    ArrayDeque<String> queue = new ArrayDeque<>();
    seen.add(start);
    queue.add(start);
    while (!queue.isEmpty()) {
      String current = queue.removeFirst();
      for (String candidate : nodes) {
        if (edges.contains(current + "\u0000" + candidate) && seen.add(candidate)) {
          queue.add(candidate);
        }
      }
    }
    return seen;
  }

  private static Set<String> jsonSet(JsonNode node) {
    if (node == null || !node.isArray()) {
      return Set.of();
    }
    Set<String> values = new LinkedHashSet<>();
    node.forEach(value -> values.add(value.asText()));
    return Set.copyOf(values);
  }

  private static boolean match(
      String actual, String expected, String code, List<String> diagnostics) {
    if (actual.equals(expected)) {
      return true;
    }
    diagnostics.add(code);
    return false;
  }
}
