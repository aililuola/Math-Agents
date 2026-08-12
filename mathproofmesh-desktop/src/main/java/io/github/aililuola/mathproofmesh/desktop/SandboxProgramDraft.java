package io.github.aililuola.mathproofmesh.desktop;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ContractNonNull;
import java.util.List;

/** Model-authored source only; schemas, image, limits, and execution stay server-owned. */
public record SandboxProgramDraft(
    @JsonProperty(value = "dependencies", required = true) @ContractNonNull
        List<String> dependencies,
    @JsonProperty(value = "source", required = true) @ContractNonNull String source) {
  public SandboxProgramDraft {
    dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    source = source == null ? "" : source.strip();
    if (source.isEmpty()) {
      throw new IllegalArgumentException("sandbox program source must not be blank");
    }
  }

  @Override
  public List<String> dependencies() {
    return List.copyOf(dependencies);
  }
}
