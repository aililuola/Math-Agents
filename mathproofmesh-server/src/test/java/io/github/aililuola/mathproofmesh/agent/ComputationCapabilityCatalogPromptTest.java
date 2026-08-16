package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ToolRequest;
import org.junit.jupiter.api.Test;

class ComputationCapabilityCatalogPromptTest {
  @Test
  void generatedToolRequestSchemaTracksEveryComputationMethod() {
    var values = PromptJsonSchema.forType(ToolRequest.class)
        .path("properties").path("kind").path("enum");
    assertThat(values).hasSize(ComputationMethod.values().length);
    assertThat(values.toString()).contains("number_theory_check", "exact_linear_algebra", "finite_set_map_check");
  }
}
