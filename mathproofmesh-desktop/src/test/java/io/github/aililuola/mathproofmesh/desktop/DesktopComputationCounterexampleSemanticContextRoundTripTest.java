package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopComputationCounterexampleSemanticContextRoundTripTest {
  @TempDir Path temporaryDirectory;

  @Test
  void verifiedCounterexamplePreservesTheExactClaimContext() throws Exception {
    try (var harness =
        DesktopComputationIssue010CoordinatorHarness.open(
            temporaryDirectory, "counterexample-context-round-trip")) {
      harness.initializeRoute();
      var fixture =
          DesktopComputationSemanticContextTestSupport.bind(
              harness,
              DesktopComputationIssue010Support.graphCounterexample(
                  "counterexample-context", 41),
              "counterexample-context-claim");

      harness.runComputation(fixture.spec());
      MessageEnvelope stored = harness.typedMemory().negatives().getFirst();
      MessageEnvelope roundTripped =
          ContractObjectMapper.read(
              ContractObjectMapper.write(stored), MessageEnvelope.class);

      int quantifierLosses =
          roundTripped.quantifiers().equals(fixture.binding().quantifiers()) ? 0 : 1;
      int bindingLosses =
          roundTripped.variableBindings().equals(fixture.binding().variableBindings()) ? 0 : 1;
      int scopeLosses =
          roundTripped.scopeLimitations().equals(fixture.binding().scopeLimitations()) ? 0 : 1;
      int polarityLosses =
          fixture.binding().polarity().equals(roundTripped.polarity()) ? 0 : 1;
      int semanticHashLosses =
          fixture.binding().claimSemanticHash().equals(roundTripped.claimSemanticHash()) ? 0 : 1;

      var negative =
          harness.typedMemory().negativeKnowledgeRegistry().records().stream()
              .filter(record -> record.evidenceMessageIds().contains(stored.messageId()))
              .findFirst()
              .orElseThrow();
      assertThat(negative.quantifiers()).isEqualTo(fixture.binding().quantifiers());
      assertThat(negative.variableBindings()).isEqualTo(fixture.binding().variableBindings());
      assertThat(negative.scopeLimitations()).isEqualTo(fixture.binding().scopeLimitations());
      assertThat(quantifierLosses).isZero();
      assertThat(bindingLosses).isZero();
      assertThat(scopeLosses).isZero();
      assertThat(polarityLosses).isZero();
      assertThat(semanticHashLosses).isZero();

      System.out.println("COUNTEREXAMPLE_QUANTIFIER_LOSSES=" + quantifierLosses);
      System.out.println("COUNTEREXAMPLE_BINDING_LOSSES=" + bindingLosses);
      System.out.println("COUNTEREXAMPLE_SCOPE_LOSSES=" + scopeLosses);
      System.out.println("COUNTEREXAMPLE_POLARITY_LOSSES=" + polarityLosses);
      System.out.println("COUNTEREXAMPLE_SEMANTIC_HASH_LOSSES=" + semanticHashLosses);
    }
  }
}
