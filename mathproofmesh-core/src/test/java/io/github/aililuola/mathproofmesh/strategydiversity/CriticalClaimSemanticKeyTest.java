package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.CriticalClaim;
import java.util.List;
import org.junit.jupiter.api.Test;

class CriticalClaimSemanticKeyTest {
  @Test
  void keyBindsProblemAssumptionsScopePolarityAndNecessity() {
    CriticalClaimKeyCompiler compiler = new CriticalClaimKeyCompiler();
    CriticalClaim required =
        StrategyDiversityTestFixtures.claim("claim-a", "For every vertex v, degree(v) >= 1.", "required");
    CriticalClaim renamed =
        StrategyDiversityTestFixtures.claim("claim-b", "For every vertex x, degree(x) >= 1.", "required");

    CriticalClaimSemanticKey left =
        compiler.compile("problem", required, List.of("connected"), List.of(), List.of("all vertices"), "positive");
    CriticalClaimSemanticKey alpha =
        compiler.compile("problem", renamed, List.of("connected"), List.of(), List.of("all vertices"), "positive");
    CriticalClaimSemanticKey otherScope =
        compiler.compile("problem", required, List.of("connected"), List.of(), List.of("interior vertices"), "positive");

    assertThat(alpha.normalizedStatement()).isEqualTo(left.normalizedStatement());
    assertThat(alpha.semanticKey()).isEqualTo(left.semanticKey());
    assertThat(otherScope.semanticKey()).isNotEqualTo(left.semanticKey());
  }
}
