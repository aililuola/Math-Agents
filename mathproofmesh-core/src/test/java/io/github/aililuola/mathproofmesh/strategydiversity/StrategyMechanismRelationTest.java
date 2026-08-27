package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StrategyMechanismRelationTest {
  @Test
  void relationSeparatesIdentityFromCommonModeRisk() {
    StrategyCard first =
        StrategyDiversityTestFixtures.strategy(
            "first", "First", "Induction by deleting a leaf", "Shared unresolved bridge U.", 0.5d);
    StrategyCard duplicate =
        StrategyDiversityTestFixtures.strategy(
            "duplicate", "New title", "Induction by deleting a leaf", "Shared unresolved bridge U.", 0.5d);
    StrategyCard commonMode =
        StrategyDiversityTestFixtures.strategy(
            "common", "Common", "Longest-path extremal method", "Shared unresolved bridge U.", 0.5d);
    StrategyMechanismAnalyzer analyzer = new StrategyMechanismAnalyzer();
    StrategyMechanismSignature firstSignature = signature(analyzer, first);
    StrategyMechanismSignature duplicateSignature = signature(analyzer, duplicate);
    StrategyMechanismSignature commonSignature = signature(analyzer, commonMode);
    Set<String> unresolved = new LinkedHashSet<>(firstSignature.requiredClaimSemanticKeys());

    assertThat(
            analyzer.relation(
                firstSignature,
                duplicateSignature,
                unresolved,
                analyzer.profile(first, StrategyDiversityTestFixtures.blueprint(first)),
                analyzer.profile(duplicate, StrategyDiversityTestFixtures.blueprint(duplicate))))
        .isEqualTo(StrategyMechanismRelation.SAME_STRUCTURAL_MECHANISM);
    assertThat(
            analyzer.relation(
                firstSignature,
                commonSignature,
                unresolved,
                analyzer.profile(first, StrategyDiversityTestFixtures.blueprint(first)),
                analyzer.profile(commonMode, StrategyDiversityTestFixtures.blueprint(commonMode))))
        .isEqualTo(StrategyMechanismRelation.SHARED_UNRESOLVED_REQUIRED_CLAIM);
  }

  private static StrategyMechanismSignature signature(
      StrategyMechanismAnalyzer analyzer, StrategyCard strategy) {
    return analyzer.signature(
        StrategyDiversityTestFixtures.PROBLEM_HASH,
        StrategyDiversityTestFixtures.ROOT_HASH,
        strategy,
        StrategyDiversityTestFixtures.control(strategy),
        StrategyDiversityTestFixtures.blueprint(strategy));
  }
}
