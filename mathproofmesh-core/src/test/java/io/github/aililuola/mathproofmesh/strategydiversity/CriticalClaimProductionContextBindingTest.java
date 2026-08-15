package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import java.util.List;
import org.junit.jupiter.api.Test;

class CriticalClaimProductionContextBindingTest {
  @Test
  void keyBindsAssumptionsQuantifiersScopeBindingsAndPolarity() {
    var claim = StrategyDiversityTestFixtures.claim("claim", "P(x) holds.", "required");
    CriticalClaimKeyCompiler compiler = new CriticalClaimKeyCompiler();
    CriticalClaimContext contextual =
        context("x", List.of("H(x)"), List.of("global"), "positive");
    CriticalClaimContext renamed =
        context("y", List.of("H(y)"), List.of("global"), "positive");
    CriticalClaimContext otherScope =
        context("x", List.of("H(x)"), List.of("eventual"), "positive");

    var first = compiler.compile("problem", claim, contextual);
    var alpha =
        compiler.compile(
            "problem",
            StrategyDiversityTestFixtures.claim("renamed", "P(y) holds.", "required"),
            renamed);
    var scoped = compiler.compile("problem", claim, otherScope);

    assertThat(alpha.semanticKey()).isEqualTo(first.semanticKey());
    assertThat(scoped.semanticKey()).isNotEqualTo(first.semanticKey());
    assertThat(first.orderedQuantifiers()).isNotEmpty();
    assertThat(first.variableBindings()).isNotEmpty();
  }

  private static CriticalClaimContext context(
      String variable, List<String> assumptions, List<String> scope, String polarity) {
    return new CriticalClaimContext(
        assumptions,
        List.of(
            new QuantifierSpec(
                variable, "vertices", "forall", 0, List.of(), variable)),
        scope,
        List.of(
            new VariableBinding(
                List.of(), variable, "vertices", "claim", variable)),
        polarity);
  }
}
