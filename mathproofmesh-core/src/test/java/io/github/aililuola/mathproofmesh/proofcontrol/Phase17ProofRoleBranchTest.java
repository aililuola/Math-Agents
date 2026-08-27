package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class Phase17ProofRoleBranchTest {

  @Test
  void counterexampleAndEveryGoalRelationMapToTheDocumentedRole() {
    ProofRoleClassifier classifier = new ProofRoleClassifier();
    assertThat(classifier.classify("anything", null, true, false, false))
        .isEqualTo(ProofControlModels.ProofRole.COUNTEREXAMPLE);
    assertThat(classifier.classify("anything", link(ProofControlModels.GoalRelation.EQUIVALENT),
            false, false, false))
        .isEqualTo(ProofControlModels.ProofRole.EQUIVALENT_REDUCTION);
    assertThat(classifier.classify("anything", link(ProofControlModels.GoalRelation.NECESSARY_ONLY),
            false, false, false))
        .isEqualTo(ProofControlModels.ProofRole.NECESSARY_CONDITION);
    assertThat(classifier.classify("anything", link(ProofControlModels.GoalRelation.HEURISTIC_ONLY),
            false, false, false))
        .isEqualTo(ProofControlModels.ProofRole.SEARCH_HEURISTIC);
    assertThat(classifier.classify("anything", link(ProofControlModels.GoalRelation.SUFFICIENT),
            false, false, true))
        .isEqualTo(ProofControlModels.ProofRole.CORE_BRIDGE);
    assertThat(classifier.classify("anything", link(ProofControlModels.GoalRelation.SUFFICIENT),
            false, false, false))
        .isEqualTo(ProofControlModels.ProofRole.SUFFICIENT_CONDITION);
  }

  @Test
  void unrelatedUnknownAndUnlinkedTextCoverBoundHeuristicAndLemmaBranches() {
    ProofRoleClassifier classifier = new ProofRoleClassifier();
    for (ProofControlModels.GoalRelation relation :
        List.of(ProofControlModels.GoalRelation.UNRELATED, ProofControlModels.GoalRelation.UNKNOWN)) {
      assertThat(classifier.classify("technical identity", link(relation), false, true, false))
          .isEqualTo(ProofControlModels.ProofRole.SEARCH_HEURISTIC);
      assertThat(classifier.classify("technical identity", link(relation), false, false, false))
          .isEqualTo(ProofControlModels.ProofRole.TECHNICAL_LEMMA);
    }
    assertThat(classifier.classify("an upper bound", null, false, false, false))
        .isEqualTo(ProofControlModels.ProofRole.AUXILIARY_BOUND);
    assertThat(classifier.classify("a lower bound", null, false, false, false))
        .isEqualTo(ProofControlModels.ProofRole.AUXILIARY_BOUND);
    assertThat(classifier.classify("上界", null, false, false, false))
        .isEqualTo(ProofControlModels.ProofRole.AUXILIARY_BOUND);
    assertThat(classifier.classify("下界", null, false, false, false))
        .isEqualTo(ProofControlModels.ProofRole.AUXILIARY_BOUND);
    assertThat(classifier.classify(null, null, false, true, false))
        .isEqualTo(ProofControlModels.ProofRole.SEARCH_HEURISTIC);
    assertThat(classifier.classify(null, null, false, false, false))
        .isEqualTo(ProofControlModels.ProofRole.TECHNICAL_LEMMA);
  }

  private static ProofControlModels.GoalLink link(ProofControlModels.GoalRelation relation) {
    return new ProofControlModels.GoalLink(
        "link",
        "subject",
        "goal",
        relation,
        ProofControlModels.ScopeRelation.SAME,
        List.of(),
        List.of(),
        List.of(),
        1.0,
        1.0,
        List.of());
  }
}
