package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ComputationCertificateEnvelope;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ComputationVerificationReceipt;
import io.github.aililuola.mathproofmesh.contract.ComputationVerificationStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndependentVerifierBranchCoverageTest {
  private static final String CHANGED_HASH = "f".repeat(64);

  @Test
  void everyCertificateBindingIsCheckedIndependently() {
    ExperimentSpec spec = ComputationIssue010TestSupport.linearAlgebraSpec();
    ComputationResultArtifact result = nativeResult(spec);
    var request = request(spec, descriptor(spec.method()));
    ComputationCertificateEnvelope certificate = ComputationCertificateFactory.create(request, result);

    for (String field :
        List.of(
            "request",
            "execution",
            "capability_id",
            "capability_version",
            "result",
            "scope",
            "domain",
            "producer_id",
            "producer_version",
            "witness")) {
      ComputationVerificationReceipt receipt =
          new IndependentComputationCertificateVerifier()
              .verify(request, result, changedBinding(certificate, field));
      assertThat(receipt.valid()).isFalse();
      assertThat(receipt.diagnostics()).anyMatch(value -> value.endsWith("_MISMATCH"));
    }
  }

  @Test
  void linearAlgebraVerifierRejectsPlausibleButIncorrectCertificates() {
    assertInvalidMutation(
        spec(ComputationMethod.EXACT_LINEAR_ALGEBRA, la("determinant", "[[1,2],[3,4]]", "")),
        certificate -> certificate.put("determinant", "99"));
    assertInvalidMutation(
        spec(ComputationMethod.EXACT_LINEAR_ALGEBRA, la("rank", "[[1,2],[2,4]]", "")),
        certificate -> certificate.put("rank", 2));
    assertInvalidMutation(
        spec(
            ComputationMethod.EXACT_LINEAR_ALGEBRA,
            la("solve", "[[1,0],[0,1]]", ",\"rhs\":[2,3]")),
        certificate -> certificate.put("consistent", false));
    assertInvalidMutation(
        spec(
            ComputationMethod.EXACT_LINEAR_ALGEBRA,
            la("solve", "[[1,0],[0,1]]", ",\"rhs\":[2,3]")),
        certificate -> certificate.put("unique", false));
    assertInvalidMutation(
        spec(
            ComputationMethod.EXACT_LINEAR_ALGEBRA,
            la("solve", "[[1,0],[0,1]]", ",\"rhs\":[2,3]")),
        certificate -> certificate.set("solution", array("[9,9]")));
    assertInvalidMutation(
        spec(
            ComputationMethod.EXACT_LINEAR_ALGEBRA,
            la("nullspace", "[[1,2],[2,4]]", "")),
        certificate -> certificate.set("nullspace_basis", array("[]")));
    assertInvalidMutation(
        spec(
            ComputationMethod.EXACT_LINEAR_ALGEBRA,
            la("nullspace", "[[0,0],[0,0]]", "")),
        certificate -> certificate.set("nullspace_basis", array("[[1,0],[2,0]]")));
    assertInvalidMutation(
        spec(
            ComputationMethod.EXACT_LINEAR_ALGEBRA,
            la("span_membership", "[[1],[0]]", ",\"vector\":[0,1]")),
        certificate -> certificate.put("member", true));
    assertInvalidMutation(
        spec(
            ComputationMethod.EXACT_LINEAR_ALGEBRA,
            la("span_membership", "[[1],[2]]", ",\"vector\":[3,6]")),
        certificate -> certificate.set("coefficients", array("[4]")));

    ExperimentSpec rectangular =
        spec(
            ComputationMethod.EXACT_LINEAR_ALGEBRA,
            la("determinant", "[[1,2]]", ""));
    assertThat(
            verify(
                    rectangular,
                    manual(
                        rectangular,
                        ExperimentOutcome.CERTIFIED,
                        EvidenceStrength.EXHAUSTIVE_CERTIFICATE,
                        "{\"complete_domain\":true}",
                        null,
                        "{\"determinant\":\"0\"}",
                        true,
                        ""))
                .valid())
        .isFalse();
  }

  @Test
  void finiteAndHypergraphVerifiersRecomputeClaimsRatherThanTrustPayloads() {
    String mapping =
        "\"domain\":[\"a\",\"b\"],\"codomain\":[\"x\",\"y\"],"
            + "\"mapping\":{\"a\":\"x\",\"b\":\"y\"}";
    assertInvalidMutation(
        spec(ComputationMethod.FINITE_SET_MAP_CHECK, map("injective", mapping, "")),
        certificate -> certificate.put("injective", false));
    assertInvalidMutation(
        spec(ComputationMethod.FINITE_SET_MAP_CHECK, map("image", mapping, "")),
        certificate -> certificate.set("image", array("[\"z\"]")));
    assertInvalidMutation(
        spec(
            ComputationMethod.FINITE_SET_MAP_CHECK,
            map("preimage", mapping, ",\"target\":\"x\"")),
        certificate -> certificate.set("preimage", array("[\"b\"]")));
    assertInvalidMutation(
        spec(
            ComputationMethod.FINITE_SET_MAP_CHECK,
            map("cardinality_equality", mapping, "")),
        certificate -> certificate.put("cardinality_equal", false));
    assertInvalidMutation(
        spec(ComputationMethod.FINITE_SET_MAP_CHECK, map("bijective", mapping, "")),
        certificate -> certificate.put("complete_finite_coverage", false));

    String hypergraph =
        "\"vertices\":[\"a\",\"b\",\"c\"],"
            + "\"edges\":[[\"a\",\"b\"],[\"b\",\"c\"]]";
    assertInvalidMutation(
        spec(
            ComputationMethod.HYPERGRAPH_TRANSVERSAL,
            hypergraph("is_hitting_set", hypergraph, "[\"b\"]")),
        certificate -> certificate.put("is_hitting_set", false));
    assertInvalidMutation(
        spec(
            ComputationMethod.HYPERGRAPH_TRANSVERSAL,
            hypergraph("is_minimal_hitting_set", hypergraph, "[\"b\"]")),
        certificate -> certificate.put("is_minimal_hitting_set", false));
    assertInvalidMutation(
        spec(
            ComputationMethod.HYPERGRAPH_TRANSVERSAL,
            "{\"operation\":\"enumerate_minimal_transversals\"," + hypergraph + "}"),
        certificate -> certificate.set("minimal_transversals", array("[]")));
    assertInvalidMutation(
        spec(
            ComputationMethod.HYPERGRAPH_TRANSVERSAL,
            "{\"operation\":\"enumerate_minimal_transversals\"," + hypergraph + "}"),
        certificate -> certificate.put("subsets_checked", 7));
    assertInvalidMutation(
        spec(
            ComputationMethod.HYPERGRAPH_TRANSVERSAL,
            "{\"operation\":\"enumerate_minimal_transversals\"," + hypergraph + "}"),
        certificate -> certificate.put("complete_finite_coverage", false));
  }

  @Test
  void relationalCounterexamplesBindAssignmentsRelationsAndDeclaredDomains() {
    ExperimentSpec numeric =
        spec(
            ComputationMethod.NUMERIC_COUNTEREXAMPLE,
            "{\"lhs\":\"x\",\"rhs\":\"0\",\"relation\":\"ge\","
                + "\"variables\":[\"x\"],\"ranges\":{\"x\":[-2,2]}}",
            "{}");
    assertRelational(numeric, "{\"assignment\":{\"x\":-1},\"relation\":\"ge\","
        + "\"lhs_value\":-1,\"rhs_value\":0}", true);
    assertRelational(numeric, "{\"assignment\":{\"x\":-1},\"relation\":\"lt\"}", false);
    assertRelational(numeric, "{\"assignment\":[]}", false);
    assertRelational(numeric, "{\"assignment\":{\"y\":-1}}", false);
    assertRelational(numeric, "{\"assignment\":{\"x\":3}}", false);
    assertRelational(numeric, "{\"assignment\":{\"x\":-1},\"lhs_value\":0}", false);
    assertRelational(numeric, "{\"assignment\":{\"x\":-1},\"rhs_value\":1}", false);

    assertRelation("eq", "1", "{}", true);
    assertRelation("ne", "0", "{}", true);
    assertRelation("le", "1", "{}", true);
    assertRelation("lt", "0", "{}", true);
    assertRelation("ge", "-1", "{}", true);
    assertRelation("gt", "0", "{}", true);
    assertRelation("unknown", "0", "{}", true);
    assertRelation("eq", "0", "{}", false);

    assertRelation("eq", "0", "{\"x\":5}", false);
    assertRelation("eq", "0", "{\"x\":{\"min\":1}}", false);
    assertRelation(
        "eq", "1", "{\"x\":{\"min\":1,\"min_exclusive\":true}}", false);
    assertRelation("eq", "2", "{\"x\":{\"max\":1}}", false);
    assertRelation(
        "eq", "1", "{\"x\":{\"max\":1,\"max_exclusive\":true}}", false);
    assertRelation("eq", "0", "{\"x\":{\"positive\":true}}", false);
    assertRelation("eq", "-1", "{\"x\":{\"nonnegative\":true}}", false);
    assertRelation("eq", "0", "{\"x\":{\"nonzero\":true}}", false);
    assertRelation(
        "eq",
        "1",
        "{\"x\":{\"min\":0,\"max\":2,\"positive\":true,"
            + "\"nonnegative\":true,\"nonzero\":true}}",
        true);
  }

  @Test
  void graphVerifierRejectsMalformedGraphsAndCertificateRelabeling() {
    assertGraph("{}", false);
    assertGraph(
        graph("connected", false, "[\"a\",\"a\"]", "[]", "{}"), false);
    assertGraph(
        graph("connected", false, "[\"a\"]", "[[\"a\"]]", "{}"), false);
    assertGraph(
        graph("connected", false, "[\"a\"]", "[[\"a\",\"z\"]]", "{}"), false);
    assertGraph(graph("connected", false, "[]", "[]", "{}"), false);
    assertGraph(
        graph(
            "proper_coloring",
            false,
            "[\"a\",\"b\"]",
            "[[\"a\",\"b\"]]",
            "{}"),
        false);
    assertGraph(
        graph(
            "proper_coloring",
            false,
            "[\"a\",\"b\"]",
            "[[\"a\",\"b\"]]",
            "{\"colors\":{\"a\":\"r\"}}"),
        false);
    assertGraph(
        graph(
            "path", false, "[\"a\",\"b\"]", "[[\"a\",\"b\"]]", "{}"),
        false);
    assertGraph(
        graph(
            "path",
            false,
            "[\"a\",\"b\"]",
            "[[\"a\",\"b\"]]",
            "{\"vertices\":[\"a\",\"a\"]}"),
        false);
    assertGraph(
        graph(
            "cycle",
            false,
            "[\"a\",\"b\",\"c\"]",
            "[[\"a\",\"b\"],[\"b\",\"c\"]]",
            "{\"vertices\":[\"a\",\"b\",\"c\"]}"),
        false);
    assertGraph(
        graph(
            "matching",
            true,
            "[\"a\",\"b\"]",
            "[[\"a\",\"b\"]]",
            "{\"edges\":[[\"a\",\"b\"]]}"),
        false);
    assertGraph(
        graph(
            "matching",
            false,
            "[\"a\",\"b\"]",
            "[[\"a\",\"b\"]]",
            "{\"edges\":[[\"a\"]]}"),
        false);
    assertGraph(
        graph(
            "matching",
            false,
            "[\"a\",\"b\",\"c\"]",
            "[[\"a\",\"b\"],[\"b\",\"c\"]]",
            "{\"edges\":[[\"a\",\"b\"],[\"b\",\"c\"]]}"),
        false);
    assertGraph(graph("unknown", false, "[\"a\"]", "[]", "{}"), false);
  }

  @Test
  void outcomeStatusAuthorityAndCapabilityCeilingsFailClosed() {
    ExperimentSpec mapSpec = ComputationIssue010TestSupport.finiteMapSpec();
    ComputationResultArtifact mapResult = rebindToSpec(mapSpec, nativeResult(mapSpec));
    ComputationCapabilityDescriptor lowCeiling =
        withCeiling(descriptor(mapSpec.method()), ComputationAuthorityCeiling.AUDIT_ONLY);
    ComputationVerificationReceipt ceilingReceipt = verify(mapSpec, mapResult, lowCeiling);
    assertThat(ceilingReceipt.valid()).isFalse();
    assertThat(ceilingReceipt.diagnostics()).contains("AUTHORITY_CEILING_EXCEEDED");

    ExperimentSpec defaultSpec = spec(ComputationMethod.NUMBER_THEORY_CHECK, "{}");
    assertThat(
            verify(
                    defaultSpec,
                    manual(
                        defaultSpec,
                        ExperimentOutcome.CERTIFIED,
                        EvidenceStrength.EXHAUSTIVE_CERTIFICATE,
                        "{\"complete_domain\":true}",
                        null,
                        "{}",
                        true,
                        ""))
                .valid())
        .isFalse();
    assertThat(
            verify(
                    defaultSpec,
                    manual(
                        defaultSpec,
                        ExperimentOutcome.CERTIFIED,
                        EvidenceStrength.EXHAUSTIVE_CERTIFICATE,
                        "{}",
                        null,
                        "{}",
                        true,
                        ""))
                .valid())
        .isFalse();
    assertThat(
            verify(
                    defaultSpec,
                    manual(
                        defaultSpec,
                        ExperimentOutcome.CERTIFIED,
                        EvidenceStrength.EXHAUSTIVE_CERTIFICATE,
                        "{\"complete_domain\":true}",
                        null,
                        "{}",
                        false,
                        ""))
                .valid())
        .isFalse();

    assertOutcomeStatus(ExperimentOutcome.ERROR, EvidenceStrength.HEURISTIC, "failure", false);
    ComputationVerificationReceipt unavailable =
        verify(
            defaultSpec,
            manual(
                defaultSpec,
                ExperimentOutcome.ERROR,
                EvidenceStrength.HEURISTIC,
                "{}",
                null,
                null,
                false,
                "BACKEND_UNAVAILABLE: no backend"));
    assertThat(unavailable.status()).isEqualTo(ComputationVerificationStatus.BACKEND_UNAVAILABLE);
    assertOutcomeStatus(ExperimentOutcome.INCONCLUSIVE, EvidenceStrength.HEURISTIC, "", false);
    assertOutcomeStatus(ExperimentOutcome.NOT_REFUTED, EvidenceStrength.HEURISTIC, "", true);
    assertOutcomeStatus(ExperimentOutcome.NOT_REFUTED, EvidenceStrength.BOUNDED_EVIDENCE, "", true);
    assertOutcomeStatus(
        ExperimentOutcome.NOT_REFUTED, EvidenceStrength.EXHAUSTIVE_CERTIFICATE, "", false);
    assertThat(
            verify(
                    defaultSpec,
                    manual(
                        defaultSpec,
                        ExperimentOutcome.CERTIFIED,
                        EvidenceStrength.BOUNDED_EVIDENCE,
                        "{\"complete_domain\":true}",
                        null,
                        "{}",
                        true,
                        ""))
                .valid())
        .isFalse();

    ExperimentSpec numeric =
        spec(
            ComputationMethod.NUMERIC_COUNTEREXAMPLE,
            "{\"lhs\":\"x\",\"rhs\":\"0\",\"relation\":\"eq\"}");
    assertThat(
            verify(
                    numeric,
                    manual(
                        numeric,
                        ExperimentOutcome.CERTIFIED,
                        EvidenceStrength.EXHAUSTIVE_CERTIFICATE,
                        "{\"complete_domain\":true}",
                        null,
                        "{}",
                        true,
                        ""))
                .valid())
        .isFalse();
    assertThat(
            verify(
                    numeric,
                    manual(
                        numeric,
                        ExperimentOutcome.COUNTEREXAMPLE_FOUND,
                        EvidenceStrength.COUNTEREXAMPLE,
                        "{}",
                        null,
                        null,
                        true,
                        ""))
                .valid())
        .isFalse();
    assertThat(
            verify(
                    defaultSpec,
                    manual(
                        defaultSpec,
                        ExperimentOutcome.COUNTEREXAMPLE_FOUND,
                        EvidenceStrength.COUNTEREXAMPLE,
                        "{}",
                        "{\"witness\":1}",
                        null,
                        true,
                        ""))
                .valid())
        .isFalse();

    ExperimentSpec lean = spec(ComputationMethod.LEAN_CHECK, "{}");
    assertThat(
            verify(
                    lean,
                    manual(
                        lean,
                        ExperimentOutcome.CERTIFIED,
                        EvidenceStrength.FORMAL_CERTIFICATE,
                        "{}",
                        null,
                        "{\"kernel_verified\":true}",
                        true,
                        ""))
                .valid())
        .isTrue();
    assertThat(
            verify(
                    lean,
                    manual(
                        lean,
                        ExperimentOutcome.CERTIFIED,
                        EvidenceStrength.FORMAL_CERTIFICATE,
                        "{}",
                        null,
                        "{\"kernel_verified\":false}",
                        true,
                        ""))
                .valid())
        .isFalse();
    for (ComputationMethod method :
        List.of(
            ComputationMethod.SYMPY_SIMPLIFY,
            ComputationMethod.SYMPY_EQUIVALENT,
            ComputationMethod.POLYNOMIAL_FACTOR,
            ComputationMethod.SANDBOXED_PYTHON)) {
      ExperimentSpec unsupported = spec(method, "{}");
      assertThat(
              verify(
                      unsupported,
                      manual(
                          unsupported,
                          ExperimentOutcome.CERTIFIED,
                          EvidenceStrength.EXHAUSTIVE_CERTIFICATE,
                          "{\"complete_domain\":true}",
                          null,
                          "{}",
                          true,
                          ""))
                  .valid())
          .isFalse();
    }
  }

  private static void assertInvalidMutation(
      ExperimentSpec spec, java.util.function.Consumer<ObjectNode> mutation) {
    ComputationResultArtifact result = nativeResult(spec);
    ObjectNode certificate = result.certificate();
    mutation.accept(certificate);
    assertThat(verify(spec, withCertificate(result, certificate)).valid()).isFalse();
  }

  private static void assertRelational(
      ExperimentSpec spec, String counterexample, boolean expected) {
    var result =
        manual(
            spec,
            ExperimentOutcome.COUNTEREXAMPLE_FOUND,
            EvidenceStrength.COUNTEREXAMPLE,
            "{}",
            counterexample,
            null,
            true,
            "");
    assertThat(verify(spec, result).valid()).isEqualTo(expected);
  }

  private static void assertRelation(
      String relation, String value, String domains, boolean expected) {
    ExperimentSpec spec =
        spec(
            ComputationMethod.REAL_INEQUALITY,
            "{\"lhs\":\"x\",\"rhs\":\"0\",\"relation\":\""
                + relation
                + "\"}",
            domains);
    assertRelational(spec, "{\"assignment\":{\"x\":" + value + "}}", expected);
  }

  private static void assertGraph(String arguments, boolean expected) {
    ExperimentSpec spec = spec(ComputationMethod.GRAPH_CERTIFICATE, arguments);
    var result =
        manual(
            spec,
            ExperimentOutcome.CERTIFIED,
            EvidenceStrength.EXHAUSTIVE_CERTIFICATE,
            "{\"complete_domain\":true}",
            null,
            "{}",
            true,
            "");
    assertThat(verify(spec, result).valid()).isEqualTo(expected);
  }

  private static void assertOutcomeStatus(
      ExperimentOutcome outcome, EvidenceStrength strength, String error, boolean expected) {
    ExperimentSpec spec = spec(ComputationMethod.NUMBER_THEORY_CHECK, "{}");
    var result = manual(spec, outcome, strength, "{}", null, null, true, error);
    assertThat(verify(spec, result).valid()).isEqualTo(expected);
  }

  private static ComputationVerificationReceipt verify(
      ExperimentSpec spec, ComputationResultArtifact result) {
    return verify(spec, result, descriptor(spec.method()));
  }

  private static ComputationVerificationReceipt verify(
      ExperimentSpec spec,
      ComputationResultArtifact result,
      ComputationCapabilityDescriptor descriptor) {
    ValidatedComputationRequest request = request(spec, descriptor);
    return new IndependentComputationCertificateVerifier()
        .verify(request, result, ComputationCertificateFactory.create(request, result));
  }

  private static ValidatedComputationRequest request(
      ExperimentSpec spec, ComputationCapabilityDescriptor descriptor) {
    return new ValidatedComputationRequest(spec, descriptor, null, "branch-verifier");
  }

  private static ComputationCapabilityDescriptor descriptor(ComputationMethod method) {
    return ComputationIssue010TestSupport.descriptor(method);
  }

  private static ComputationResultArtifact nativeResult(ExperimentSpec spec) {
    var broker = ComputationFixtures.broker("verifier-native-" + spec.experimentId());
    var outcome = ComputationIssue010TestSupport.run(broker, spec);
    return broker
        .executionService()
        .artifacts()
        .read(outcome.artifacts().result().reference(), ComputationResultArtifact.class)
        .orElseThrow();
  }

  private static ComputationResultArtifact withCertificate(
      ComputationResultArtifact result, ObjectNode certificate) {
    return new ComputationResultArtifact(
        result.requestHash(),
        result.executionHash(),
        result.outcome(),
        result.evidenceStrength(),
        result.scope(),
        result.counterexample(),
        certificate,
        result.exactArithmetic(),
        result.casesChecked(),
        result.runtimeSeconds(),
        result.producerId(),
        result.producerVersion(),
        result.error(),
        null);
  }

  private static ComputationResultArtifact rebindToSpec(
      ExperimentSpec spec, ComputationResultArtifact result) {
    return new ComputationResultArtifact(
        spec.requestHash(),
        spec.executionHash(),
        result.outcome(),
        result.evidenceStrength(),
        result.scope(),
        result.counterexample(),
        result.certificate(),
        result.exactArithmetic(),
        result.casesChecked(),
        result.runtimeSeconds(),
        result.producerId(),
        result.producerVersion(),
        result.error(),
        null);
  }

  private static ComputationResultArtifact manual(
      ExperimentSpec spec,
      ExperimentOutcome outcome,
      EvidenceStrength strength,
      String scope,
      String counterexample,
      String certificate,
      boolean exact,
      String error) {
    ComputationCapabilityDescriptor descriptor = descriptor(spec.method());
    return new ComputationResultArtifact(
        spec.requestHash(),
        spec.executionHash(),
        outcome,
        strength,
        object(scope),
        counterexample == null ? null : object(counterexample),
        certificate == null ? null : object(certificate),
        exact,
        1,
        0.0d,
        descriptor.producerId(),
        descriptor.producerVersion(),
        error,
        null);
  }

  private static ComputationCertificateEnvelope changedBinding(
      ComputationCertificateEnvelope value, String field) {
    return new ComputationCertificateEnvelope(
        field.equals("request") ? CHANGED_HASH : value.requestHash(),
        field.equals("execution") ? CHANGED_HASH : value.executionHash(),
        field.equals("capability_id") ? "changed-capability" : value.capabilityId(),
        field.equals("capability_version") ? "changed-version" : value.capabilityVersion(),
        field.equals("scope") ? CHANGED_HASH : value.scopeHash(),
        field.equals("domain") ? CHANGED_HASH : value.domainHash(),
        field.equals("result") ? CHANGED_HASH : value.resultHash(),
        value.certificateType(),
        value.casesExpected(),
        value.casesChecked(),
        field.equals("witness") ? object("{\"changed\":true}") : value.witness(),
        value.coverageDigest(),
        field.equals("producer_id") ? "changed-producer" : value.producerId(),
        field.equals("producer_version") ? "changed-version" : value.producerVersion(),
        null);
  }

  private static ComputationCapabilityDescriptor withCeiling(
      ComputationCapabilityDescriptor value, ComputationAuthorityCeiling ceiling) {
    return new ComputationCapabilityDescriptor(
        value.method(),
        value.capabilityId(),
        value.capabilityVersion(),
        value.backendKind(),
        value.determinism(),
        ceiling,
        value.sideEffectFree(),
        value.replaySafe(),
        value.inputSchemaHash(),
        value.outputSchemaHash(),
        value.resourceEnvelope(),
        value.producerId(),
        value.producerVersion(),
        value.verifierId(),
        value.verifierVersion());
  }

  private static ExperimentSpec spec(ComputationMethod method, String arguments) {
    return ComputationFixtures.spec(method, arguments);
  }

  private static ExperimentSpec spec(
      ComputationMethod method, String arguments, String domains) {
    return ComputationFixtures.spec(method, arguments, domains);
  }

  private static ObjectNode object(String json) {
    return ComputationFixtures.object(json);
  }

  private static com.fasterxml.jackson.databind.node.ArrayNode array(String json) {
    return (com.fasterxml.jackson.databind.node.ArrayNode)
        io.github.aililuola.mathproofmesh.contract.ContractObjectMapper.parseTree(json);
  }

  private static String la(String operation, String matrix, String suffix) {
    return "{\"operation\":\""
        + operation
        + "\",\"matrix\":"
        + matrix
        + suffix
        + "}";
  }

  private static String map(String operation, String body, String suffix) {
    return "{\"operation\":\"" + operation + "\"," + body + suffix + "}";
  }

  private static String hypergraph(String operation, String body, String candidate) {
    return "{\"operation\":\""
        + operation
        + "\","
        + body
        + ",\"candidate\":"
        + candidate
        + "}";
  }

  private static String graph(
      String property,
      boolean directed,
      String nodes,
      String edges,
      String certificate) {
    return "{\"property\":\""
        + property
        + "\",\"graph\":{\"directed\":"
        + directed
        + ",\"nodes\":"
        + nodes
        + ",\"edges\":"
        + edges
        + "},\"certificate\":"
        + certificate
        + "}";
  }
}
