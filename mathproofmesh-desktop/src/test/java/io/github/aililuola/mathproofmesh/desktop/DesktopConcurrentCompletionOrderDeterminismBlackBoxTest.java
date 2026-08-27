package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.concurrency.ResearchMergePlanner;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkKind;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.Test;

final class DesktopConcurrentCompletionOrderDeterminismBlackBoxTest {
  @Test
  void completionOrderCannotChangeMergeHash() {
    var run =
        DesktopResearchConcurrencyTestSupport.run(
            ResearchWorkKind.ROUTE_REVIEW, 4, work -> work.hashCode() % 2 == 0 ? 15L : 45L);
    var reversed = new ArrayList<>(run.results());
    Collections.reverse(reversed);
    var replay = new ResearchMergePlanner().plan(run.snapshot(), run.workItems(), reversed);

    assertThat(replay.mergePlanHash()).isEqualTo(run.mergePlan().mergePlanHash());
    assertThat(replay.acceptedResultHashes()).isEqualTo(run.mergePlan().acceptedResultHashes());
  }
}
