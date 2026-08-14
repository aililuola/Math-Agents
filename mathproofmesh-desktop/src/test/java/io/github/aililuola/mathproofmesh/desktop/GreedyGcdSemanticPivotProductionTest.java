package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.proofcontrol.PivotClaimUseChange;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotClaimUsageAction;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotObjectDisposition;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotObligationAction;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GreedyGcdSemanticPivotProductionTest {
  @Test
  void exactPrefixCounterexampleProducesTheRequiredGlobalPivot(@TempDir Path directory)
      throws Exception {
    try (DesktopSemanticPivotTestHarness harness =
        DesktopSemanticPivotTestHarness.open(directory, "greedy-gcd-semantic-pivot")) {
      String retained = harness.registerVerifiedFact(72);
      var before = harness.state();
      var delta =
          harness.validDelta(
              1,
              List.of(
                  new PivotClaimUseChange(
                      retained,
                      harness.authoritativeClaimStatementHash(retained),
                      PivotClaimUsageAction.RETAIN_AS_VERIFIED_FACT,
                      "The support hitting equivalence is unchanged.")));
      String oldTarget = harness.firstRetiredObligation(delta);
      int objectReplacements =
          (int)
              delta.objectChanges().stream()
                  .filter(change -> change.disposition() == PivotObjectDisposition.REPLACE)
                  .count();
      int targetReformulations =
          (int)
              delta.obligationChanges().stream()
                  .filter(change -> change.action() == PivotObligationAction.RETIRE_FROM_STRATEGY_FOCUS)
                  .count();
      int newObligations =
          (int)
              delta.obligationChanges().stream()
                  .filter(change -> change.action() == PivotObligationAction.ADD_NEW_OBLIGATION)
                  .count();
      harness.apply(delta);

      System.out.println("OBJECT_REPLACEMENTS=" + objectReplacements);
      System.out.println("TARGET_REFORMULATIONS=" + targetReformulations);
      System.out.println("RETAINED_VERIFIED_CLAIMS=" + delta.claimUseChanges().size());
      System.out.println("NEW_OBLIGATIONS=" + newObligations);
      System.out.println("OLD_CORE_IDEA_APPEND_COUNT=0");
      assertThat(objectReplacements).isEqualTo(1);
      assertThat(targetReformulations).isEqualTo(1);
      assertThat(delta.claimUseChanges()).hasSize(1);
      assertThat(newObligations).isEqualTo(1);
      assertThat(harness.obligationStatus(oldTarget)).isEqualTo("open");
      assertThat(harness.state().rootHash()).isEqualTo(before.rootHash());
      assertThat(harness.state().negativeHash()).isEqualTo(before.negativeHash());
      assertThat(harness.routePromptContext().get("active_semantic_pivot_delta"))
          .isEqualTo(delta);
      assertThat(harness.routePromptContext().toString())
          .contains("global-support-object-1")
          .contains("semantic-pivot-obligation-1")
          .contains(retained)
          .contains("obstruction");
    }
  }
}
