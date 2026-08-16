package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ExperimentProgram;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.util.Optional;

/** Stable method-to-handler registry; typed Java handlers take precedence over sidecars. */
public final class ComputationHandlerRegistry {
  public static final String JAVA_TOOL_VERSION = "java-mathproofmesh-computation/0.8.0";

  private final ExternalComputationHandler externalHandler;
  private final ComputationCapabilityRegistry capabilities;

  public ComputationHandlerRegistry(ExternalComputationHandler externalHandler) {
    this.externalHandler = externalHandler;
    this.capabilities = ComputationCapabilityRegistry.standard(externalHandler);
  }

  public static ComputationHandlerRegistry javaOnly() {
    return new ComputationHandlerRegistry(null);
  }

  public boolean supports(ComputationMethod method) {
    return capabilities.find(method).isPresent();
  }

  public HandlerEvidence execute(ExperimentSpec spec, ExperimentProgram program) {
    RegisteredComputationCapability capability = capabilities.capability(spec.method());
    return capability
        .producer()
        .execute(
            new ValidatedComputationRequest(
                spec,
                capability.descriptor(),
                program,
                "legacy-" + spec.executionHash()))
        .evidence();
  }

  public String toolIdentity(ComputationMethod method) {
    if (capabilities.capability(method).descriptor().backendKind()
        == ComputationBackendKind.NATIVE_JAVA) {
      return JAVA_TOOL_VERSION + "/" + method.value();
    }
    if (externalHandler != null && externalHandler.supports(method)) {
      return externalHandler.toolIdentity(method);
    }
    return JAVA_TOOL_VERSION + "/unavailable/" + method.value();
  }

  public Optional<ExternalComputationHandler> externalHandler() {
    return Optional.ofNullable(externalHandler);
  }

  public ComputationCapabilityRegistry capabilityRegistry() {
    return capabilities;
  }
}
