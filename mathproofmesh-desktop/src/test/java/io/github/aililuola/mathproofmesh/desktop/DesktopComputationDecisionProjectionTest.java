package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.computation.InMemoryComputationCache;
import io.github.aililuola.mathproofmesh.contract.ComputationDecisionAction;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopComputationDecisionProjectionTest {
  @TempDir Path temporaryDirectory;

  @Test
  void typedResultsProjectOnlyTheirMatchingDecisionBranches() {
    var broker = DesktopComputationIssue010Support.broker("desktop-decisions", temporaryDirectory, new InMemoryComputationCache());
    var finite = DesktopComputationIssue010Support.run(broker, DesktopComputationIssue010Support.finiteMap("finite"), "finite", 0);
    var refuted = DesktopComputationIssue010Support.run(broker, DesktopComputationIssue010Support.graphCounterexample("refuted", 1), "refuted", 0);
    assertThat(finite.applicationReceipt().action())
        .isEqualTo(ComputationDecisionAction.SATISFY_FINITE_DOMAIN_OBLIGATION);
    assertThat(refuted.applicationReceipt().action())
        .isEqualTo(ComputationDecisionAction.SUBMIT_EXACT_COUNTEREXAMPLE);
  }
}
