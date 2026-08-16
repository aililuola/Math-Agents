package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.ContractValidationException;
import io.github.aililuola.mathproofmesh.contract.ToolRequest;
import org.junit.jupiter.api.Test;

class ComputationCapabilityContractsTest {
  @Test
  void toolRequestUsesTheStableComputationMethodCatalog() {
    ObjectNode empty = (ObjectNode) ContractObjectMapper.parseTree("{}");
    assertThat(new ToolRequest(empty, empty, "number_theory_check", 10, "check", null).kind())
        .isEqualTo("number_theory_check");
    assertThatThrownBy(() -> new ToolRequest(empty, empty, "invented_solver", 10, "check", null))
        .isInstanceOf(ContractValidationException.class);
  }
}
