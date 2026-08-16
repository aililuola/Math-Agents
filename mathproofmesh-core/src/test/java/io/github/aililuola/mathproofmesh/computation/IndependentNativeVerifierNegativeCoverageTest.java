package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class IndependentNativeVerifierNegativeCoverageTest {
  @Test
  void rejectsMutationsOfEveryNumberTheoryAuthorityPayload() {
    rejectNumberTheoryMutations(
        "{\"operation\":\"multiplicative_order\",\"a\":2,\"n\":5}",
        payload -> payload.put("operation", "crt"),
        payload -> payload.put("a", 3),
        payload -> payload.put("n", 7),
        payload -> payload.put("order", 3));
    rejectNumberTheoryMutations(
        "{\"operation\":\"crt\",\"residues\":[2,3],\"moduli\":[3,5]}",
        payload -> payload.put("operation", "is_prime"),
        payload -> payload.put("solvable", false),
        payload -> payload.put("solution", 4),
        payload -> payload.put("combined_modulus", 16));
    rejectNumberTheoryMutations(
        "{\"operation\":\"crt\",\"residues\":[0,1],\"moduli\":[2,2]}",
        payload -> payload.put("solvable", true),
        payload -> payload.remove("inconsistency_witness"),
        payload -> payload.withObject("inconsistency_witness").put("index_pair", "invalid"),
        payload ->
            payload
                .withObject("inconsistency_witness")
                .putArray("index_pair")
                .add(-1)
                .add(1));
    rejectNumberTheoryMutations(
        "{\"operation\":\"p_adic_valuation\",\"p\":2,\"expression\":\"x\","
            + "\"assignment\":{\"x\":8},\"claimed\":3}",
        payload -> payload.put("p", 3),
        payload -> payload.put("value", 4),
        payload -> payload.put("valuation", 2));
    rejectNumberTheoryMutations(
        "{\"operation\":\"primitive_root\",\"n\":7}",
        payload -> payload.put("exists", false),
        payload -> payload.put("primitive_root", 5),
        payload -> payload.put("totient", 5));
    rejectNumberTheoryMutations(
        "{\"operation\":\"primitive_root\",\"n\":8}",
        payload -> payload.put("exists", true));
    rejectNumberTheoryMutations(
        "{\"operation\":\"is_prime\",\"n\":37}",
        payload -> payload.put("n", 41),
        payload -> payload.put("is_prime", false));
    rejectNumberTheoryMutations(
        "{\"operation\":\"factorization\",\"n\":360}",
        payload -> payload.put("operation", "crt"),
        payload -> payload.putObject("factors").put("2", 1));
  }

  @Test
  void rejectsInvalidNumberTheoryDomainsBeforeTrustingProducerPayload() {
    ComputationResultArtifact baseline =
        produce(
            ComputationMethod.NUMBER_THEORY_CHECK,
            "{\"operation\":\"is_prime\",\"n\":37}",
            "{}");
    List<String> invalidArguments =
        List.of(
            "{\"operation\":\"unknown\"}",
            "{\"operation\":\"multiplicative_order\",\"a\":1,\"n\":1}",
            "{\"operation\":\"multiplicative_order\",\"a\":2,\"n\":1000000001}",
            "{\"operation\":\"multiplicative_order\",\"a\":2,\"n\":4}",
            "{\"operation\":\"crt\",\"residues\":[],\"moduli\":[]}",
            "{\"operation\":\"crt\",\"residues\":[1],\"moduli\":[2,3]}",
            "{\"operation\":\"crt\",\"residues\":[1],\"moduli\":[0]}",
            "{\"operation\":\"crt\",\"residues\":[1],\"moduli\":[1000000001]}",
            "{\"operation\":\"p_adic_valuation\",\"p\":4,\"expression\":\"8\"}",
            "{\"operation\":\"p_adic_valuation\",\"p\":2,\"expression\":\"0\"}",
            "{\"operation\":\"primitive_root\",\"n\":1}",
            "{\"operation\":\"primitive_root\",\"n\":1000000001}",
            "{\"operation\":\"is_prime\",\"n\":1000000000000000001}",
            "{\"operation\":\"factorization\",\"n\":1}",
            "{\"operation\":\"factorization\",\"n\":1000000000001}");

    for (String arguments : invalidArguments) {
      ExperimentSpec spec =
          ComputationFixtures.spec(ComputationMethod.NUMBER_THEORY_CHECK, arguments);
      assertThat(IndependentNumberTheoryCertificateVerifier.verify(spec, baseline)).isFalse();
    }
    assertThat(
            IndependentNumberTheoryCertificateVerifier.verify(
                ComputationFixtures.spec(
                    ComputationMethod.NUMBER_THEORY_CHECK,
                    "{\"operation\":\"is_prime\",\"n\":37}"),
                copy(baseline, ExperimentOutcome.INCONCLUSIVE, null, null, baseline.scope(), baseline.casesChecked())))
        .isFalse();
  }

  @Test
  void rejectsMutationsOfBoundedNativeCertificatesAndWitnesses() {
    String modularCertificate =
        "{\"lhs\":\"x^5\",\"rhs\":\"x\",\"modulus\":5,"
            + "\"finite_reduction\":true,\"reduction_justification\":\"all residues\"}";
    ComputationResultArtifact certified =
        produce(ComputationMethod.MODULAR_EXHAUSTIVE, modularCertificate, "{\"x\":{\"min\":0,\"max\":4}}");
    ExperimentSpec certifiedSpec =
        ComputationFixtures.spec(
            ComputationMethod.MODULAR_EXHAUSTIVE,
            modularCertificate,
            "{\"x\":{\"min\":0,\"max\":4}}");
    assertNativeCertificateMutation(
        certifiedSpec, certified, payload -> payload.put("all_cases_satisfied", false));
    assertNativeCertificateMutation(certifiedSpec, certified, payload -> payload.put("modulus", 7));
    assertNativeCertificateMutation(certifiedSpec, certified, payload -> payload.putArray("variables").add("y"));
    ObjectNode incompleteScope = certified.scope();
    incompleteScope.put("complete_domain", false);
    assertThat(
            IndependentNativeComputationVerifier.verifyModular(
                certifiedSpec,
                copy(
                    certified,
                    certified.outcome(),
                    certified.counterexample(),
                    certified.certificate(),
                    incompleteScope,
                    certified.casesChecked())))
        .isFalse();
    ObjectNode partialResidues = certified.scope();
    partialResidues.put("full_residue_coverage", false);
    assertThat(
            IndependentNativeComputationVerifier.verifyModular(
                certifiedSpec,
                copy(
                    certified,
                    certified.outcome(),
                    certified.counterexample(),
                    certified.certificate(),
                    partialResidues,
                    certified.casesChecked())))
        .isFalse();
    assertThat(
            IndependentNativeComputationVerifier.verifyModular(
                certifiedSpec,
                copy(
                    certified,
                    certified.outcome(),
                    certified.counterexample(),
                    certified.certificate(),
                    certified.scope(),
                    certified.casesChecked() + 1)))
        .isFalse();

    rejectCounterexampleMutations(
        ComputationMethod.MODULAR_EXHAUSTIVE,
        "{\"lhs\":\"x\",\"rhs\":\"0\",\"relation\":\"eq\",\"modulus\":3}",
        "{}",
        IndependentNativeComputationVerifier::verifyModular,
        payload -> payload.put("modulus", 5),
        payload -> payload.putObject("assignment").put("y", 1),
        payload -> payload.put("lhs_mod", 0),
        payload -> payload.put("rhs_mod", 1));
    rejectCounterexampleMutations(
        ComputationMethod.BOUNDED_INTEGER_SEARCH,
        "{\"target\":{\"lhs\":\"x*x\",\"rhs\":\"x\",\"relation\":\"eq\"}}",
        "{\"x\":{\"min\":0,\"max\":3}}",
        IndependentNativeComputationVerifier::verifyBoundedIntegerSearch,
        payload -> payload.putObject("assignment").put("y", 2),
        payload -> payload.withObject("assignment").put("x", 4),
        payload -> payload.put("lhs_value", 0),
        payload -> payload.put("rhs_value", 9),
        payload -> payload.put("relation", "ne"));
    rejectCounterexampleMutations(
        ComputationMethod.RECURRENCE_CHECK,
        "{\"initial_values\":[0,1],\"coefficients\":[1,1],"
            + "\"start_n\":0,\"end_n\":8,\"claimed_expression\":\"n\"}",
        "{}",
        IndependentNativeComputationVerifier::verifyRecurrence,
        payload -> payload.put("n", -1),
        payload -> payload.put("n", 9),
        payload -> payload.put("actual", "999"),
        payload -> payload.put("claimed", "999"));
    rejectCounterexampleMutations(
        ComputationMethod.CANDIDATE_PERIOD_CHECK,
        "{\"values\":[1,2,1,2,1,3],\"candidate_period\":2}",
        "{}",
        IndependentNativeComputationVerifier::verifyCandidatePeriod,
        payload -> payload.put("index", 4),
        payload -> payload.put("prior_index", 1),
        payload -> payload.put("value", "1"),
        payload -> payload.put("candidate_period", 3));
  }

  @Test
  void geometryFailsClosedForMalformedOrUnboundAssertions() {
    String points =
        "{\"A\":[0,0],\"B\":[1,0],\"C\":[2,0],\"D\":[0,1],"
            + "\"E\":[1,1],\"F\":[2,1]}";
    String valid =
        "{\"points\":" + points + ",\"assertion\":{\"kind\":\"collinear\",\"points\":[\"A\",\"B\",\"C\"]}}";
    ComputationResultArtifact baseline = produce(ComputationMethod.EXACT_GEOMETRY, valid, "{}");
    List<String> invalid =
        List.of(
            "{\"points\":{},\"assertion\":{\"kind\":\"collinear\",\"points\":[\"Z\"]}}",
            "{\"points\":" + points + ",\"assertion\":{\"kind\":\"unknown\",\"points\":[]}}",
            "{\"points\":" + points + ",\"assertion\":{\"kind\":\"collinear\",\"points\":[\"A\",\"B\"]}}",
            "{\"points\":" + points + ",\"assertion\":{\"kind\":\"collinear\",\"points\":[\"A\",\"B\",\"C\"],\"expected\":\"yes\"}}",
            "{\"points\":" + points + ",\"assertion\":{\"kind\":\"orientation\",\"points\":[\"A\",\"B\",\"D\"],\"expected_sign\":2}}",
            "{\"points\":" + points + ",\"assertion\":{\"kind\":\"orientation\",\"points\":[\"A\",\"B\",\"D\"],\"expected_sign\":-2}}",
            "{\"points\":" + points + ",\"assertion\":{\"kind\":\"parallel\",\"points\":[\"A\",\"A\",\"D\",\"E\"]}}",
            "{\"points\":" + points + ",\"assertion\":{\"kind\":\"parallel\",\"points\":[\"A\",\"B\",\"D\",\"D\"]}}",
            "{\"points\":" + points + ",\"assertion\":{\"kind\":\"equal_angle\",\"points\":[\"A\",\"A\",\"B\",\"D\",\"E\",\"F\"]}}",
            "{\"points\":{\"A\":[0]},\"assertion\":{\"kind\":\"collinear\",\"points\":[\"A\"]}}");
    for (String arguments : invalid) {
      assertThat(
              IndependentGeometryCertificateVerifier.verify(
                  ComputationFixtures.spec(ComputationMethod.EXACT_GEOMETRY, arguments),
                  baseline))
          .isFalse();
    }
    assertThat(
            IndependentGeometryCertificateVerifier.verify(
                ComputationFixtures.spec(ComputationMethod.EXACT_GEOMETRY, valid),
                copy(
                    baseline,
                    ExperimentOutcome.INCONCLUSIVE,
                    null,
                    null,
                    baseline.scope(),
                    baseline.casesChecked())))
        .isFalse();
  }

  @SafeVarargs
  private static void rejectNumberTheoryMutations(
      String arguments, Consumer<ObjectNode>... mutations) {
    ExperimentSpec spec = ComputationFixtures.spec(ComputationMethod.NUMBER_THEORY_CHECK, arguments);
    ComputationResultArtifact original = produce(ComputationMethod.NUMBER_THEORY_CHECK, arguments, "{}");
    for (Consumer<ObjectNode> mutation : mutations) {
      ObjectNode payload = authorityPayload(original);
      mutation.accept(payload);
      ComputationResultArtifact forged =
          original.outcome() == ExperimentOutcome.COUNTEREXAMPLE_FOUND
              ? copy(original, original.outcome(), payload, original.certificate(), original.scope(), original.casesChecked())
              : copy(original, original.outcome(), original.counterexample(), payload, original.scope(), original.casesChecked());
      assertThat(IndependentNumberTheoryCertificateVerifier.verify(spec, forged)).isFalse();
    }
  }

  private static void assertNativeCertificateMutation(
      ExperimentSpec spec,
      ComputationResultArtifact original,
      Consumer<ObjectNode> mutation) {
    ObjectNode certificate = original.certificate();
    mutation.accept(certificate);
    assertThat(
            IndependentNativeComputationVerifier.verifyModular(
                spec,
                copy(
                    original,
                    original.outcome(),
                    original.counterexample(),
                    certificate,
                    original.scope(),
                    original.casesChecked())))
        .isFalse();
  }

  @SafeVarargs
  private static void rejectCounterexampleMutations(
      ComputationMethod method,
      String arguments,
      String domains,
      NativeVerifier verifier,
      Consumer<ObjectNode>... mutations) {
    ExperimentSpec spec = ComputationFixtures.spec(method, arguments, domains);
    ComputationResultArtifact original = produce(method, arguments, domains);
    for (Consumer<ObjectNode> mutation : mutations) {
      ObjectNode counterexample = original.counterexample();
      mutation.accept(counterexample);
      assertThat(
              verifier.verify(
                  spec,
                  copy(
                      original,
                      original.outcome(),
                      counterexample,
                      original.certificate(),
                      original.scope(),
                      original.casesChecked())))
          .isFalse();
    }
  }

  private static ObjectNode authorityPayload(ComputationResultArtifact result) {
    return result.outcome() == ExperimentOutcome.COUNTEREXAMPLE_FOUND
        ? result.counterexample()
        : result.certificate();
  }

  private static ComputationResultArtifact produce(
      ComputationMethod method, String arguments, String domains) {
    ExperimentSpec spec = ComputationFixtures.spec(method, arguments, domains);
    RegisteredComputationCapability capability =
        ComputationIssue010TestSupport.registry().capability(method);
    ValidatedComputationRequest request =
        new ValidatedComputationRequest(spec, capability.descriptor(), null, "negative-replay");
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

  private static ComputationResultArtifact copy(
      ComputationResultArtifact source,
      ExperimentOutcome outcome,
      ObjectNode counterexample,
      ObjectNode certificate,
      ObjectNode scope,
      int casesChecked) {
    return new ComputationResultArtifact(
        source.requestHash(),
        source.executionHash(),
        outcome,
        source.evidenceStrength(),
        scope,
        counterexample,
        certificate,
        source.exactArithmetic(),
        casesChecked,
        source.runtimeSeconds(),
        source.producerId(),
        source.producerVersion(),
        source.error(),
        null);
  }

  @FunctionalInterface
  private interface NativeVerifier {
    boolean verify(ExperimentSpec spec, ComputationResultArtifact result);
  }
}
