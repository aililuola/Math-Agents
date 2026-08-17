package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RunUsageConflictTest {
  @Test
  void conflictingCountersForOneRequestAreQuarantined() {
    var first = new ProviderCallUsageEvidence("request-1", 4, 6, BigDecimal.ONE, 2, "a");
    var second = new ProviderCallUsageEvidence("request-1", 5, 6, BigDecimal.ONE, 2, "b");
    var result =
        new RunUsageReconciler()
            .reconcile(
                List.of(RunUsageEvidence.providerCalls(List.of(first, second), "provider")),
                RunUsageSnapshot.empty());
    assertThat(result.status()).isEqualTo(RunUsageStatus.CONFLICT);
    assertThat(result.conflicts()).extracting(RunUsageConflict::code)
        .containsExactly("PROVIDER_REQUEST_USAGE_CONFLICT");
  }
}
