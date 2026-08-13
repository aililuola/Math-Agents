package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ProblemSemanticViewCandidate;
import io.github.aililuola.mathproofmesh.contract.SemanticInvariantAudit;
import io.github.aililuola.mathproofmesh.proofcontrol.ExactGoalContractChecker.AuditResult;
import io.github.aililuola.mathproofmesh.proofcontrol.ExactGoalContractChecker.ConclusionShape;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExactGoalContractCheckerTest {
  static final String SOURCE =
      "\u8bc1\u660e\uff1a\u5b58\u5728\u6b63\u6574\u6570 T \u548c L\uff0c\u4f7f\u5f97\u5bf9\u4e8e\u6bcf\u4e00\u4e2a\u6b63\u6574\u6570 n\uff0c"
          + "\u90fd\u6709 $a_{n+T}=a_n+L$\u3002";

  private record SemanticCase(
      String name, String candidate, boolean expectedPass, String expectedFailureCode) {}

  @Test
  void printsAndAssertsExactGoalContractDiagnostic() {
    ExactGoalContractChecker checker = new ExactGoalContractChecker();
    List<SemanticCase> cases =
        List.of(
            new SemanticCase(
                "exact_global_translation",
                "There exist positive integers T and L such that for every positive integer n, "
                    + "$a_{n+T}=a_n+L$.",
                true,
                null),
            new SemanticCase(
                "global_to_eventual",
                "There exist T,L such that for all sufficiently large n, $a_{n+T}=a_n+L$.",
                false,
                "INDEX_SCOPE_MISMATCH"),
            new SemanticCase(
                "translation_to_arithmetic",
                "The sequence is an arithmetic progression with constant difference.",
                false,
                "CONCLUSION_SHAPE_MISMATCH"),
            new SemanticCase(
                "correct_formula_wrong_explanation",
                "There exist T,L such that for every n, $a_{n+T}=a_n+L$; in other words, "
                    + "the sequence is an arithmetic progression.",
                false,
                "MIXED_CONCLUSION_INTERPRETATION"),
            new SemanticCase(
                "exists_forall_to_forall_exists",
                "For every n, there exist T,L such that $a_{n+T}=a_n+L$.",
                false,
                "QUANTIFIER_ORDER_MISMATCH"),
            new SemanticCase(
                "uniform_to_per_instance",
                "For each n choose T(n),L(n) such that $a_{n+T(n)}=a_n+L(n)$.",
                false,
                "UNIFORM_WITNESS_SCOPE_MISMATCH"));

    System.out.println("GOAL CONTRACT DIAGNOSTIC");
    System.out.println("---------------------------------------------------------------");
    System.out.printf("%-38s %-8s %s%n", "CASE", "RESULT", "FAILURE CODES");
    for (SemanticCase semanticCase : cases) {
      AuditResult result = checker.audit(SOURCE, semanticCase.candidate());
      List<String> failureCodes = failureCodes(result);
      System.out.printf(
          "%-38s %-8s %s%n",
          semanticCase.name(), result.passed() ? "PASS" : "BLOCK", failureCodes);

      assertThat(result.passed()).isEqualTo(semanticCase.expectedPass());
      if (semanticCase.expectedFailureCode() != null) {
        assertThat(failureCodes).contains(semanticCase.expectedFailureCode());
      }
    }
    System.out.println("---------------------------------------------------------------");
  }

  @Test
  void extractsOrderedQuantifiersAndClassifiesTheSourceGoal() {
    var signature = new ExactGoalContractChecker().extract(SOURCE);

    assertThat(signature.indexScope()).isEqualTo(ProofControlModels.IndexScope.ALL);
    assertThat(signature.uniformityScope())
        .isEqualTo(ProofControlModels.UniformityScope.UNIFORM);
    assertThat(signature.quantifierSkeleton())
        .extracting(ExactGoalContractChecker.QuantifierAtom::kind)
        .containsExactly("exists", "forall");
    assertThat(signature.quantifierSkeleton().getFirst().variables())
        .containsExactly("t", "l");
    assertThat(signature.quantifierSkeleton().getLast().variables())
        .containsExactly("n");
    assertThat(signature.conclusionShape())
        .isEqualTo(ConclusionShape.INDEX_TRANSLATION_PERIODICITY);
  }

  @Test
  void equivalentVariableRenamingPassesTheStructuralClassifier() {
    AuditResult result =
        new ExactGoalContractChecker()
            .audit(
                SOURCE,
                "There exist P,Q such that for every k, $b_{k+P}=b_k+Q$.");

    assertThat(result.passed()).isTrue();
    assertThat(result.candidate().conclusionShape())
        .isEqualTo(ConclusionShape.INDEX_TRANSLATION_PERIODICITY);
  }

  @Test
  void unsupportedOrdinaryProblemIsNotRejectedByTheExactGoalClassifier() {
    String source = "\u8bc1\u660e $x^2 >= 0$\u3002";
    String translation = "Prove $x^2 >= 0$.";
    ExactGoalContractChecker checker = new ExactGoalContractChecker();
    AuditResult result = checker.audit(source, translation);

    assertThat(result.passed()).isTrue();
    assertThat(result.findings())
        .allSatisfy(finding -> assertThat(finding.status()).isEqualTo("not_applicable"));

    var view =
        new ProblemSemanticViewService(checker)
            .build(
                source,
                new ProblemSemanticViewCandidate(
                    0.99d, translation, List.of(), true, true, true, true));
    assertThat(view.status()).isEqualTo("usable");
  }

  private static List<String> failureCodes(AuditResult result) {
    return result.findings().stream()
        .filter(finding -> "fail".equals(finding.status()))
        .map(SemanticInvariantAudit::detail)
        .map(detail -> detail.contains(":") ? detail.substring(0, detail.indexOf(':')) : detail)
        .distinct()
        .toList();
  }
}
