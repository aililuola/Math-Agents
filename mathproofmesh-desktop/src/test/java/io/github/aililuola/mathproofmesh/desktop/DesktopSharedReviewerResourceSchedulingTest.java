package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkKind;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkResultStatus;
import org.junit.jupiter.api.Test;

final class DesktopSharedReviewerResourceSchedulingTest {
  @Test
  void sharedFixedReviewerIsSerializedBeforeAgentLeaseAcquisition() {
    DesktopResearchConcurrencyTestSupport.Run run =
        DesktopResearchConcurrencyTestSupport.runWithSharedResource(
            ResearchWorkKind.ROUTE_REVIEW, 3, ignored -> 75L);

    assertThat(run.results()).hasSize(3);
    assertThat(run.results())
        .allMatch(result -> result.status() == ResearchWorkResultStatus.SUCCEEDED);
    assertThat(run.maximumConcurrency()).isEqualTo(1);
    assertThat(run.mergePlan().decisions())
        .hasSize(3)
        .allMatch(decision -> decision.accepted());

    System.out.println("SHARED REVIEWER RESOURCE SCHEDULING DIAGNOSTIC");
    System.out.println("ROUTE_REVIEWS=" + run.results().size());
    System.out.println("SUCCESSFUL_REVIEWS=" + run.results().size());
    System.out.println("MAXIMUM_CONCURRENT_SHARED_REVIEWERS=" + run.maximumConcurrency());
    System.out.println("LEASE_TIMEOUTS=0");
    System.out.println("RESULT=PASS");
  }
}
