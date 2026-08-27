package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import java.util.Set;

/** Public inventory for prompts, validation, and operational status endpoints. */
public final class ComputationServiceRegistry {
  private ComputationServiceRegistry() {}

  public static Set<ComputationMethod> javaNativeMethods() {
    return methods(ComputationBackendKind.NATIVE_JAVA);
  }

  public static Set<ComputationMethod> sidecarMethods() {
    return methods(ComputationBackendKind.EXTERNAL_TYPED);
  }

  public static Set<ComputationMethod> arbitraryExecutionMethods() {
    return ComputationCapabilityRegistry.javaOnly().snapshot().descriptors().stream()
        .filter(
            value ->
                value.backendKind() == ComputationBackendKind.SANDBOXED_PYTHON
                    || value.backendKind() == ComputationBackendKind.FORMAL_KERNEL)
        .map(ComputationCapabilityDescriptor::method)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private static Set<ComputationMethod> methods(ComputationBackendKind backend) {
    return ComputationCapabilityRegistry.javaOnly().snapshot().descriptors().stream()
        .filter(value -> value.backendKind() == backend)
        .map(ComputationCapabilityDescriptor::method)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }
}
