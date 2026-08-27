package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ComputationVerifiedAuthority;
import org.junit.jupiter.api.Test;

class SandboxAuthorityCeilingTest {
  @Test
  void sandboxDescriptorCannotGrantFiniteOrFormalAuthority() {
    var ceiling = ComputationIssue010TestSupport.descriptor(ComputationMethod.SANDBOXED_PYTHON).authorityCeiling();
    assertThat(ceiling.permits(ComputationVerifiedAuthority.BOUNDED_OBSERVATION)).isTrue();
    assertThat(ceiling.permits(ComputationVerifiedAuthority.FINITE_DOMAIN_CERTIFICATE)).isFalse();
    assertThat(ceiling.permits(ComputationVerifiedAuthority.FORMAL_CERTIFICATE)).isFalse();
  }
}
