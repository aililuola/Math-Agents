package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ComputationVerifiedAuthority;
import org.junit.jupiter.api.Test;

class ComputationAuthorityCeilingTest {
  @Test
  void boundedAndSandboxCeilingsCannotEscalateToPositiveCertificates() {
    assertThat(
            ComputationAuthorityCeiling.BOUNDED_OBSERVATION.permits(
                ComputationVerifiedAuthority.FINITE_DOMAIN_CERTIFICATE))
        .isFalse();
    assertThat(
            ComputationAuthorityCeiling.AUDIT_ONLY.permits(
                ComputationVerifiedAuthority.EXACT_COUNTEREXAMPLE))
        .isFalse();
  }
}
