package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CriticalClaimParaphraseCommonModeTest {
  @Test
  void conservativeMathematicalSynonymsCannotBypassCommonModeIdentity() {
    CriticalClaimKeyCompiler compiler = new CriticalClaimKeyCompiler();
    var deleting =
        StrategyDiversityTestFixtures.claim(
            "delete", "Deleting a pendant vertex leaves a smaller tree.", "required");
    var removing =
        StrategyDiversityTestFixtures.claim(
            "remove", "Removing a leaf leaves a smaller tree.", "required");

    assertThat(compiler.compile("problem", deleting).semanticKey())
        .isEqualTo(compiler.compile("problem", removing).semanticKey());
  }
}
