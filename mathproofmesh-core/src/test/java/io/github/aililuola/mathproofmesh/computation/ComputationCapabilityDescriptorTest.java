package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import org.junit.jupiter.api.Test;

class ComputationCapabilityDescriptorTest {
  @Test
  void descriptorsAreVersionedHashedAndAuthorityBounded() {
    var descriptor = ComputationIssue010TestSupport.descriptor(ComputationMethod.EXACT_LINEAR_ALGEBRA);
    assertThat(descriptor.backendKind()).isEqualTo(ComputationBackendKind.NATIVE_JAVA);
    assertThat(descriptor.producerId()).isNotEqualTo(descriptor.verifierId());
    assertThat(descriptor.inputSchemaHash()).hasSize(64);
    assertThat(ComputationCapabilityFingerprint.of(descriptor).value()).hasSize(64);
  }
}
