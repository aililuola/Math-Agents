package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ProblemSemanticViewCandidate;
import io.github.aililuola.mathproofmesh.contract.SemanticInvariantAudit;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProblemSemanticViewDeterministicAuditTest {
  private static final String WRONG_TRANSLATION =
      "There exist positive integers T and L such that for all sufficiently large positive "
          + "integers n, $a_{n+T}=a_n+L$; equivalently, the sequence eventually becomes an "
          + "arithmetic progression.";

  @Test
  void modelReportedTrueFlagsCannotBypassDeterministicAudit() {
    ProblemSemanticViewCandidate candidate =
        new ProblemSemanticViewCandidate(
            0.99d,
            WRONG_TRANSLATION,
            List.of("model claims exact preservation"),
            true,
            true,
            true,
            true);

    var view = new ProblemSemanticViewService().build(ExactGoalContractCheckerTest.SOURCE, candidate);

    assertThat(view.status()).isEqualTo("rejected");
    assertThat(view.deterministicAuditPassed()).isFalse();
    assertThat(view.auditFindings())
        .extracting(SemanticInvariantAudit::invariant)
        .contains("index_scope", "conclusion_shape");
  }
}
