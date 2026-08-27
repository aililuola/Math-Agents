package io.github.aililuola.mathproofmesh.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class ConcurrencyTelemetryMetricsTest {
  @Test
  void derivesConcurrencyFromProviderCallIntervals() {
    AtomicLong ticker = new AtomicLong();
    ConcurrencyTelemetryLedger ledger = new ConcurrencyTelemetryLedger(ticker::get);
    ledger.record(ConcurrencyEventType.WORK_QUEUED, "epoch", "a", "", 4);
    ledger.record(ConcurrencyEventType.PROVIDER_CALL_STARTED, "epoch", "a", "agent-a", 3);
    ledger.record(ConcurrencyEventType.PROVIDER_CALL_STARTED, "epoch", "b", "agent-b", 2);
    ticker.set(10L);
    ledger.record(ConcurrencyEventType.PROVIDER_CALL_COMPLETED, "epoch", "a", "agent-a", 1);
    ledger.record(ConcurrencyEventType.PROVIDER_CALL_COMPLETED, "epoch", "b", "agent-b", 0);
    ConcurrencyMetrics metrics = ledger.metrics(4, 5);
    assertThat(metrics.maxActiveProviderCalls()).isEqualTo(2);
    assertThat(metrics.meanConcurrencyWhileReadyWorkExists()).isEqualTo(2.0d);
    assertThat(metrics.perAgentBusyNanos()).containsEntry("agent-a", 10L).containsEntry("agent-b", 10L);
  }
}
