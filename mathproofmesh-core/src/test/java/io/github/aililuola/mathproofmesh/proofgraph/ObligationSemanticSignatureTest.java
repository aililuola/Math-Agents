package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ObligationSemanticSignatureTest {
  @Test
  void exactAndTrustedAlphaEquivalenceAreSafeButSemanticCollisionsStayDistinct() {
    QuantifierSpec n = new QuantifierSpec("n", "positive integers", "forall", 0, List.of(), "n");
    QuantifierSpec k = new QuantifierSpec("k", "positive integers", "forall", 0, List.of(), "k");
    ProofObligation left =
        ObligationCanonicalizationTestFixtures.obligation(
            "left", "r1", "For every n, P(n)", "forall n p(n)", "family", ObligationKind.LEMMA,
            List.of("n is positive"), List.of(n), "left-plan");
    ProofObligation renamed =
        ObligationCanonicalizationTestFixtures.obligation(
            "renamed", "r2", "For every k, P(k)", "forall k p(k)", "family", ObligationKind.LEMMA,
            List.of("k is positive"), List.of(k), "right-plan");
    ObligationCreationContext leftContext = context(left, "r1", List.of("all"), "positive");
    ObligationCreationContext rightContext = context(renamed, "r2", List.of("all"), "positive");

    assertThat(ObligationSemanticSignature.from(left, leftContext).signatureHash())
        .isEqualTo(ObligationSemanticSignature.from(renamed, rightContext).signatureHash());
    assertThat(
            ObligationSemanticSignature.identityStrength(
                left, leftContext, renamed, rightContext))
        .isEqualTo(ObligationIdentityStrength.TRUSTED_ALPHA_EQUIVALENT);
    assertThat(ObligationSemanticSignature.identityStrength(left, leftContext, left, leftContext))
        .isEqualTo(ObligationIdentityStrength.EXACT);

    ProofObligation exists =
        ObligationCanonicalizationTestFixtures.obligation(
            "exists", "r3", "There exists n, P(n)", "exists n p(n)", "family", ObligationKind.LEMMA,
            List.of("n is positive"),
            List.of(new QuantifierSpec("n", "positive integers", "exists", 0, List.of(), "n")),
            "exists-plan");
    assertThat(ObligationSemanticSignature.from(exists, context(exists, "r3", List.of("all"), "positive")).signatureHash())
        .isNotEqualTo(ObligationSemanticSignature.from(left, leftContext).signatureHash());
    assertThat(ObligationSemanticSignature.from(left, context(left, "r1", List.of("eventual"), "positive")).signatureHash())
        .isNotEqualTo(ObligationSemanticSignature.from(left, leftContext).signatureHash());
    assertThat(ObligationSemanticSignature.from(left, context(left, "r1", List.of("all"), "negative")).signatureHash())
        .isNotEqualTo(ObligationSemanticSignature.from(left, leftContext).signatureHash());
  }

  private static ObligationCreationContext context(
      ProofObligation obligation, String route, List<String> scope, String polarity) {
    return ObligationCanonicalizationTestFixtures.context(
        obligation, route, "family", scope, polarity, Map.of(), 0);
  }
}
