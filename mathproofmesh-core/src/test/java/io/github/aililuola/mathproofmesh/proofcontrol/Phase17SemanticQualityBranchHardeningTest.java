package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.Obligation;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.ObligationDomain;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.ObligationKind;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.ObligationStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class Phase17SemanticQualityBranchHardeningTest {

  @Test
  void statementsExerciseAcceptNormalizeSearchAndRejectWithAllStructuralFlags() {
    SemanticQualityGate gate = new SemanticQualityGate();
    var accepted = gate.assessStatement("For every integer n, n = n");
    assertThat(accepted.verdict()).isEqualTo(SemanticQualityGate.Verdict.ACCEPT);
    assertThat(accepted.accepted()).isTrue();
    assertThat(accepted.quarantined()).isFalse();
    assertThat(accepted.eligibleForCoreDebt()).isTrue();
    assertThat(accepted.eligibleForBottleneck()).isTrue();

    var implicit = gate.assessStatement("The sequence a_n is bounded");
    assertThat(implicit.verdict()).isEqualTo(SemanticQualityGate.Verdict.ACCEPT);
    assertThat(implicit.normalizationNeeds()).containsExactly("explicit_index_quantifier");

    for (String statement :
        List.of(
            "find an invariant",
            "prove the theorem",
            "complete the argument",
            "construct something suitable",
            "analyze carefully",
            "search for another route")) {
      var assessment = gate.assessStatement(statement);
      assertThat(assessment.placeholder()).as(statement).isTrue();
      assertThat(assessment.quarantined()).isTrue();
      assertThat(assessment.verdict())
          .isIn(
              SemanticQualityGate.Verdict.SEARCH_OR_PROCESS_TASK,
              SemanticQualityGate.Verdict.REJECT);
    }

    var normalization = gate.assessStatement("For every integer alpha beta gamma");
    assertThat(normalization.verdict()).isEqualTo(SemanticQualityGate.Verdict.NEEDS_NORMALIZATION);
    assertThat(normalization.normalizationNeeds()).contains("missing_explicit_relation");
    var singleObject = gate.assessStatement("x = x");
    assertThat(singleObject.verdict()).isEqualTo(SemanticQualityGate.Verdict.NEEDS_NORMALIZATION);
    assertThat(singleObject.normalizationNeeds()).contains("missing_explicit_objects");
    var emptyTokens = gate.assessStatement("=");
    assertThat(emptyTokens.verdict()).isEqualTo(SemanticQualityGate.Verdict.REJECT);
    assertThat(emptyTokens.rejectionReasons())
        .contains("not_truth_apt", "missing_explicit_objects", "missing_quantifier_or_scope");
  }

  @Test
  void selfImplicationSourceIdentityGoalDuplicationAndExecutableKindsAreCovered() {
    SemanticQualityGate gate = new SemanticQualityGate();
    Obligation self = obligation("self", "if x = 0 then x = 0", ObligationKind.LEMMA, List.of());
    var internal = gate.assess(self, "claim", null, null, null);
    assertThat(internal.selfImplication()).isTrue();
    assertThat(internal.verdict()).isEqualTo(SemanticQualityGate.Verdict.REJECT);

    Obligation source = obligation("source", "For every n, n = n", ObligationKind.LEMMA, List.of());
    assertThat(gate.assess(source, "claim", null, "  ", null).selfImplication()).isFalse();
    assertThat(gate.assess(source, "claim", null, "for every n, n = n", null).selfImplication())
        .isTrue();

    Obligation main =
        obligation("main", "For every n, n = n", ObligationKind.MAIN_GOAL, List.of());
    Obligation copy =
        obligation("copy", "show that for every n, n = n", ObligationKind.LEMMA, List.of());
    assertThat(gate.assess(copy, "claim", main, null, null).duplicatesMainGoal()).isTrue();
    assertThat(gate.assess(main, "claim", main, null, null).duplicatesMainGoal()).isFalse();

    Obligation assumed =
        obligation("assumed", "alpha equals beta", ObligationKind.LEMMA, List.of("alpha is fixed"));
    assertThat(gate.assess(assumed, "claim", null, null, null).hasScope()).isTrue();
    assertThat(gate.assess(assumed, "claim", null, null, " execute ").executable()).isTrue();
    assertThat(gate.assess(assumed, "claim", null, null, " ").executable()).isTrue();

    Obligation construction =
        obligation("construction", "object x equals object y", ObligationKind.CONSTRUCTION, List.of());
    Obligation computation =
        obligation(
            "computation", "integer x equals integer y", ObligationKind.COMPUTATION_QUESTION, List.of());
    assertThat(gate.assess(construction, "claim", null, null, null).executable()).isTrue();
    assertThat(gate.assess(computation, "claim", null, null, null).executable()).isTrue();
  }

  @Test
  void everyExplicitAndMarkerDomainReceivesTheCorrectFatalVerdict() {
    SemanticQualityGate gate = new SemanticQualityGate();
    java.util.Map<String, ObligationDomain> explicit =
        java.util.Map.ofEntries(
            java.util.Map.entry("strategy", ObligationDomain.MATHEMATICAL),
            java.util.Map.entry("search", ObligationDomain.SEARCH),
            java.util.Map.entry("process", ObligationDomain.PROCESS),
            java.util.Map.entry("tool", ObligationDomain.TOOL),
            java.util.Map.entry("verification", ObligationDomain.VERIFICATION),
            java.util.Map.entry("protocol", ObligationDomain.PROTOCOL),
            java.util.Map.entry("safety", ObligationDomain.SAFETY));
    for (var entry : explicit.entrySet()) {
      var assessment =
          gate.assess(
              obligation(
                  "domain-" + entry.getKey(),
                  "For every integer n, n = n",
                  ObligationKind.LEMMA,
                  List.of()),
              entry.getKey(),
              null,
              null,
              null);
      assertThat(assessment.domain()).isEqualTo(entry.getValue());
      if (entry.getValue() == ObligationDomain.MATHEMATICAL) {
        assertThat(assessment.verdict()).isEqualTo(SemanticQualityGate.Verdict.ACCEPT);
      } else if (entry.getValue().name().matches("SEARCH|PROCESS|TOOL|VERIFICATION")) {
        assertThat(assessment.verdict())
            .isEqualTo(SemanticQualityGate.Verdict.SEARCH_OR_PROCESS_TASK);
      } else {
        assertThat(assessment.verdict()).isEqualTo(SemanticQualityGate.Verdict.REJECT);
      }
    }

    java.util.Map<String, ObligationDomain> markers =
        java.util.Map.of(
            "api key must be hidden", ObligationDomain.SAFETY,
            "output json in required format", ObligationDomain.PROTOCOL,
            "checkpoint policy workflow state", ObligationDomain.PROCESS,
            "run the tool under runtime limit", ObligationDomain.TOOL,
            "independent reviewer must verify the proof", ObligationDomain.VERIFICATION,
            "find a representation", ObligationDomain.SEARCH);
    for (var entry : markers.entrySet()) {
      assertThat(gate.assessStatement(entry.getKey()).domain()).isEqualTo(entry.getValue());
    }
  }

  private static Obligation obligation(
      String id, String statement, ObligationKind kind, List<String> assumptions) {
    return new Obligation(
        id, statement, kind, ObligationStatus.OPEN, assumptions, List.of(), 1.0d, 0.0d);
  }
}
