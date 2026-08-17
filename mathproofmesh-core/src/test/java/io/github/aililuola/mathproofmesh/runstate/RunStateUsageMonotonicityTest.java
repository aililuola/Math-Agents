package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

final class RunStateUsageMonotonicityTest {
  @Test
  void lowerLaterAggregateCannotZeroDurableUsage() {
    RunUsageSnapshot prior = RunStateTestSupport.usage(7, 70, 30);
    var result =
        new RunUsageReconciler()
            .reconcile(
                List.of(
                    RunUsageEvidence.aggregate(
                        RunUsageEvidenceSource.RESULT_PROJECTION,
                        RunUsageSnapshot.empty(),
                        "failed-result")),
                prior);
    assertThat(result.status()).isEqualTo(RunUsageStatus.CONFLICT);
    assertThat(result.usage()).isEqualTo(prior);
  }
}
