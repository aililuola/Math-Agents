package io.github.aililuola.mathproofmesh.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class Issue013BudgetArchitecturePreFixTest {

  @Test
  void schedulerExposesCanonicalStateDecisionInsteadOfOnlyCallerActionKeys()
      throws ClassNotFoundException {
    Class<?> snapshot =
        Class.forName(
            "io.github.aililuola.mathproofmesh.orchestration.BudgetStateSnapshot");
    boolean canonicalDecision =
        Arrays.stream(AdaptiveBudgetManager.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("decide"))
            .map(Method::getParameterTypes)
            .anyMatch(parameters -> parameters.length == 1 && parameters[0].equals(snapshot));

    assertThat(canonicalDecision)
        .as("AdaptiveBudgetManager must decide from one canonical state snapshot")
        .isTrue();
  }

  @Test
  void multidimensionalActionEnvelopeTypesExist() throws ClassNotFoundException {
    assertThat(
            Class.forName(
                "io.github.aililuola.mathproofmesh.orchestration.BudgetResourceVector"))
        .isNotNull();
    assertThat(
            Class.forName(
                "io.github.aililuola.mathproofmesh.orchestration.BudgetEnvelopeLedger"))
        .isNotNull();
    assertThat(
            Class.forName(
                "io.github.aililuola.mathproofmesh.orchestration.StageTokenEnvelopeResolver"))
        .isNotNull();
  }
}
