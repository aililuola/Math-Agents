package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkKind;
import org.junit.jupiter.api.Test;

final class DesktopClaimCourtCrossCaseSerialBlackBoxTest {
  @Test
  void fourDifferentCasesOverlapAtTheSameCourtStage() {
    var run =
        DesktopResearchConcurrencyTestSupport.run(
            ResearchWorkKind.CLAIM_BLIND_ADJUDICATION, 4, ignored -> 35L);
    assertThat(run.maximumConcurrency()).isEqualTo(4);
    assertThat(run.workItems()).extracting(item -> item.claimId()).doesNotHaveDuplicates();
  }
}
