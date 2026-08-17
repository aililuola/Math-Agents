package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

final class RunStateTransitionPolicyTest {
  @Test
  void terminalAttemptCannotReturnToRunning() {
    RunExecutionAttemptLedger ledger = new RunExecutionAttemptLedger();
    var attempt = ledger.create("run-1", Instant.EPOCH);
    ledger.transition(attempt.attemptId(), RunExecutionAttemptStatus.RUNNING, "", Instant.EPOCH);
    ledger.transition(attempt.attemptId(), RunExecutionAttemptStatus.FAILED, "backend", Instant.EPOCH);
    assertThatThrownBy(
            () ->
                ledger.transition(
                    attempt.attemptId(), RunExecutionAttemptStatus.RUNNING, "", Instant.EPOCH))
        .isInstanceOf(IllegalStateException.class);
    org.assertj.core.api.Assertions.assertThat(ledger.create("run-1", Instant.ofEpochSecond(1)).ordinal())
        .isEqualTo(1);
  }
}
