package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ComputationContractRepairCapabilityTest {
  @Test
  void repairPromptCannotInventAuthorityOrUnregisteredTools() {
    assertThat(PromptCatalog.instruction("computation_contract_repair"))
        .contains("registered typed method")
        .contains("Preserve experiment_id")
        .doesNotContain("mark the claim verified");
  }
}
