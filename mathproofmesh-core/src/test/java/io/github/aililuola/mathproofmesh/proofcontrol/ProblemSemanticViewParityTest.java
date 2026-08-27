package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ProblemSemanticView;
import io.github.aililuola.mathproofmesh.contract.ProblemSemanticViewCandidate;
import io.github.aililuola.mathproofmesh.contract.SemanticInvariantAudit;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ProblemSemanticViewParityTest {
  record SemanticCase(
      String name,
      String source,
      String candidate,
      String expectedStatus,
      Set<String> expectedFailureCodes) {}

  static Stream<SemanticCase> semanticCases() {
    return Stream.of(
        new SemanticCase(
            "exact_global_translation",
            ExactGoalContractCheckerTest.SOURCE,
            "Prove that there exist positive integers T and L such that for every positive "
                + "integer n, $a_{n+T}=a_n+L$.",
            "usable",
            Set.of()),
        new SemanticCase(
            "changed_formula",
            "\u8bc1\u660e $x^2 >= 0$\u3002",
            "Prove $y^3 < 1$.",
            "rejected",
            Set.of()),
        new SemanticCase(
            "reversed_task",
            "\u8bc1\u660e $x^2 >= 0$\u3002",
            "Disprove $x^2 >= 0$.",
            "rejected",
            Set.of()),
        new SemanticCase(
            "reversed_quantifiers",
            ExactGoalContractCheckerTest.SOURCE,
            "Prove that for every positive integer n there exist positive integers T and L "
                + "such that $a_{n+T}=a_n+L$.",
            "rejected",
            Set.of("QUANTIFIER_ORDER_MISMATCH")),
        new SemanticCase(
            "global_to_eventual",
            ExactGoalContractCheckerTest.SOURCE,
            "Prove that there exist positive integers T and L such that for all sufficiently "
                + "large positive integers n, $a_{n+T}=a_n+L$.",
            "rejected",
            Set.of("INDEX_SCOPE_MISMATCH")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("semanticCases")
  void runsTheNamedSemanticCase(SemanticCase semanticCase) {
    ProblemSemanticView view =
        new ProblemSemanticViewService()
            .build(semanticCase.source(), candidate(semanticCase.candidate()));

    assertThat(view.status()).isEqualTo(semanticCase.expectedStatus());
    assertThat(failureCodes(view)).containsAll(semanticCase.expectedFailureCodes());
  }

  @Test
  void legacyUsableViewWithoutDeterministicAuditIsQuarantined() {
    ProblemSemanticView legacy =
        new ProblemSemanticView(
            List.of(),
            false,
            0.99d,
            false,
            "Prove $x^2 >= 0$.",
            List.of(),
            List.of(),
            List.of("$x^2 >= 0$"),
            "zh",
            "legacy-hash",
            "usable");

    assertThat(legacy.status()).isEqualTo("rejected");
    assertThat(legacy.authoritative()).isFalse();
  }

  @Test
  void semanticViewCandidateIsAlwaysOnlyModelSelfReport() {
    ProblemSemanticViewCandidate candidate = candidate("Prove $x^2 >= 0$.");

    assertThat(candidate.preservesConclusion()).isTrue();
    assertThat(candidate.preservesDomains()).isTrue();
    assertThat(candidate.preservesHypotheses()).isTrue();
    assertThat(candidate.preservesQuantifiers()).isTrue();
    assertThat(candidate).isNotInstanceOf(ProblemSemanticView.class);
  }

  @Test
  void auditedViewRemainsNonAuthoritativeAndKeepsTheSourceHash() {
    var view =
        new ProblemSemanticViewService()
            .build(
                "\u8bc1\u660e $x^2 >= 0$\u3002",
                candidate("Prove $x^2 >= 0$."));

    assertThat(view.status()).isEqualTo("usable");
    assertThat(view.authoritative()).isFalse();
    assertThat(view.sourceStatementHash()).isNotBlank();
  }

  private static ProblemSemanticViewCandidate candidate(String statement) {
    return new ProblemSemanticViewCandidate(
        0.99d, statement, List.of("model self-report"), true, true, true, true);
  }

  private static Set<String> failureCodes(ProblemSemanticView view) {
    return view.auditFindings().stream()
        .filter(finding -> "fail".equals(finding.status()))
        .map(SemanticInvariantAudit::detail)
        .map(detail -> detail.contains(":") ? detail.substring(0, detail.indexOf(':')) : detail)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }
}
