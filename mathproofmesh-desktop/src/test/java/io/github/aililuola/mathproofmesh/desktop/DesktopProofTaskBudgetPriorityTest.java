package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.orchestration.BudgetBucket;
import io.github.aililuola.mathproofmesh.orchestration.BudgetEnvelope;
import io.github.aililuola.mathproofmesh.orchestration.BudgetEnvelopeLedger;
import io.github.aililuola.mathproofmesh.orchestration.BudgetResourceVector;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DesktopProofTaskBudgetPriorityTest {
  private static final BudgetResourceVector ONE_CALL = calls(1);
  private static final BudgetResourceVector ZERO = calls(0);

  @Test
  void optionalRecoveryCannotConsumeThreeProtectedAuthorityReviewCalls() {
    BudgetEnvelopeLedger ledger = new BudgetEnvelopeLedger(calls(7), ZERO);
    BudgetEnvelope envelope =
        ledger.reserve("run", "epoch", "proof-task", "decision", BudgetBucket.DEPTH, calls(7));
    ledger.activate(envelope.envelopeId());

    for (int index = 0; index < 4; index++) {
      settle(ledger, envelope, "optional-" + index, ONE_CALL, calls(3));
    }
    assertThatThrownBy(
            () ->
                ledger.reservePhysical(
                    envelope.envelopeId(),
                    "optional-blocked",
                    "optional-blocked",
                    "structured_output_recovery",
                    4,
                    "pricing",
                    ONE_CALL,
                    calls(3)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("ACTION_ENVELOPE_PROTECTED_RESERVE");

    for (int index = 0; index < 3; index++) {
      settle(ledger, envelope, "authority-" + index, ONE_CALL, ZERO);
    }
    assertThat(ledger.remaining(envelope.envelopeId()).calls()).isZero();
    System.out.println("PROTECTED_AUTHORITY_REVIEW_CALLS=3");
    System.out.println("OPTIONAL_CALLS_BEFORE_PROTECTION=4");
    System.out.println("OPTIONAL_CALLS_BLOCKED_AT_FLOOR=1");
  }

  @Test
  void proofTaskBatchFallsBackToLargestAffordableStablePrefix() {
    List<Integer> attemptedSizes = new ArrayList<>();

    List<String> admitted =
        DesktopBudgetScheduler.largestReservablePrefix(
            List.of("task-a", "task-b", "task-c"),
            prefix -> {
              attemptedSizes.add(prefix.size());
              return prefix.size() <= 1;
            });

    assertThat(attemptedSizes).containsExactly(3, 2, 1);
    assertThat(admitted).containsExactly("task-a");
    System.out.println("AFFORDABLE_BATCH_ATTEMPTS=" + attemptedSizes);
    System.out.println("AFFORDABLE_BATCH_ADMISSIONS=" + admitted.size());
  }

  @Test
  void optionalSupportingClaimCourtPreservesOneRepairOpportunity() {
    boolean constrained =
        DesktopClaimCourtBatchExecutor.supportingWorkFits(
            calls(9), calls(7), calls(3), 1);
    assertThat(constrained).isFalse();
    assertThat(
            DesktopClaimCourtBatchExecutor.supportingWorkFits(
                calls(17), calls(7), calls(3), 3))
        .isTrue();
    System.out.println("SUPPORTING_COURT_REPAIR_RESERVE_VIOLATIONS=" + (constrained ? 1 : 0));
  }

  private static void settle(
      BudgetEnvelopeLedger ledger,
      BudgetEnvelope envelope,
      String id,
      BudgetResourceVector resources,
      BudgetResourceVector protectedReserve) {
    var reservation =
        ledger.reservePhysical(
            envelope.envelopeId(),
            id,
            id,
            "stage",
            id.hashCode() & Integer.MAX_VALUE,
            "pricing",
            resources,
            protectedReserve);
    ledger.markDispatched(reservation.reservationId());
    ledger.settle(reservation.reservationId(), resources);
  }

  private static BudgetResourceVector calls(long calls) {
    return new BudgetResourceVector(calls, 0L, 0L, 0L, BigDecimal.ZERO);
  }
}
