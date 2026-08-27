package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.CriticalClaimContextBinding;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.util.List;
import org.junit.jupiter.api.Test;

class CriticalClaimContextCompilerTest {
  private final CriticalClaimContextCompiler compiler = new CriticalClaimContextCompiler();

  @Test
  void newCandidateRequiresAnExplicitBindingWhileLegacyCompilationKeepsRootFallback() {
    StrategyCard missing = strategy("missing-binding", List.of());
    var blueprint = StrategyDiversityTestFixtures.blueprint(missing);
    CriticalClaimContext root =
        new CriticalClaimContext(
            List.of("The structure is finite."),
            List.of(),
            List.of("object_scope=all"),
            List.of(),
            "positive");

    assertThatThrownBy(() -> compiler.compileNewCandidate(missing, blueprint, root))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MISSING_CRITICAL_CLAIM_CONTEXT_BINDING");
    assertThat(compiler.compile(missing, blueprint, root))
        .containsEntry(missing.criticalClaims().getFirst().claimId(), root);

    StrategyCard complete = strategy("complete-binding", List.of(binding("complete-binding")));
    CriticalClaimContext context =
        compiler
            .compileNewCandidate(
                complete, StrategyDiversityTestFixtures.blueprint(complete), root)
            .get("complete-binding-required");
    assertThat(context.assumptions())
        .contains("The structure is finite.", "Declared structural bridge to the required claim.");
    assertThat(context.scopeLimitations())
        .contains("object_scope=all", "claim_node=lemma:critical_claim");
  }

  @Test
  void duplicateBindingsFailClosedWithStableCode() {
    CriticalClaimContextBinding binding = binding("duplicate-binding");
    StrategyCard duplicate = strategy("duplicate-binding", List.of(binding, binding));

    assertThatThrownBy(
            () ->
                compiler.compileNewCandidate(
                    duplicate,
                    StrategyDiversityTestFixtures.blueprint(duplicate),
                    CriticalClaimContext.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DUPLICATE_CRITICAL_CLAIM_CONTEXT_BINDING");
  }

  @Test
  void unknownBlueprintNodeBindingFailsClosed() {
    StrategyCard source = strategy("unknown-node-binding", List.of());
    CriticalClaimContextBinding invalid =
        new CriticalClaimContextBinding(
            source.criticalClaims().getFirst().claimId(),
            "missing-blueprint-node",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "positive");
    StrategyCard candidate = strategy("unknown-node-binding", List.of(invalid));

    assertThatThrownBy(
            () ->
                compiler.compileNewCandidate(
                    candidate,
                    StrategyDiversityTestFixtures.blueprint(candidate),
                    CriticalClaimContext.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown blueprint node");
  }

  private static StrategyCard strategy(
      String id, List<CriticalClaimContextBinding> bindings) {
    StrategyCard source =
        StrategyDiversityTestFixtures.strategy(
            id,
            "Explicit Claim context",
            "Use a direct structural argument.",
            "The route-local bridge holds.",
            0.8d);
    return new StrategyCard(
        source.assignedAgentId(),
        source.bottleneck(),
        source.calculationChecks(),
        source.calculationEvidenceRefs(),
        source.computationHints(),
        source.coreIdea(),
        source.criticalClaims(),
        source.estimatedCost(),
        source.estimatedSuccess(),
        source.expectedLemmas(),
        source.falsificationTest(),
        source.independenceBasis(),
        source.inspirationProposalId(),
        source.keyOriginalStep(),
        source.parentStrategyIds(),
        source.prerequisites(),
        source.strategyId(),
        source.tags(),
        source.title(),
        source.mechanismOperations(),
        List.copyOf(bindings));
  }

  private static CriticalClaimContextBinding binding(String strategyId) {
    return new CriticalClaimContextBinding(
        strategyId + "-required",
        "@claim",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "positive");
  }
}
