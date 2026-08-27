package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import org.junit.jupiter.api.Test;

final class RunClaimLifecycleProgressExtractorTest {
  @Test
  void modernAuthorityStatesAndTrustedRefutationsAreClassifiedConservatively() {
    var lifecycle =
        ContractObjectMapper.parseTree(
            """
            {"entries":{
              "verified":{"claimId":"verified","state":"LOCALLY_VERIFIED"},
              "fact":{"claimId":"fact","state":"EXTERNALLY_ADMITTED_FACT"},
              "proposed":{"claimId":"proposed","state":"PROPOSED"},
              "invalidated":{"claimId":"invalidated","state":"INVALIDATED"},
              "untrusted":{"claimId":"untrusted","state":"REJECTED","invalidationReason":"review failed"},
              "refuted":{"claimId":"refuted","state":"REJECTED",
                "invalidationReason":"verified exact statement refutation abc",
                "invalidatingEvidenceIds":["evidence-1"],
                "history":["rejected:verified exact statement refutation abc"]}
            }}
            """);

    ClaimLifecycleProgressExtractor.Progress progress =
        ClaimLifecycleProgressExtractor.extract(lifecycle);

    assertThat(progress.verifiedClaimIds()).containsExactly("fact", "verified");
    assertThat(progress.refutedClaimIds()).containsExactly("refuted");
    assertThat(progress.verifiedClaimIds()).doesNotContain("proposed", "invalidated");
    assertThat(progress.refutedClaimIds()).doesNotContain("invalidated", "untrusted");
  }

  @Test
  void legacyExplicitRefutedIsPreservedButUnclassifiedRejectedIsNotPromoted() {
    var lifecycle =
        ContractObjectMapper.parseTree(
            """
            {"records":{
              "verified":{"claimId":"verified","status":"VERIFIED"},
              "refuted":{"claimId":"refuted","status":"REFUTED"},
              "rejected":{"claimId":"rejected","status":"REJECTED"}
            }}
            """);

    ClaimLifecycleProgressExtractor.Progress progress =
        ClaimLifecycleProgressExtractor.extract(lifecycle);

    assertThat(progress.verifiedClaimIds()).containsExactly("verified");
    assertThat(progress.refutedClaimIds()).containsExactly("refuted");
  }
}
