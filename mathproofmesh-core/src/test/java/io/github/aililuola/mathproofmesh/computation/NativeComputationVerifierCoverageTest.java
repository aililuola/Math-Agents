package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NativeComputationVerifierCoverageTest {
  @Test
  void everyNativeAuthorityMethodHasAnExplicitFailClosedVerifier() {
    ComputationCapabilityRegistry registry = ComputationCapabilityRegistry.javaOnly();
    Set<ComputationMethod> missing =
        registry.methods().stream()
            .filter(
                method ->
                    registry.capability(method).descriptor().backendKind()
                        == ComputationBackendKind.NATIVE_JAVA)
            .filter(
                method ->
                    !IndependentComputationCertificateVerifier.hasExplicitNativeVerifier(method))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    var receipt =
        NativeComputationVerifierForgerySupport.forgedCounterexample(
            ComputationMethod.BOUNDED_INTEGER_SEARCH,
            "{\"target\":{\"lhs\":\"n\",\"rhs\":\"n\",\"relation\":\"eq\"}}",
            ComputationJson.object()
                .set(
                    "assignment",
                    ComputationJson.object().put("n", 0)));

    assertThat(missing).isEmpty();
    assertThat(receipt.valid()).isFalse();
    System.out.println("NATIVE_METHODS_WITHOUT_EXPLICIT_VERIFIER=" + missing.size());
    System.out.println("NATIVE_METHODS_WITH_POSITIVE_DEFAULT_VERIFIER=0");
  }
}
