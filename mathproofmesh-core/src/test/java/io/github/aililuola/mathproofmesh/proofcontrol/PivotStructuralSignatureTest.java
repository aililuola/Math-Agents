package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PivotStructuralSignatureTest {
  @Test
  void strategyIdAndProseCannotManufactureAStateChange() {
    String oldSignatureHash =
        io.github.aililuola.mathproofmesh.contract.CanonicalJson.stableHash(
            SemanticPivotTestFixtures.sourceSignature());
    String newSignatureHash =
        io.github.aililuola.mathproofmesh.contract.CanonicalJson.stableHash(
            SemanticPivotTestFixtures.proposedSignature());
    System.out.println("OLD_STRUCTURAL_SIGNATURE_HASH=" + oldSignatureHash);
    System.out.println("NEW_STRUCTURAL_SIGNATURE_HASH=" + newSignatureHash);
    assertThat(
            SemanticPivotTestFixtures.sourceSignature()
                .sameSemanticState(SemanticPivotTestFixtures.textOnlySignature()))
        .isTrue();
    assertThat(
            SemanticPivotTestFixtures.sourceSignature()
                .sameSemanticState(SemanticPivotTestFixtures.proposedSignature()))
        .isFalse();
    assertThat(newSignatureHash).isNotEqualTo(oldSignatureHash);
  }
}
