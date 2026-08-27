package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ClaimCounterexampleFullContextExactnessTest {
  @Test
  void allStructuredClaimContextDimensionsRemainExact() {
    var claim =
        ClaimCourtTestFixtures.claim(
            "context-claim",
            "P(x)",
            "P(x)",
            List.of(),
            List.of(
                ClaimCourtTestFixtures.step(
                    "context-step", "P(x)", "Apply the stated hypothesis.")));
    List<FrozenClaimSemanticContext> contexts =
        List.of(
            context("forall", "x", List.of("H"), List.of("scope=A"), "positive"),
            context("exists", "x", List.of("H"), List.of("scope=A"), "positive"),
            context("forall", "x", List.of("H"), List.of("scope=A"), "negative"),
            context("forall", "x", List.of(), List.of("scope=A"), "positive"),
            context("forall", "x", List.of("H"), List.of("scope=B"), "positive"),
            context("forall", "y", List.of("H"), List.of("scope=A"), "positive"));
    ClaimCourtSemanticContextCompiler compiler =
        new ClaimCourtSemanticContextCompiler();
    ClaimFreezeService freezer = new ClaimFreezeService();
    List<FrozenClaimSnapshot> frozen =
        contexts.stream()
            .map(
                context ->
                    freezer.freeze(
                        "problem-hash",
                        "root-goal-hash",
                        "route-1",
                        claim,
                        compiler.compile(claim, context)))
            .toList();

    assertThat(frozen).hasSize(6);
    assertThat(frozen)
        .extracting(FrozenClaimSnapshot::claimSemanticHash)
        .doesNotHaveDuplicates();
    ClaimRefutationEvidence evidence =
        new ClaimRefutationEvidence(
            "evidence-1",
            ClaimRefutationEvidenceType.INDEPENDENT_WITNESS_ADJUDICATION,
            frozen.getFirst().claimId(),
            frozen.getFirst().claimStatementHash(),
            frozen.getFirst().claimSemanticHash(),
            "x=0",
            "artifact://witness",
            true,
            true);
    assertThat(evidence.exactFor(frozen.getFirst())).isTrue();
    assertThat(frozen.stream().skip(1).noneMatch(evidence::exactFor)).isTrue();

    long distinctSemanticHashes =
        frozen.stream().map(FrozenClaimSnapshot::claimSemanticHash).distinct().count();
    long quantifierFalseRefutations = evidence.exactFor(frozen.get(1)) ? 1L : 0L;
    long polarityFalseRefutations = evidence.exactFor(frozen.get(2)) ? 1L : 0L;
    long assumptionFalseRefutations = evidence.exactFor(frozen.get(3)) ? 1L : 0L;
    long scopeFalseRefutations = evidence.exactFor(frozen.get(4)) ? 1L : 0L;
    long bindingFalseRefutations = evidence.exactFor(frozen.get(5)) ? 1L : 0L;

    assertThat(distinctSemanticHashes).isEqualTo(6L);
    assertThat(quantifierFalseRefutations).isZero();
    assertThat(polarityFalseRefutations).isZero();
    assertThat(assumptionFalseRefutations).isZero();
    assertThat(scopeFalseRefutations).isZero();
    assertThat(bindingFalseRefutations).isZero();
    System.out.println("CLAIM_CONTEXTS=" + frozen.size());
    System.out.println("DISTINCT_SEMANTIC_HASHES=" + distinctSemanticHashes);
    System.out.println("QUANTIFIER_FALSE_REFUTATIONS=" + quantifierFalseRefutations);
    System.out.println("POLARITY_FALSE_REFUTATIONS=" + polarityFalseRefutations);
    System.out.println("ASSUMPTION_FALSE_REFUTATIONS=" + assumptionFalseRefutations);
    System.out.println("SCOPE_FALSE_REFUTATIONS=" + scopeFalseRefutations);
    System.out.println("VARIABLE_BINDING_FALSE_REFUTATIONS=" + bindingFalseRefutations);
    System.out.println("RESULT=PASS");
  }

  @Test
  void missingServerCompiledContextFailsClosed() {
    var claim = ClaimCourtTestFixtures.linearClaim();
    assertThatThrownBy(
            () -> new ClaimCourtSemanticContextCompiler().compile(claim, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MISSING_CLAIM_COURT_SEMANTIC_CONTEXT_BINDING");
  }

  private static FrozenClaimSemanticContext context(
      String kind,
      String variable,
      List<String> assumptions,
      List<String> scope,
      String polarity) {
    String variableId = "var-" + variable;
    return new FrozenClaimSemanticContext(
        assumptions,
        List.of(
            new QuantifierSpec(
                variable, "finite set", kind, 0, List.of(), variableId)),
        List.of(
            new VariableBinding(
                List.of(variable), variable, "finite set", "claim", variableId)),
        scope,
        polarity);
  }
}
