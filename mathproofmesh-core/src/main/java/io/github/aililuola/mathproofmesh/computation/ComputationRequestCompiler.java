package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ExperimentProgram;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.util.List;

/** Server-owned capability resolution and typed contract compilation. */
public final class ComputationRequestCompiler {
  private final String runId;
  private final ComputationCapabilityRegistry capabilities;

  public ComputationRequestCompiler(
      String runId, ComputationCapabilityRegistry capabilities) {
    this.runId = require(runId, "runId");
    this.capabilities = java.util.Objects.requireNonNull(capabilities, "capabilities");
  }

  public ValidatedComputationRequest compile(
      ExperimentSpec spec,
      ExperimentProgram program,
      ComputationExecutionContext context) {
    java.util.Objects.requireNonNull(spec, "spec");
    java.util.Objects.requireNonNull(context, "context");
    RegisteredComputationCapability capability = capabilities.capability(spec.method());
    List<String> issues = ContractsFunctions.validateExperimentContract(spec);
    if (!issues.isEmpty()) {
      throw new IllegalArgumentException("invalid typed computation contract: " + String.join("; ", issues));
    }
    if (spec.maxCases() > capability.descriptor().resourceEnvelope().maxCases()) {
      throw new IllegalArgumentException("request exceeds capability maxCases");
    }
    ComputationResourceGuard.validateRequest(
        spec, capability.descriptor().resourceEnvelope());
    if (spec.method() == ComputationMethod.SANDBOXED_PYTHON) {
      rejectNativeBypass(spec);
      if (program == null) {
        throw new IllegalArgumentException("sandbox capability requires an admitted program");
      }
    } else if (program != null) {
      throw new IllegalArgumentException("typed capabilities do not accept model-generated programs");
    }
    String executionId =
        ComputationExecutionLedger.stableExecutionId(
            runId, context.routeId(), spec, capability.descriptor());
    return new ValidatedComputationRequest(spec, capability.descriptor(), program, executionId);
  }

  private static void rejectNativeBypass(ExperimentSpec spec) {
    String gap = spec.typedToolGap() == null ? "" : spec.typedToolGap().toLowerCase(java.util.Locale.ROOT);
    boolean nativeIntent =
        gap.contains("linear algebra")
            || gap.contains("matrix")
            || gap.contains("finite set")
            || gap.contains("bijection")
            || gap.contains("hypergraph")
            || gap.contains("transversal")
            || gap.contains("graph certificate")
            || gap.contains("number theory")
            || gap.contains("recurrence");
    if (nativeIntent) {
      throw new IllegalArgumentException("NATIVE_CAPABILITY_PRECEDENCE");
    }
  }

  private static String require(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }
}
