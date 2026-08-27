package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import org.junit.jupiter.api.Test;

class ComputationOutputLimitTest {
  @Test
  void oversizedResultCannotBecomeADurableArtifact() {
    var descriptor = ComputationIssue010TestSupport.linearAlgebraSpec();
    var capability =
        ComputationIssue010TestSupport.registry().capability(descriptor.method()).descriptor();
    var result =
        new ComputationResultArtifact(
            descriptor.requestHash(),
            descriptor.executionHash(),
            ExperimentOutcome.NOT_REFUTED,
            EvidenceStrength.BOUNDED_EVIDENCE,
            ComputationJson.object().put("oversized", "x".repeat(2_000)),
            null,
            null,
            true,
            1,
            0.01d,
            capability.producerId(),
            capability.producerVersion(),
            "",
            null);
    var envelope =
        new ComputationResourceEnvelope(100, 1.0d, 1_000_000L, 512);

    assertThatThrownBy(() -> ComputationResourceGuard.validateResult(result, envelope))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("COMPUTATION_OUTPUT_LIMIT");
  }
}
