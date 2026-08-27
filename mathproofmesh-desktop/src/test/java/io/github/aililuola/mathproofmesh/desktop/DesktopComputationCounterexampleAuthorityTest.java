package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.computation.ComputationEvidenceGate;
import io.github.aililuola.mathproofmesh.computation.InMemoryComputationCache;
import io.github.aililuola.mathproofmesh.contract.ComputationVerifiedAuthority;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopComputationCounterexampleAuthorityTest {
  @TempDir Path temporaryDirectory;

  @Test
  void exactGraphWitnessRequiresIndependentReceiptBeforeRefutationAuthority() {
    var broker = DesktopComputationIssue010Support.broker("desktop-counterexample", temporaryDirectory, new InMemoryComputationCache());
    var outcome = DesktopComputationIssue010Support.run(broker, DesktopComputationIssue010Support.graphCounterexample("graph", 2), "graph", 0);
    assertThat(outcome.verificationReceipt().valid()).isTrue();
    assertThat(outcome.verificationReceipt().authority())
        .isEqualTo(ComputationVerifiedAuthority.EXACT_COUNTEREXAMPLE);
    assertThat(outcome.authority()).isEqualTo(ComputationEvidenceGate.EvidenceAuthority.REFUTED);
  }
}
