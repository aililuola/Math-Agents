package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ProblemContract;
import io.github.aililuola.mathproofmesh.contract.ProblemKind;
import io.github.aililuola.mathproofmesh.contract.ProblemSemanticViewCandidate;
import io.github.aililuola.mathproofmesh.contract.TaskRequirement;
import java.util.List;
import org.junit.jupiter.api.Test;

class RootGoalContractMultiRoundTest {
  private static final String EXACT =
      "There exist positive integers T and L such that for every positive integer n, "
          + "$a_{n+T}=a_n+L$.";
  private static final String EVENTUAL =
      "There exist positive integers T and L such that for all sufficiently large positive "
          + "integers n, $a_{n+T}=a_n+L$.";
  private static final String ARITHMETIC =
      "There exist positive integers T and L such that for every positive integer n, "
          + "$a_{n+T}=a_n+L$; equivalently, the sequence is an arithmetic progression.";
  private static final String SWAPPED =
      "For every positive integer n there exist positive integers T and L such that "
          + "$a_{n+T}=a_n+L$.";

  @Test
  void immutableRootGoalSurvivesTwentyProductionAttachmentRounds() {
    ExactGoalContractChecker checker = new ExactGoalContractChecker();
    ProblemSemanticViewService service = new ProblemSemanticViewService(checker);
    RootGoalContract rootGoal =
        RootGoalContract.freeze(ExactGoalContractCheckerTest.SOURCE, checker);
    String initialRootHash = rootGoal.sourceStatementHash();
    ProblemContract activeProblem = problemContract();
    int hashChanges = 0;
    int rootReplacements = 0;
    int rejectedLeaks = 0;

    for (int round = 0; round < 20; round++) {
      String statement = candidateFor(round);
      boolean expectedUsable = EXACT.equals(statement);
      ProblemSemanticViewService.Attachment attachment =
          service.attach(rootGoal, activeProblem, candidate(statement));
      activeProblem = attachment.authoritativeProblem();
      var view = attachment.auditedView();

      if (!initialRootHash.equals(rootGoal.sourceStatementHash())) {
        hashChanges++;
      }
      if (!ExactGoalContractCheckerTest.SOURCE.equals(activeProblem.exactStatement())) {
        rootReplacements++;
      }
      if (!expectedUsable
          && activeProblem.semanticView() != null
          && statement.equals(activeProblem.semanticView().englishStatement())) {
        rejectedLeaks++;
      }

      assertThat(rootGoal.sourceStatementHash()).isEqualTo(initialRootHash);
      assertThat(rootGoal.sourceStatement()).isEqualTo(ExactGoalContractCheckerTest.SOURCE);
      assertThat(activeProblem.goalHash()).isEqualTo(initialRootHash);
      assertThat(activeProblem.exactStatement()).isEqualTo(ExactGoalContractCheckerTest.SOURCE);
      assertThat(view.status()).isEqualTo(expectedUsable ? "usable" : "rejected");
      assertThat(view.authoritative()).isFalse();
      if (expectedUsable) {
        assertThat(attachment.candidateAttached()).isTrue();
        assertThat(activeProblem.semanticView()).isEqualTo(view);
      } else {
        assertThat(attachment.candidateAttached()).isFalse();
        assertThat(view.deterministicAuditPassed()).isFalse();
        if (activeProblem.semanticView() != null) {
          assertThat(activeProblem.semanticView().englishStatement()).isNotEqualTo(statement);
        }
      }
    }

    System.out.println("MULTI_ROUND_COUNT=20");
    System.out.println("ROOT_HASH_CHANGES=" + hashChanges);
    System.out.println("ROOT_GOAL_REPLACEMENTS=" + rootReplacements);
    System.out.println("REJECTED_SIDECAR_LEAKS=" + rejectedLeaks);
    assertThat(hashChanges).isZero();
    assertThat(rootReplacements).isZero();
    assertThat(rejectedLeaks).isZero();
  }

  private static String candidateFor(int round) {
    return switch (round % 6) {
      case 0, 2, 5 -> EXACT;
      case 1 -> EVENTUAL;
      case 3 -> ARITHMETIC;
      case 4 -> SWAPPED;
      default -> throw new AssertionError("unreachable round");
    };
  }

  private static ProblemSemanticViewCandidate candidate(String statement) {
    return new ProblemSemanticViewCandidate(
        0.99d, statement, List.of("fixed candidate sequence"), true, true, true, true);
  }

  private static ProblemContract problemContract() {
    String source = ExactGoalContractCheckerTest.SOURCE;
    return new ProblemContract(
        List.of(),
        source,
        null,
        List.of(),
        List.of(),
        source,
        null,
        List.of(),
        null,
        null,
        1.0d,
        null,
        List.of(),
        "original",
        source,
        source,
        "zh-CN",
        null,
        ProblemKind.PROOF,
        null,
        List.of(TaskRequirement.PROOF));
  }
}
