package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import org.junit.jupiter.api.Test;

class ComputationCapabilitySingleSourceArchitectureTest {
  @Test
  void everyStableMethodResolvesThroughTheCapabilityRegistry() {
    var registry = ComputationIssue010TestSupport.registry();
    assertThat(registry.snapshot().descriptors())
        .extracting(ComputationCapabilityDescriptor::method)
        .containsExactlyInAnyOrder(ComputationMethod.values());
    var handlers = ComputationHandlerRegistry.javaOnly();
    assertThat(java.util.Arrays.stream(ComputationMethod.values()).allMatch(handlers::supports))
        .isTrue();
  }
}
