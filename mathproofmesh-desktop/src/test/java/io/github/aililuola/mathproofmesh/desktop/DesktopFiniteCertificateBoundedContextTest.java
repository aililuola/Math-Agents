package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopFiniteCertificateBoundedContextTest {
  @TempDir Path temporaryDirectory;

  @Test
  void finiteCertificateGetsItsOwnExplicitlyBoundedSemanticIdentity() throws Exception {
    try (var harness =
        DesktopComputationIssue010CoordinatorHarness.open(
            temporaryDirectory, "finite-bounded-context")) {
      harness.initializeRoute();
      var source = DesktopComputationIssue010Support.finiteMap("finite-bounded-context");
      var fixture =
          DesktopComputationSemanticContextTestSupport.bind(
              harness, source, "finite-bounded-context-claim");
      harness.runComputation(fixture.spec());
      MessageEnvelope bounded = harness.typedMemory().facts().getFirst();

      int originalHashReuses =
          fixture.binding().claimStatementHash().equals(bounded.claimStatementHash())
                  || fixture.binding().claimSemanticHash().equals(bounded.claimSemanticHash())
              ? 1
              : 0;
      int scopeLosses =
          bounded.scopeLimitations().stream()
                      .anyMatch(value -> value.startsWith("Finite computation domains: "))
                  && bounded.scopeLimitations().stream()
                      .anyMatch(value -> value.startsWith("Certified result scope: "))
                  && bounded.scopeLimitations().stream()
                      .anyMatch(value -> value.startsWith("Certified finite input: "))
              ? 0
              : 1;
      boolean reusableAsUnrestrictedClaim =
          harness.exactVerifiedFact(bounded, fixture.binding());
      int generalFactPromotions =
          originalHashReuses
                  + (reusableAsUnrestrictedClaim ? 1 : 0)
                  + ("closed".equals(harness.obligation(fixture.binding().claimId()).status())
                      ? 1
                      : 0);

      assertThat(bounded.claimStatementHash()).isNotBlank();
      assertThat(bounded.claimSemanticHash()).isNotBlank();
      assertThat(originalHashReuses).isZero();
      assertThat(scopeLosses).isZero();
      assertThat(generalFactPromotions).isZero();
      System.out.println(
          "FINITE_CERTIFICATE_UNRESTRICTED_CLAIM_HASH_REUSES=" + originalHashReuses);
      System.out.println("FINITE_CERTIFICATE_SCOPE_LOSSES=" + scopeLosses);
      System.out.println("FINITE_CERTIFICATE_GENERAL_FACT_PROMOTIONS=" + generalFactPromotions);
    }
  }
}
