package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyCandidateStatus;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyMechanismPrimitive;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopV12StrategyDiversityMigrationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void preservesLegacyActiveRoutesAndRebuildsOnlyDeterministicIssue007Projections()
      throws Exception {
    Path runDirectory = temporaryDirectory.resolve("v12");
    DesktopSolveCheckpoint version13;
    try (DesktopStrategyPortfolioTestHarness source =
        DesktopStrategyPortfolioTestHarness.open(runDirectory, "strategy-v12-migration")) {
      source.freeze();
      source.setStrategies(DesktopStrategyPortfolioTestHarness.fourIndependent("legacy"));
      source.generateAndAdmit();
      version13 = source.checkpointRoundTrip();
    }

    ObjectNode legacyJson =
        (ObjectNode) ContractObjectMapper.parseTree(ContractObjectMapper.write(version13));
    legacyJson.put("schemaVersion", 12);
    legacyJson.remove("strategyCandidates");
    legacyJson.remove("strategyMechanisms");
    legacyJson.remove("strategyPreflights");
    legacyJson.remove("strategyPortfolios");
    legacyJson.remove("portfolioReplenishments");
    DesktopSolveCheckpoint legacy =
        ContractObjectMapper.read(legacyJson.toString(), DesktopSolveCheckpoint.class);
    assertThat(legacy.schemaVersion()).isEqualTo(12);

    DesktopSolveCheckpoint migrated;
    DesktopStrategyPortfolioTestHarness.ProductionState firstRestore;
    try (DesktopStrategyPortfolioTestHarness restored =
        DesktopStrategyPortfolioTestHarness.open(runDirectory, "strategy-v12-migration")) {
      restored.restore(legacy);
      firstRestore = restored.state();

      assertThat(restored.admittedStrategies()).hasSize(4);
      assertThat(restored.routeStrategyIds()).isEqualTo(firstRestore.admittedStrategyIds());
      assertThat(restored.candidates().snapshot().records().values())
          .allMatch(record -> record.status() == StrategyCandidateStatus.LEGACY_ACTIVE);
      assertThat(restored.preflights().snapshot().reports()).isEmpty();
      assertThat(restored.mechanisms().snapshot().legacyActiveStrategyIds()).hasSize(4);
      assertThat(restored.mechanisms().snapshot().profiles().values())
          .allMatch(
              profile ->
                  profile.primitives().equals(java.util.Set.of(StrategyMechanismPrimitive.UNKNOWN)));
      assertThat(restored.providerStrategyCalls()).isZero();
      migrated = restored.checkpointRoundTrip();
      assertThat(migrated.schemaVersion()).isEqualTo(DesktopSolveCheckpoint.CURRENT_SCHEMA_VERSION);
    }

    try (DesktopStrategyPortfolioTestHarness restoredAgain =
        DesktopStrategyPortfolioTestHarness.open(runDirectory, "strategy-v12-migration")) {
      restoredAgain.restore(migrated);
      assertThat(restoredAgain.state()).isEqualTo(firstRestore);
      assertThat(restoredAgain.providerStrategyCalls()).isZero();
      assertThat(restoredAgain.preflights().snapshot().reports()).isEmpty();
    }
  }
}
