package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactType;
import io.github.aililuola.mathproofmesh.contract.VerifiedClaimPayload;
import org.junit.jupiter.api.Test;

class BrokerArtifactCompilerAuthorityTest {
  @Test
  void rawResearchFindingAndModelAuthorityFailClosed() {
    BrokerArtifactCompiler compiler = new BrokerArtifactCompiler();
    var rejected =
        compiler.compile(
            BrokerArtifactTestFixtures.request(
                BrokerArtifactType.VERIFIED_CLAIM,
                new VerifiedClaimPayload(
                    BrokerArtifactTestFixtures.context("forall", "global", "positive")),
                BrokerArtifactSourceKind.RAW_RESEARCH_FINDING,
                "route-a",
                "claim-tree",
                "revision-tree",
                true));
    var inactive =
        compiler.compile(
            BrokerArtifactTestFixtures.request(
                BrokerArtifactType.VERIFIED_CLAIM,
                new VerifiedClaimPayload(
                    BrokerArtifactTestFixtures.context("forall", "global", "positive")),
                BrokerArtifactSourceKind.CLAIM_COURT_VERIFIED,
                "route-a",
                "claim-tree",
                "revision-tree",
                false));
    assertThat(rejected.accepted()).isFalse();
    assertThat(rejected.rejectionCodes()).containsExactly("UNAUTHORIZED_ARTIFACT_AUTHORITY");
    assertThat(inactive.accepted()).isFalse();
  }
}
