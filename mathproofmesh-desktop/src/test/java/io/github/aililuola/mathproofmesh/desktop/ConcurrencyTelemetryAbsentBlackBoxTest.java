package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkKind;
import org.junit.jupiter.api.Test;

final class ConcurrencyTelemetryAbsentBlackBoxTest {
  @Test
  void durableTelemetryNowExposesActualProviderOverlapAndPerKeyBusyTime() {
    var run =
        DesktopResearchConcurrencyTestSupport.run(
            ResearchWorkKind.ROUTE_REVIEW, 8, ignored -> 30L);
    assertThat(run.metrics().maxActiveProviderCalls()).isEqualTo(4);
    assertThat(run.metrics().perAgentBusyNanos()).hasSizeGreaterThanOrEqualTo(4);
    assertThat(run.metrics().perAgentLeaseCount()).hasSizeGreaterThanOrEqualTo(4);
    assertThat(run.telemetryHash()).isNotBlank();
  }
}
