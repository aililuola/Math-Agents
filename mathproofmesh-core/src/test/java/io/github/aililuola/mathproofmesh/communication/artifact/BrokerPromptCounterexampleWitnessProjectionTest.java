package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import org.junit.jupiter.api.Test;

class BrokerPromptCounterexampleWitnessProjectionTest {
  @Test
  void promptProjectionPreservesCounterexampleWitnessAndObstructionRepairDetails() {
    BrokerArtifactPromptProjectionService projection = new BrokerArtifactPromptProjectionService();
    String counterexample = ContractObjectMapper.write(
        projection.project(BrokerArtifactTestFixtures.counterexample()));
    String obstruction = ContractObjectMapper.write(
        projection.project(BrokerArtifactTestFixtures.obstruction()));
    int witnessLosses = counterexample.contains("path on four vertices") ? 0 : 1;
    int obstructionLosses =
        obstruction.contains("MISSING_JUSTIFICATION")
                && obstruction.contains("LOCAL_PATCH")
                && obstruction.contains("Prove the finite-cardinality bridge")
            ? 0 : 1;

    System.out.println("COUNTEREXAMPLE_WITNESS_PROJECTION_LOSSES=" + witnessLosses);
    System.out.println("OBSTRUCTION_DETAIL_PROJECTION_LOSSES=" + obstructionLosses);
    assertThat(witnessLosses).isZero();
    assertThat(obstructionLosses).isZero();
  }
}
