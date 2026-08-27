package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import org.junit.jupiter.api.Test;

class StrategyMechanismRegistryTest {
  @Test
  void snapshotRestorePreservesStableMechanismIdentity() {
    StrategyCard strategy =
        StrategyDiversityTestFixtures.strategy(
            "route", "Route", "Kernel basis extension", "A basis of the kernel extends to the domain.", 0.5d);
    StrategyMechanismAnalyzer analyzer = new StrategyMechanismAnalyzer();
    StrategyMechanismSignature signature =
        analyzer.signature(
            StrategyDiversityTestFixtures.PROBLEM_HASH,
            StrategyDiversityTestFixtures.ROOT_HASH,
            strategy,
            StrategyDiversityTestFixtures.control(strategy),
            StrategyDiversityTestFixtures.blueprint(strategy));
    StrategyMechanismRegistry registry = new StrategyMechanismRegistry();
    registry.register(
        strategy.strategyId(),
        signature,
        analyzer.profile(strategy, StrategyDiversityTestFixtures.blueprint(strategy)),
        false);
    String before = registry.registryHash();

    StrategyMechanismRegistry restored = StrategyMechanismRegistry.restore(registry.snapshot());

    assertThat(restored.registryHash()).isEqualTo(before);
    assertThat(restored.signature(strategy.strategyId())).contains(signature);
    assertThatThrownBy(
            () ->
                restored.register(
                    strategy.strategyId(),
                    new StrategyMechanismSignature(
                        signature.problemHash(),
                        signature.rootGoalHash(),
                        signature.targetCanonicalIds(),
                        signature.requiredClaimSemanticKeys(),
                        signature.domainObjectRoleSignature(),
                        signature.representationSignature(),
                        signature.dependencyDagShapeHash(),
                        "changed",
                        signature.falsificationContractSignature(),
                        "changed"),
                    analyzer.profile(strategy, StrategyDiversityTestFixtures.blueprint(strategy)),
                    false))
        .isInstanceOf(IllegalStateException.class);
  }
}
