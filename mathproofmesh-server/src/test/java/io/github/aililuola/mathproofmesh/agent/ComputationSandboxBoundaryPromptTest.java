package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.config.ComputationConfig;
import org.junit.jupiter.api.Test;

class ComputationSandboxBoundaryPromptTest {
  @Test
  void sandboxIsLastResortAndDisabledByDefault() {
    assertThat(PromptCatalog.instruction("independent_exploration"))
        .contains("sandboxed_python is a last resort")
        .contains("Prefer registered typed computation methods");
    assertThat(ComputationConfig.defaults().sandboxedPythonEnabled())
        .isFalse();
  }
}
