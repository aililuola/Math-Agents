package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.ExperimentProgram;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.util.Objects;

public record ValidatedComputationRequest(
    ExperimentSpec spec,
    ComputationCapabilityDescriptor capability,
    ExperimentProgram program,
    String executionId) {
  public ValidatedComputationRequest {
    spec = Objects.requireNonNull(spec, "spec");
    capability = Objects.requireNonNull(capability, "capability");
    executionId = executionId == null ? "" : executionId.strip();
    if (executionId.isEmpty()) {
      throw new IllegalArgumentException("executionId is required");
    }
    if (spec.method() != capability.method()) {
      throw new IllegalArgumentException("request method does not match capability");
    }
  }
}
