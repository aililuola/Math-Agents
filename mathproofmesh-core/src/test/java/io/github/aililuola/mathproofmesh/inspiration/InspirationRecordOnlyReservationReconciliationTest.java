package io.github.aililuola.mathproofmesh.inspiration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.aililuola.mathproofmesh.contract.InspirationAssignmentPlan;
import io.github.aililuola.mathproofmesh.contract.InspirationCallReservation;
import io.github.aililuola.mathproofmesh.contract.InspirationContextMode;
import io.github.aililuola.mathproofmesh.contract.InspirationMechanism;
import io.github.aililuola.mathproofmesh.contract.InspirationProposalAssignment;
import io.github.aililuola.mathproofmesh.contract.InspirationTask;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class InspirationRecordOnlyReservationReconciliationTest {
  @Test
  void offAndShadowReservationsRemainReconcilableWithoutBudgetMutation() {
    for (InspirationPolicy.Mode mode :
        List.of(InspirationPolicy.Mode.OFF, InspirationPolicy.Mode.SHADOW)) {
      InspirationPolicy policy = InspirationPolicy.defaults(mode);
      InspirationEngine engine =
          new InspirationEngine(
              policy,
              new InspirationMechanismRegistry(policy.enabledMechanisms()),
              new InspirationReferee(policy),
              new MechanismContextProfile(policy.limits()));
      InspirationTask task =
          new InspirationTask(
              1,
              InspirationMechanism.REPRESENTATION_SWITCH,
              "record-only trigger",
              List.of("main-goal"),
              List.of("route-1"),
              "task-" + mode.name().toLowerCase(java.util.Locale.ROOT),
              "trigger-" + mode.name().toLowerCase(java.util.Locale.ROOT));
      InspirationProposalAssignment assignment =
          new InspirationProposalAssignment(
              InspirationContextMode.WARM,
              task.mechanism(),
              0,
              "proposer",
              "specialist",
              true,
              task.taskId());
      InspirationAssignmentPlan plan =
          new InspirationAssignmentPlan(
              List.of(assignment),
              null,
              List.of("proposer"),
              task.mechanism(),
              1,
              0,
              task.taskId());
      InspirationSnapshot snapshot =
          new InspirationSnapshot(
              0,
              "problem-hash",
              "number_theory",
              List.of("route-1"),
              List.of(),
              Map.of("route-1", 1),
              0,
              Map.of("route-1", 1.0d),
              0.0d,
              List.of(),
              List.of(),
              0.0d,
              List.of(),
              10,
              2,
              0,
              4,
              List.of("main-goal"),
              Map.of("main-goal", "main_goal"),
              false,
              false);

      InspirationCallReservation reserved =
          engine.reserveCycle(task, plan, snapshot, 0, 1);
      InspirationCallReservation reconciled =
          engine.reconcileReservation(reserved.reservationId(), Map.of(), false);

      assertEquals(0, reconciled.consumedCalls());
      assertEquals(0, reconciled.overrunCalls());
      assertEquals(reconciled.reservedCalls(), reconciled.releasedCalls());
      assertEquals("completed", reconciled.status());
      assertEquals(reconciled, engine.reserveCycle(task, plan, snapshot, 0, 1));
    }
  }
}
