package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.computation.ComputationEvidenceGate;
import io.github.aililuola.mathproofmesh.computation.ComputationHandlerRegistry;
import io.github.aililuola.mathproofmesh.computation.InMemoryComputationCache;
import io.github.aililuola.mathproofmesh.contract.ComputationDecisionAction;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopNotRefutedNoFactPromotionTest {
  @TempDir Path temporaryDirectory;

  @Test
  void boundedNonRefutationCannotBecomeAFact() {
    var broker = DesktopComputationIssue010Support.broker("not-refuted", temporaryDirectory, new InMemoryComputationCache());
    var outcome = DesktopComputationIssue010Support.run(broker, DesktopComputationIssue010Support.boundedObservation("bounded", 1), "bounded", 0);
    var descriptor = ComputationHandlerRegistry.javaOnly().capabilityRegistry()
        .capability(outcome.result().method()).descriptor();
    assertThat(ComputationEvidenceGate.evaluate(outcome.result(), outcome.verificationReceipt(), descriptor).factAdmissible())
        .isFalse();
    assertThat(outcome.applicationReceipt().action())
        .isEqualTo(ComputationDecisionAction.RECORD_BOUNDED_OBSERVATION);
  }
}
