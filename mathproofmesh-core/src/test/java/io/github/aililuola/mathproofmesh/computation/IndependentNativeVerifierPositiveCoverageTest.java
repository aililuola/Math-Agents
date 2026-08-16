package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ComputationCertificateEnvelope;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ComputationVerificationReceipt;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class IndependentNativeVerifierPositiveCoverageTest {
  private static final String GEOMETRY_POINTS =
      "{\"A\":[0,0],\"B\":[1,0],\"C\":[2,0],\"D\":[0,1],"
          + "\"E\":[1,1],\"F\":[2,1],\"G\":[3,2]}";

  @ParameterizedTest
  @MethodSource("numberTheoryCases")
  void independentlyReplaysEveryNumberTheoryAuthorityPath(
      String arguments, ExperimentOutcome expected) {
    ComputationVerificationReceipt receipt = verify(ComputationMethod.NUMBER_THEORY_CHECK, arguments, "{}");

    assertThat(receipt.valid()).isTrue();
    assertThat(produce(ComputationMethod.NUMBER_THEORY_CHECK, arguments, "{}").outcome())
        .isEqualTo(expected);
  }

  @ParameterizedTest
  @MethodSource("geometryCases")
  void independentlyReplaysEveryExactGeometryAuthorityPath(
      String assertion, ExperimentOutcome expected) {
    String arguments = "{\"points\":" + GEOMETRY_POINTS + ",\"assertion\":" + assertion + "}";
    ComputationVerificationReceipt receipt = verify(ComputationMethod.EXACT_GEOMETRY, arguments, "{}");

    assertThat(receipt.valid()).isTrue();
    assertThat(produce(ComputationMethod.EXACT_GEOMETRY, arguments, "{}").outcome())
        .isEqualTo(expected);
  }

  @ParameterizedTest
  @MethodSource("boundedNativeCases")
  void independentlyReplaysEveryBoundedNativeCounterexampleOrCertificate(
      ComputationMethod method, String arguments, String domains, ExperimentOutcome expected) {
    ComputationVerificationReceipt receipt = verify(method, arguments, domains);

    assertThat(receipt.valid()).isTrue();
    assertThat(produce(method, arguments, domains).outcome()).isEqualTo(expected);
  }

  private static Stream<Arguments> numberTheoryCases() {
    return Stream.of(
        Arguments.of(
            "{\"operation\":\"multiplicative_order\",\"a\":2,\"n\":5}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"multiplicative_order\",\"a\":2,\"n\":5,\"claimed\":3}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of(
            "{\"operation\":\"multiplicative_order\",\"a\":-1,\"n\":7,\"claimed\":2}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"crt\",\"residues\":[2,3],\"moduli\":[3,5]}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"crt\",\"residues\":[2,3],\"moduli\":[3,5],\"claimed\":4}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of(
            "{\"operation\":\"crt\",\"residues\":[0,1],\"moduli\":[2,2]}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"crt\",\"residues\":[1,3],\"moduli\":[2,4]}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"p_adic_valuation\",\"p\":2,\"expression\":\"x\","
                + "\"assignment\":{\"x\":8},\"claimed\":3}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"p_adic_valuation\",\"p\":2,\"expression\":\"x\","
                + "\"assignment\":{\"x\":8},\"claimed\":2}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of(
            "{\"operation\":\"p_adic_valuation\",\"p\":3,\"expression\":\"-27\"}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"primitive_root\",\"n\":2}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"primitive_root\",\"n\":7,\"claimed\":3}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"primitive_root\",\"n\":7,\"claimed\":2}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of(
            "{\"operation\":\"primitive_root\",\"n\":8}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"primitive_root\",\"n\":8,\"claimed\":true}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of(
            "{\"operation\":\"primitive_root\",\"n\":8,\"claimed\":false}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"primitive_root\",\"n\":7,\"claimed\":false}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of(
            "{\"operation\":\"is_prime\",\"n\":2,\"claimed\":true}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"is_prime\",\"n\":37}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"is_prime\",\"n\":1}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"is_prime\",\"n\":25,\"claimed\":true}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of(
            "{\"operation\":\"is_prime\",\"n\":2047}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"factorization\",\"n\":360}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"factorization\",\"n\":97,\"claimed\":{\"97\":1}}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"factorization\",\"n\":360,"
                + "\"claimed\":{\"2\":2,\"3\":2,\"5\":1}}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND));
  }

  private static Stream<Arguments> geometryCases() {
    return Stream.of(
        geometry("{\"kind\":\"collinear\",\"points\":[\"A\",\"B\",\"C\"]}", true),
        geometry(
            "{\"kind\":\"collinear\",\"points\":[\"A\",\"B\",\"D\"]}", false),
        geometry(
            "{\"kind\":\"collinear\",\"points\":[\"A\",\"B\",\"D\"],\"expected\":false}",
            true),
        geometry(
            "{\"kind\":\"orientation\",\"points\":[\"A\",\"B\",\"D\"],\"expected_sign\":1}",
            true),
        geometry(
            "{\"kind\":\"orientation\",\"points\":[\"A\",\"D\",\"B\"],\"expected_sign\":1}",
            false),
        geometry(
            "{\"kind\":\"equal_distance\",\"points\":[\"A\",\"B\",\"D\",\"E\"]}",
            true),
        geometry(
            "{\"kind\":\"equal_distance\",\"points\":[\"A\",\"B\",\"A\",\"G\"]}",
            false),
        geometry(
            "{\"kind\":\"point_on_segment\",\"points\":[\"B\",\"A\",\"C\"]}",
            true),
        geometry(
            "{\"kind\":\"point_on_segment\",\"points\":[\"D\",\"A\",\"C\"]}",
            false),
        geometry(
            "{\"kind\":\"concyclic\",\"points\":[\"A\",\"B\",\"E\",\"D\"]}",
            true),
        geometry(
            "{\"kind\":\"concyclic\",\"points\":[\"A\",\"B\",\"C\",\"F\"]}",
            false),
        geometry(
            "{\"kind\":\"parallel\",\"points\":[\"A\",\"B\",\"D\",\"E\"]}",
            true),
        geometry(
            "{\"kind\":\"parallel\",\"points\":[\"A\",\"B\",\"A\",\"D\"]}",
            false),
        geometry(
            "{\"kind\":\"perpendicular\",\"points\":[\"A\",\"B\",\"A\",\"D\"]}",
            true),
        geometry(
            "{\"kind\":\"perpendicular\",\"points\":[\"A\",\"B\",\"D\",\"E\"]}",
            false),
        geometry(
            "{\"kind\":\"equal_angle\",\"points\":[\"D\",\"A\",\"B\",\"E\",\"B\",\"C\"]}",
            true),
        geometry(
            "{\"kind\":\"equal_angle\",\"points\":[\"D\",\"A\",\"B\",\"G\",\"B\",\"C\"]}",
            false));
  }

  private static Arguments geometry(String assertion, boolean holds) {
    return Arguments.of(
        assertion,
        holds ? ExperimentOutcome.CERTIFIED : ExperimentOutcome.COUNTEREXAMPLE_FOUND);
  }

  private static Stream<Arguments> boundedNativeCases() {
    return Stream.of(
        Arguments.of(
            ComputationMethod.MODULAR_EXHAUSTIVE,
            "{\"lhs\":\"x^5\",\"rhs\":\"x\",\"modulus\":5,"
                + "\"finite_reduction\":true,\"reduction_justification\":\"all residues\"}",
            "{\"x\":{\"min\":0,\"max\":4}}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            ComputationMethod.MODULAR_EXHAUSTIVE,
            "{\"lhs\":\"x\",\"rhs\":\"0\",\"relation\":\"eq\",\"modulus\":3}",
            "{}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of(
            ComputationMethod.BOUNDED_INTEGER_SEARCH,
            "{\"target\":{\"lhs\":\"x*x\",\"rhs\":\"x\",\"relation\":\"eq\"}}",
            "{\"x\":{\"min\":0,\"max\":3}}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of(
            ComputationMethod.BOUNDED_INTEGER_SEARCH,
            "{\"target\":{\"lhs\":\"x\",\"rhs\":\"0\",\"relation\":\"eq\"},"
                + "\"constraints\":[{\"lhs\":\"x\",\"rhs\":\"2\",\"relation\":\"lt\"}]}",
            "{\"x\":{\"min\":0,\"max\":1}}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of(
            ComputationMethod.RECURRENCE_CHECK,
            "{\"initial_values\":[0,1],\"coefficients\":[1,1],"
                + "\"start_n\":0,\"end_n\":8,\"claimed_expression\":\"n\"}",
            "{}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of(
            ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
            "{\"initial_values\":[0],\"length\":4,\"candidate_min\":0,"
                + "\"candidate_max\":20,\"rule\":\"avoid_forbidden_differences\","
                + "\"forbidden_differences\":[1],\"claimed_values\":[0,2,5,7]}",
            "{}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of(
            ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
            "{\"initial_values\":[1,2],\"length\":4,\"candidate_max\":10,"
                + "\"rule\":\"avoid_three_term_arithmetic_progression\","
                + "\"claimed_values\":[1,2,4,6]}",
            "{}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of(
            ComputationMethod.CANDIDATE_PERIOD_CHECK,
            "{\"values\":[1,2,1,2,1,3],\"candidate_period\":2}",
            "{}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of(
            ComputationMethod.CANDIDATE_PERIOD_CHECK,
            "{\"values\":[9,1,2,1,3],\"candidate_period\":2,\"start_index\":1}",
            "{}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND));
  }

  private static ComputationVerificationReceipt verify(
      ComputationMethod method, String arguments, String domains) {
    ExperimentSpec spec = ComputationFixtures.spec(method, arguments, domains);
    ComputationResultArtifact result = produce(method, arguments, domains);
    RegisteredComputationCapability capability =
        ComputationIssue010TestSupport.registry().capability(method);
    ValidatedComputationRequest request =
        new ValidatedComputationRequest(spec, capability.descriptor(), null, "positive-replay");
    ComputationCertificateEnvelope certificate =
        ComputationCertificateFactory.create(request, result);
    return capability.verifier().verify(request, result, certificate);
  }

  private static ComputationResultArtifact produce(
      ComputationMethod method, String arguments, String domains) {
    ExperimentSpec spec = ComputationFixtures.spec(method, arguments, domains);
    RegisteredComputationCapability capability =
        ComputationIssue010TestSupport.registry().capability(method);
    ValidatedComputationRequest request =
        new ValidatedComputationRequest(spec, capability.descriptor(), null, "positive-producer");
    ProducedComputation produced = capability.producer().execute(request);
    HandlerEvidence evidence = produced.evidence();
    return new ComputationResultArtifact(
        spec.requestHash(),
        spec.executionHash(),
        evidence.outcome(),
        evidence.evidenceStrength(),
        evidence.scope(),
        evidence.counterexample(),
        evidence.certificate(),
        evidence.exactArithmetic(),
        evidence.casesChecked(),
        0.001d,
        produced.producerId(),
        produced.producerVersion(),
        "",
        null);
  }
}
