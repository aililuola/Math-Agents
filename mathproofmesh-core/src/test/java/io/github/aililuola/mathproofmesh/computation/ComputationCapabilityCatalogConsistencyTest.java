package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ComputationCapabilityCatalogConsistencyTest {
  @Test
  void registryCatalogAndEnumCoverExactlyTheSameMethods() {
    var catalog = ContractsFunctions.experimentToolCatalog(Set.of());
    assertThat(catalog).hasSize(ComputationMethod.values().length);
    assertThat(catalog.stream().map(node -> node.path("method").asText()))
        .containsExactlyInAnyOrder(
            java.util.Arrays.stream(ComputationMethod.values())
                .map(ComputationMethod::value)
                .toArray(String[]::new));
  }
}
