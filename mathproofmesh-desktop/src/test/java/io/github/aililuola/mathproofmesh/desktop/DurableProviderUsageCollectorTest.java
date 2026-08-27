package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.provider.UsageTotals;
import io.github.aililuola.mathproofmesh.runstate.ProviderCallUsageEvidence;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DurableProviderUsageCollectorTest {
  @TempDir Path temporaryDirectory;

  @Test
  void completeRequestEvidenceExtendsTheAggregate() throws Exception {
    var result =
        DurableProviderUsageCollector.collect(
            temporaryDirectory,
            DesktopLiveFailureUsageTestSupport.config(),
            totals(20, 200, 100, "0.30", 20.0d),
            calls(21, 10, 5, "0.015", 1.0d));

    assertThat(result.status())
        .isEqualTo(DurableProviderUsageCollector.Status.DURABLE_EXTENSION);
    assertThat(result.totals().calls()).isEqualTo(21L);
    assertThat(result.evidence()).hasSize(21);
  }

  @Test
  void incompleteRequestEvidenceCannotReduceTheAggregate() throws Exception {
    var result =
        DurableProviderUsageCollector.collect(
            temporaryDirectory,
            DesktopLiveFailureUsageTestSupport.config(),
            totals(20, 200, 100, "0.30", 20.0d),
            calls(3, 10, 5, "0.015", 1.0d));

    assertThat(result.status())
        .isEqualTo(DurableProviderUsageCollector.Status.AGGREGATE_PRESERVED);
    assertThat(result.totals().calls()).isEqualTo(20L);
    assertThat(result.evidence()).isEmpty();
  }

  @Test
  void incomparableEvidenceIsQuarantinedInsteadOfGuessed() throws Exception {
    var result =
        DurableProviderUsageCollector.collect(
            temporaryDirectory,
            DesktopLiveFailureUsageTestSupport.config(),
            totals(20, 200, 100, "0.30", 20.0d),
            calls(21, 10, 4, "0.015", 1.0d));

    assertThat(result.status())
        .isEqualTo(DurableProviderUsageCollector.Status.AGGREGATE_CONFLICT);
    assertThat(result.status().conflict()).isTrue();
  }

  @Test
  void conflictingDuplicateRequestIdentityIsQuarantined() throws Exception {
    ProviderCallUsageEvidence first = call("same-request", 10, 5, "0.01", 1.0d);
    ProviderCallUsageEvidence conflict = call("same-request", 11, 5, "0.01", 1.0d);

    var result =
        DurableProviderUsageCollector.collect(
            temporaryDirectory,
            DesktopLiveFailureUsageTestSupport.config(),
            UsageTotals.zero(),
            List.of(first, conflict));

    assertThat(result.status())
        .isEqualTo(DurableProviderUsageCollector.Status.REQUEST_CONFLICT);
    assertThat(result.status().conflict()).isTrue();
  }

  private static UsageTotals totals(
      long calls, long input, long output, String cost, double latency) {
    return new UsageTotals(calls, input, output, new BigDecimal(cost), latency);
  }

  private static List<ProviderCallUsageEvidence> calls(
      int count, long input, long output, String cost, double latency) {
    return IntStream.range(0, count)
        .mapToObj(index -> call("request-" + index, input, output, cost, latency))
        .toList();
  }

  private static ProviderCallUsageEvidence call(
      String requestId, long input, long output, String cost, double latency) {
    return new ProviderCallUsageEvidence(
        requestId, input, output, new BigDecimal(cost), latency, "");
  }
}
