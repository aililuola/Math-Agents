package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactType;
import io.github.aililuola.mathproofmesh.contract.VerifiedClaimPayload;
import org.junit.jupiter.api.Test;

class BrokerArtifactSemanticIdentityTest {
  @Test
  void quantifierScopePolarityAndRevisionParticipateInIdentity() {
    BrokerArtifactCompiler compiler = new BrokerArtifactCompiler();
    String universal = hash(compiler, "forall", "global", "positive", "revision-a");
    assertThat(hash(compiler, "exists", "global", "positive", "revision-a"))
        .isNotEqualTo(universal);
    assertThat(hash(compiler, "forall", "bounded", "positive", "revision-a"))
        .isNotEqualTo(universal);
    assertThat(hash(compiler, "forall", "global", "negative", "revision-a"))
        .isNotEqualTo(universal);
    assertThat(hash(compiler, "forall", "global", "positive", "revision-b"))
        .isNotEqualTo(universal);
  }

  private static String hash(
      BrokerArtifactCompiler compiler, String quantifier, String scope, String polarity,
      String revision) {
    return compiler
        .compile(
            BrokerArtifactTestFixtures.request(
                BrokerArtifactType.VERIFIED_CLAIM,
                new VerifiedClaimPayload(
                    BrokerArtifactTestFixtures.context(quantifier, scope, polarity)),
                BrokerArtifactSourceKind.CLAIM_COURT_VERIFIED,
                "route-a",
                "claim-tree",
                revision,
                true))
        .artifact()
        .semanticHash();
  }
}
