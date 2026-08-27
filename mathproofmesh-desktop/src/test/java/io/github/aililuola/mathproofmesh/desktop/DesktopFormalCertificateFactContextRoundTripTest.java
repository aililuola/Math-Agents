package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopFormalCertificateFactContextRoundTripTest {
  @TempDir Path temporaryDirectory;

  @Test
  void formalCertificateBecomesAnExactlyReusableClaimFact() throws Exception {
    DesktopSolveCheckpoint checkpoint;
    DesktopComputationSemanticContextTestSupport.ContextFixture fixture;
    try (var harness =
        DesktopComputationIssue010CoordinatorHarness.openWithFakeFormalKernel(
            temporaryDirectory, "formal-fact-context")) {
      harness.initializeRoute();
      fixture =
          DesktopComputationSemanticContextTestSupport.bind(
              harness,
              DesktopComputationIssue010Support.formalCertificate("formal-fact-context"),
              "formal-fact-context-claim");
      harness.runComputation(fixture.spec());
      MessageEnvelope fact = harness.typedMemory().facts().getFirst();
      MessageEnvelope roundTripped =
          ContractObjectMapper.read(ContractObjectMapper.write(fact), MessageEnvelope.class);

      int statementHashLosses =
          fixture.binding().claimStatementHash().equals(roundTripped.claimStatementHash()) ? 0 : 1;
      int semanticHashLosses =
          fixture.binding().claimSemanticHash().equals(roundTripped.claimSemanticHash()) ? 0 : 1;
      int quantifierLosses =
          fixture.binding().quantifiers().equals(roundTripped.quantifiers()) ? 0 : 1;
      int bindingLosses =
          fixture.binding().variableBindings().equals(roundTripped.variableBindings()) ? 0 : 1;
      int scopeLosses =
          fixture.binding().scopeLimitations().equals(roundTripped.scopeLimitations()) ? 0 : 1;
      int polarityLosses =
          fixture.binding().polarity().equals(roundTripped.polarity()) ? 0 : 1;
      int evidenceFailures = harness.exactVerifiedFact(roundTripped, fixture.binding()) ? 0 : 1;

      assertThat(statementHashLosses).isZero();
      assertThat(semanticHashLosses).isZero();
      assertThat(quantifierLosses).isZero();
      assertThat(bindingLosses).isZero();
      assertThat(scopeLosses).isZero();
      assertThat(polarityLosses).isZero();
      assertThat(evidenceFailures).isZero();
      System.out.println("FORMAL_FACT_STATEMENT_HASH_LOSSES=" + statementHashLosses);
      System.out.println("FORMAL_FACT_SEMANTIC_HASH_LOSSES=" + semanticHashLosses);
      System.out.println("FORMAL_FACT_QUANTIFIER_LOSSES=" + quantifierLosses);
      System.out.println("FORMAL_FACT_BINDING_LOSSES=" + bindingLosses);
      System.out.println("FORMAL_FACT_SCOPE_LOSSES=" + scopeLosses);
      System.out.println("FORMAL_FACT_POLARITY_LOSSES=" + polarityLosses);
      System.out.println("FORMAL_FACT_EVIDENCE_ROUND_TRIP_FAILURES=" + evidenceFailures);
      checkpoint = harness.checkpointRoundTrip();
    }

    try (var restored =
        DesktopComputationIssue010CoordinatorHarness.open(
            temporaryDirectory, "formal-fact-context")) {
      restored.restore(checkpoint);
      assertThat(restored.typedMemory().facts()).hasSize(1);
      assertThat(restored.exactVerifiedFact(
              restored.typedMemory().facts().getFirst(), fixture.binding()))
          .isTrue();
    }
  }
}
