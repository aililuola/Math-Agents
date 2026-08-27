package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.computation.InMemoryComputationCache;
import io.github.aililuola.mathproofmesh.contract.ComputationVerifiedAuthority;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopFiniteCertificateScopeBoundaryTest {
  @TempDir Path temporaryDirectory;

  @Test
  void finiteCertificateRemainsBoundToItsCompleteEnumeratedDomain() {
    var broker = DesktopComputationIssue010Support.broker("finite-scope", temporaryDirectory, new InMemoryComputationCache());
    var outcome = DesktopComputationIssue010Support.run(broker, DesktopComputationIssue010Support.finiteMap("finite-map"), "finite-map", 0);
    assertThat(outcome.result().scope().path("complete_domain").asBoolean()).isTrue();
    assertThat(outcome.verificationReceipt().authority())
        .isEqualTo(ComputationVerifiedAuthority.FINITE_DOMAIN_CERTIFICATE);
    assertThat(outcome.verificationReceipt().verifiedScopeHash()).hasSize(64);
  }
}
