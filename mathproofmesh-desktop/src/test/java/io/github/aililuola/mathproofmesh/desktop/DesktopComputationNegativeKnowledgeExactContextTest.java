package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimEvidenceSemanticBinding;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.memory.NegativeCandidateIntent;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeCandidate;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeDecisionCode;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeSurface;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeTargetType;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopComputationNegativeKnowledgeExactContextTest {
  @TempDir Path temporaryDirectory;

  @Test
  void permanentCounterexampleBlocksOnlyItsExactSemanticContext() throws Exception {
    try (var harness =
        DesktopComputationIssue010CoordinatorHarness.open(
            temporaryDirectory, "negative-exact-context")) {
      harness.initializeRoute();
      var fixture =
          DesktopComputationSemanticContextTestSupport.bind(
              harness,
              DesktopComputationIssue010Support.graphCounterexample(
                  "negative-exact-context", 42),
              "negative-exact-context-claim");
      harness.runComputation(fixture.spec());
      MessageEnvelope negative = harness.typedMemory().negatives().getFirst();
      var gate = harness.typedMemory().negativeKnowledgeAdmissionGate();

      var exact = gate.evaluate(candidate(negative, fixture.binding()), 0);
      QuantifierSpec forall = fixture.binding().quantifiers().getFirst();
      var existential =
          new QuantifierSpec(
              forall.displayName(),
              forall.domain(),
              "exists",
              forall.order(),
              forall.restrictions(),
              forall.variableId());
      var crossQuantifier =
          gate.evaluate(
              candidate(
                  negative,
                  fixture.binding(),
                  List.of(existential),
                  fixture.binding().scopeLimitations()),
              0);
      List<String> restrictedScope =
          java.util.stream.Stream.concat(
                  fixture.binding().scopeLimitations().stream(),
                  java.util.stream.Stream.of("scope excludes the recorded witness"))
              .toList();
      var crossScope =
          gate.evaluate(
              candidate(
                  negative,
                  fixture.binding(),
                  fixture.binding().quantifiers(),
                  restrictedScope),
              0);
      var crossPolarity =
          gate.evaluate(
              new NegativeKnowledgeCandidate(
                  negative.problemHash(),
                  NegativeKnowledgeTargetType.CLAIM,
                  negative.statement(),
                  negative.normalizedStatement(),
                  fixture.binding().assumptions(),
                  fixture.binding().quantifiers(),
                  fixture.binding().variableBindings(),
                  fixture.binding().scopeLimitations(),
                  "negative",
                  NegativeKnowledgeSurface.STRATEGY_ADMISSION,
                  NegativeCandidateIntent.POSITIVE_DEPENDENCY),
              0);

      int exactBlocks = exact.code() == NegativeKnowledgeDecisionCode.BLOCK_PERMANENT ? 1 : 0;
      int crossQuantifierBlocks =
          crossQuantifier.code() == NegativeKnowledgeDecisionCode.BLOCK_PERMANENT ? 1 : 0;
      int crossScopeBlocks =
          crossScope.code() == NegativeKnowledgeDecisionCode.BLOCK_PERMANENT ? 1 : 0;
      int crossPolarityBlocks =
          crossPolarity.code() == NegativeKnowledgeDecisionCode.BLOCK_PERMANENT ? 1 : 0;

      assertThat(exactBlocks).isOne();
      assertThat(crossQuantifierBlocks).isZero();
      assertThat(crossScopeBlocks).isZero();
      assertThat(crossPolarityBlocks).isZero();
      System.out.println("EXACT_NEGATIVE_REENTRY_BLOCKS=" + exactBlocks);
      System.out.println("CROSS_QUANTIFIER_FALSE_BLOCKS=" + crossQuantifierBlocks);
      System.out.println("CROSS_SCOPE_FALSE_BLOCKS=" + crossScopeBlocks);
      System.out.println("CROSS_POLARITY_FALSE_BLOCKS=" + crossPolarityBlocks);
    }
  }

  private static NegativeKnowledgeCandidate candidate(
      MessageEnvelope message, ClaimEvidenceSemanticBinding binding) {
    return candidate(message, binding, binding.quantifiers(), binding.scopeLimitations());
  }

  private static NegativeKnowledgeCandidate candidate(
      MessageEnvelope message,
      ClaimEvidenceSemanticBinding binding,
      List<QuantifierSpec> quantifiers,
      List<String> scopeLimitations) {
    return new NegativeKnowledgeCandidate(
        message.problemHash(),
        NegativeKnowledgeTargetType.CLAIM,
        message.statement(),
        message.normalizedStatement(),
        binding.assumptions(),
        quantifiers,
        binding.variableBindings(),
        scopeLimitations,
        binding.polarity(),
        NegativeKnowledgeSurface.STRATEGY_ADMISSION,
        NegativeCandidateIntent.POSITIVE_DEPENDENCY);
  }
}
