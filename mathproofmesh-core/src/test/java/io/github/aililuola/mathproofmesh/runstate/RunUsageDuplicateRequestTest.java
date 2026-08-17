package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RunUsageDuplicateRequestTest {
  @Test
  void identicalRequestIsCountedExactlyOnce() {
    var call = new ProviderCallUsageEvidence("request-1", 4, 6, BigDecimal.ONE, 2, "a");
    var result =
        new RunUsageReconciler()
            .reconcile(
                List.of(RunUsageEvidence.providerCalls(List.of(call, call), "provider")),
                RunUsageSnapshot.empty());
    assertThat(result.usage().providerCalls()).isEqualTo(1);
    assertThat(result.usage().totalTokens()).isEqualTo(10);
  }
}
