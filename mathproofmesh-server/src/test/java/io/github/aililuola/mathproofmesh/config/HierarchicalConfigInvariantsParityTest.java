package io.github.aililuola.mathproofmesh.config;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HierarchicalConfigInvariantsParityTest {
  @Test
  void activeHierarchicalTopologyRequiresContinuation() {
    String yaml =
        "agents:\n"
            + "  - id: mock-agent\n"
            + "    provider: mock\n"
            + "    model: mock-model\n"
            + "topology:\n"
            + "  mode: hierarchical_sparse\n"
            + "  proof_graph:\n"
            + "    enabled: false\n"
            + "    mode: 'off'\n"
            + "  typed_communication:\n"
            + "    enabled: true\n"
            + "  inspiration:\n"
            + "    enabled: false\n"
            + "    mode: 'off'\n"
            + "continuation:\n"
            + "  enabled: false\n";

    ConfigValidationException failure =
        assertThrows(
            ConfigValidationException.class,
            () -> new StrictYamlConfigLoader().read(yaml));

    assertTrue(
        failure.getMessage().contains("requires continuation.enabled=true"),
        failure::getMessage);
  }
}
