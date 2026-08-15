package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimSemanticContextBinding;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopLocalClaimQuantifierIsolationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void forallAndExistsLocalClaimsHaveDistinctCourtIdentity() throws Exception {
    var forall = freeze("forall", "local-forall", temporaryDirectory.resolve("forall"));
    var exists = freeze("exists", "local-exists", temporaryDirectory.resolve("exists"));

    int falseRefutations =
        forall.claimSemanticHash().equals(exists.claimSemanticHash())
                || forall.statementCaseId().equals(exists.statementCaseId())
            ? 1
            : 0;
    assertThat(falseRefutations).isZero();
    System.out.println("LOCAL_CLAIM_QUANTIFIER_FALSE_REFUTATIONS=" + falseRefutations);
  }

  private static io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.FrozenClaimSnapshot
      freeze(String kind, String runId, Path directory) throws Exception {
    String claimId = "local-quantifier";
    QuantifierSpec quantifier =
        new QuantifierSpec("z", "D", kind, 0, List.of(), "local-z");
    VariableBinding variable =
        new VariableBinding(List.of("z"), "z", "D", "attempt-local", "local-z");
    ClaimSemanticContextBinding binding =
        new ClaimSemanticContextBinding(
            claimId,
            "@claim",
            List.of(),
            List.of(quantifier),
            List.of(variable),
            List.of("D"),
            "positive");
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(directory, runId)) {
      harness.freezeAndCreateRoute();
      harness.installModernLocalClaimRound(0, claimId, "P(z)", binding);
      harness.integrateInstalledRound();
      return harness.frozenClaim(claimId);
    }
  }
}
