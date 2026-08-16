package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.computation.ComputationHandlerRegistry;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import org.junit.jupiter.api.Test;

class DesktopExactLinearAlgebraRequiresExternalBackendBlackBoxTest {
  @Test
  void exactRationalLinearAlgebraHasANativeJavaCapability() {
    boolean methodRegistered;
    boolean nativeSupported;
    try {
      ComputationMethod method = ComputationMethod.fromValue("exact_linear_algebra");
      methodRegistered = true;
      nativeSupported = ComputationHandlerRegistry.javaOnly().supports(method);
    } catch (IllegalArgumentException exception) {
      methodRegistered = false;
      nativeSupported = false;
    }

    System.out.println("EXACT_LINEAR_ALGEBRA_REQUESTS=1");
    System.out.println("NATIVE_EXECUTIONS=" + (nativeSupported ? 1 : 0));
    System.out.println("BACKEND_UNAVAILABLE=" + (nativeSupported ? 0 : 1));
    assertThat(methodRegistered).isTrue();
    assertThat(nativeSupported).isTrue();
  }
}
