package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RunUsageReconciliationTest {
  @Test
  void durableRequestsOutrankZeroProjection() {
    var calls = List.of(new ProviderCallUsageEvidence("request-1", 7, 3, BigDecimal.ONE, 4, "a"));
    var result =
        new RunUsageReconciler()
            .reconcile(
                List.of(
                    RunUsageEvidence.providerCalls(calls, "provider"),
                    RunUsageEvidence.aggregate(
                        RunUsageEvidenceSource.RESULT_PROJECTION,
                        RunUsageSnapshot.empty(),
                        "result")),
                RunUsageSnapshot.empty());
    assertThat(result.status()).isEqualTo(RunUsageStatus.RECORDED);
    assertThat(result.usage().providerCalls()).isEqualTo(1);
    assertThat(result.usage().totalTokens()).isEqualTo(10);
  }
}
