package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopFalseStatementAndInvalidProofConflationBlackBoxTest {
  @TempDir Path temporaryDirectory;

  @Test
  void statementRefutationAndProofFailureHaveDistinctAuthorityOutcomes() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("truth-proof-separation"),
            "truth-proof-separation")) {
      harness.freezeAndCreateRoute();
      harness.runSingleLegacyClaimRound(
          0,
          "false-hamiltonian-claim",
          "FALSE_LOCAL_REFUTED: Every connected finite graph has a Hamiltonian cycle; P4 is "
              + "an exact counterexample.");
      harness.runSingleLegacyClaimRound(
          1,
          "true-tree-leaves-claim",
          "FALSE_LOCAL_BAD_PROOF: Every finite tree with at least two vertices has at least "
              + "two leaves; the supplied proof contains a local invalid inference.");

      Map<String, ClaimStatus> statuses =
          harness.lemmaMemory().claims().stream()
              .collect(Collectors.toMap(ClaimCard::claimId, ClaimCard::status));
      ClaimStatus falseStatement = statuses.get("false-hamiltonian-claim");
      ClaimStatus trueBadProof = statuses.get("true-tree-leaves-claim");
      long distinctOutcomes = java.util.stream.Stream.of(falseStatement, trueBadProof).distinct().count();

      System.out.println("FALSE_STATEMENT_CASES=1");
      System.out.println("TRUE_BAD_PROOF_CASES=1");
      System.out.println("DISTINCT_OUTCOMES_EXPECTED=2");
      System.out.println("DISTINCT_OUTCOMES_ACTUAL=" + distinctOutcomes);
      assertThat(falseStatement).isEqualTo(ClaimStatus.REJECTED);
      assertThat(trueBadProof).isEqualTo(ClaimStatus.UNCERTAIN);
    }
  }
}
