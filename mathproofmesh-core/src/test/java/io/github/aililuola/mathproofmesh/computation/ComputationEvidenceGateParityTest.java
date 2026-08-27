package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ComputationVerificationReceipt;
import io.github.aililuola.mathproofmesh.contract.ComputationVerificationStatus;
import io.github.aililuola.mathproofmesh.contract.ComputationVerifiedAuthority;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComputationEvidenceGateParityTest {

  @Test
  void test_bounded_computation_cannot_close_fact_gate() {
    ComputationBroker broker = ComputationFixtures.broker("bounded-fact-gate");
    var spec =
        ComputationFixtures.spec(
            ComputationMethod.CANDIDATE_PERIOD_CHECK,
            "{\"values\":[1,2,1,2],\"candidate_period\":2}");
    ExperimentResult result =
        ComputationFixtures.run(broker, spec);
    GateEvidence evidence = evidence(broker, spec.experimentId(), spec.method());

    assertThat(
            ComputationEvidenceGate.evaluate(result, evidence.receipt(), evidence.descriptor())
                .factAdmissible())
        .isFalse();
    assertThat(ComputationEvidenceGate.authority(result, evidence.receipt(), evidence.descriptor()))
        .isEqualTo(ComputationEvidenceGate.EvidenceAuthority.NOT_REFUTED);
  }

  @Test
  void test_formal_kernel_certificate_can_pass_fact_gate() {
    ExperimentResult result =
        result(
            ExperimentOutcome.CERTIFIED,
            EvidenceStrength.FORMAL_CERTIFICATE,
            ComputationMethod.LEAN_CHECK,
            true);
    ComputationCapabilityDescriptor descriptor = descriptor(ComputationMethod.LEAN_CHECK);
    ComputationVerificationReceipt receipt =
        receipt(result, descriptor, ComputationVerifiedAuthority.FORMAL_CERTIFICATE);

    assertThat(ComputationEvidenceGate.evaluate(result, receipt, descriptor).factAdmissible())
        .isTrue();
    assertThat(ComputationEvidenceGate.authority(result, receipt, descriptor))
        .isEqualTo(ComputationEvidenceGate.EvidenceAuthority.VERIFIED);
  }

  @Test
  void test_independently_verified_counterexample_is_negative_evidence() {
    ComputationBroker broker = ComputationFixtures.broker("negative-evidence");
    var spec =
        ComputationFixtures.spec(
            ComputationMethod.CANDIDATE_PERIOD_CHECK,
            "{\"values\":[1,2,1,3],\"candidate_period\":2}");
    ExperimentResult result = ComputationFixtures.run(broker, spec);
    GateEvidence evidence = evidence(broker, spec.experimentId(), spec.method());

    assertThat(ComputationEvidenceGate.authority(result, evidence.receipt(), evidence.descriptor()))
        .isEqualTo(ComputationEvidenceGate.EvidenceAuthority.REFUTED);
    assertThat(ComputationEvidenceGate.evaluate(result, evidence.receipt(), evidence.descriptor()))
        .satisfies(
            decision -> {
              assertThat(decision.factAdmissible()).isFalse();
              assertThat(decision.negativeAdmissible()).isTrue();
            });
  }

  @Test
  void test_complete_exhaustive_certificate_has_bounded_authority() {
    ComputationBroker broker = ComputationFixtures.broker("bounded-authority");
    var spec =
        ComputationFixtures.spec(
            ComputationMethod.MODULAR_EXHAUSTIVE,
            "{\"lhs\":\"x^5\",\"rhs\":\"x\",\"modulus\":5,"
                + "\"finite_reduction\":true,"
                + "\"reduction_justification\":\"Depends only on the residue class.\"}",
            "{\"x\":{\"min\":0,\"max\":4}}");
    ExperimentResult result = ComputationFixtures.run(broker, spec);
    GateEvidence evidence = evidence(broker, spec.experimentId(), spec.method());

    assertThat(ComputationEvidenceGate.authority(result, evidence.receipt(), evidence.descriptor()))
        .isEqualTo(ComputationEvidenceGate.EvidenceAuthority.VERIFIED_BOUNDED);
    assertThat(
            ComputationEvidenceGate.evaluate(result, evidence.receipt(), evidence.descriptor())
                .factAdmissible())
        .isTrue();
  }

  @Test
  void test_incomplete_or_non_kernel_certificates_remain_inconclusive() {
    ExperimentResult incomplete =
        result(
            ExperimentOutcome.CERTIFIED,
            EvidenceStrength.EXHAUSTIVE_CERTIFICATE,
            ComputationMethod.MODULAR_EXHAUSTIVE,
            false);
    ExperimentResult nonKernel =
        result(
            ExperimentOutcome.CERTIFIED,
            EvidenceStrength.FORMAL_CERTIFICATE,
            ComputationMethod.SANDBOXED_PYTHON,
            true);
    ExperimentResult inconclusive =
        result(
            ExperimentOutcome.INCONCLUSIVE,
            EvidenceStrength.HEURISTIC,
            ComputationMethod.NUMERIC_COUNTEREXAMPLE,
            false);

    for (ExperimentResult result : List.of(incomplete, nonKernel, inconclusive)) {
      assertThat(ComputationEvidenceGate.authority(result))
          .isEqualTo(ComputationEvidenceGate.EvidenceAuthority.INCONCLUSIVE);
      assertThat(ComputationEvidenceGate.evaluate(result).factAdmissible())
          .isFalse();
    }
  }

  @Test
  void test_heuristic_not_refuted_result_is_not_fact_evidence() {
    ExperimentResult result =
        result(
            ExperimentOutcome.NOT_REFUTED,
            EvidenceStrength.HEURISTIC,
            ComputationMethod.NUMERIC_COUNTEREXAMPLE,
            false);

    assertThat(ComputationEvidenceGate.authority(result))
        .isEqualTo(ComputationEvidenceGate.EvidenceAuthority.NOT_REFUTED);
    assertThat(ComputationEvidenceGate.evaluate(result).factAdmissible())
        .isFalse();
  }

  private static ExperimentResult result(
      ExperimentOutcome outcome,
      EvidenceStrength strength,
      ComputationMethod method,
      boolean completeDomain) {
    return new ExperimentResult(
        List.of(),
        false,
        1,
        outcome == ExperimentOutcome.CERTIFIED
            ? ComputationFixtures.object("{\"certificate\":\"checked\"}")
            : null,
        outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND
            ? ComputationFixtures.object("{\"witness\":1}")
            : null,
        null,
        null,
        strength,
        true,
        "evidence-gate-experiment",
        outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND,
        method,
        outcome,
        null,
        "path-evidence-gate",
        null,
        "a".repeat(64),
        null,
        0.01,
        ComputationFixtures.object("{\"complete_domain\":" + completeDomain + "}"),
        "The calculation result is checked before fact admission.",
        null,
        "evidence-gate-test",
        "evidence-gate-test@sha256:" + "b".repeat(64),
        List.of("The evidence authority is evaluated independently."));
  }

  private static GateEvidence evidence(
      ComputationBroker broker, String experimentId, ComputationMethod method) {
    ComputationExecutionOutcome outcome =
        broker.executionService().lastOutcome(experimentId).orElseThrow();
    return new GateEvidence(outcome.verificationReceipt(), descriptor(method));
  }

  private static ComputationCapabilityDescriptor descriptor(ComputationMethod method) {
    return ComputationHandlerRegistry.javaOnly()
        .capabilityRegistry()
        .capability(method)
        .descriptor();
  }

  private static ComputationVerificationReceipt receipt(
      ExperimentResult result,
      ComputationCapabilityDescriptor descriptor,
      ComputationVerifiedAuthority authority) {
    return new ComputationVerificationReceipt(
        "receipt-parity",
        "c".repeat(64),
        descriptor.verifierId(),
        descriptor.verifierVersion(),
        ComputationVerificationStatus.VALID,
        true,
        authority,
        CanonicalJson.stableHash(result.scope()),
        List.of("independent verifier receipt fixture"),
        null,
        null);
  }

  private record GateEvidence(
      ComputationVerificationReceipt receipt, ComputationCapabilityDescriptor descriptor) {}
}
