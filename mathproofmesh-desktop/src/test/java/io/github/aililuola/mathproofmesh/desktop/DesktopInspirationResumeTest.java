package io.github.aililuola.mathproofmesh.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.InspirationAssignmentPlan;
import io.github.aililuola.mathproofmesh.contract.InspirationContextMode;
import io.github.aililuola.mathproofmesh.contract.InspirationMechanism;
import io.github.aililuola.mathproofmesh.contract.InspirationProposalAssignment;
import io.github.aililuola.mathproofmesh.contract.InspirationTask;
import io.github.aililuola.mathproofmesh.contract.InspirationTriggerType;
import io.github.aililuola.mathproofmesh.inspiration.InspirationSnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class DesktopInspirationResumeTest {
  private static final String TASK_ID = "inspiration_task_resume";

  @Test
  void resumesAtTheFirstUncommittedProposalSlot() {
    DesktopSolveCheckpoint.InspirationRoundProgress progress = progress();
    progress = progress.markProposalSlot(TASK_ID, 0);
    InspirationAssignmentPlan remaining =
        DesktopSolveCoordinator.remainingInspirationAssignments(plan(), progress);

    assertEquals(
        List.of(1, 2),
        remaining.assignments().stream()
            .map(InspirationProposalAssignment::proposalSlot)
            .toList());
    assertEquals(2, remaining.requestedProposals());
    assertTrue(progress.proposalSlotCompleted(TASK_ID, 0));
    assertFalse(progress.proposalSlotCompleted(TASK_ID, 1));
  }

  @Test
  void roundTripsTheExactTaskAndSlotFrontier() {
    DesktopSolveCheckpoint.InspirationRoundProgress expected =
        progress()
            .withAssignmentPlan(plan())
            .markProposalSlot(TASK_ID, 0)
            .markTaskCompleted(TASK_ID);

    DesktopSolveCheckpoint.InspirationRoundProgress restored =
        ContractObjectMapper.read(
            ContractObjectMapper.write(expected),
            DesktopSolveCheckpoint.InspirationRoundProgress.class);

    assertEquals(expected, restored);
    assertTrue(restored.taskCompleted(TASK_ID));
    assertTrue(restored.proposalSlotCompleted(TASK_ID, 0));
  }

  @Test
  void resumesThePersistedRoundWithoutConsumingAnotherRound() {
    DesktopSolveCheckpoint.InspirationRoundProgress progress = progress();

    assertEquals(6, DesktopSolveCoordinator.inspirationRoundToRun(6, 6, progress));
    assertEquals(7, DesktopSolveCoordinator.inspirationRoundToRun(6, 12, null));
    assertEquals(-1, DesktopSolveCoordinator.inspirationRoundToRun(12, 12, null));
  }

  @Test
  void rejectsAnInspirationCheckpointFromAnotherRound() {
    assertThrows(
        IllegalStateException.class,
        () -> DesktopSolveCoordinator.inspirationRoundToRun(7, 12, progress()));
  }

  private static DesktopSolveCheckpoint.InspirationRoundProgress progress() {
    InspirationTask task =
        new InspirationTask(
            3,
            InspirationMechanism.BRIDGE_LEMMA,
            "repair the minimum bridge",
            List.of("bridge-obligation"),
            List.of("route-1"),
            TASK_ID,
            "trigger-resume");
    return new DesktopSolveCheckpoint.InspirationRoundProgress(
        6,
        snapshot(),
        List.of(task),
        Map.of(),
        Map.of("trigger-resume", InspirationTriggerType.STAGNATION),
        List.of(),
        Map.of(),
        null,
        4,
        1,
        2,
        3.5d);
  }

  private static InspirationAssignmentPlan plan() {
    List<InspirationProposalAssignment> assignments =
        List.of(
            assignment(0, "agent-a"),
            assignment(1, "agent-b"),
            assignment(2, "agent-c"));
    return new InspirationAssignmentPlan(
        assignments,
        null,
        List.of("agent-a", "agent-b", "agent-c"),
        InspirationMechanism.BRIDGE_LEMMA,
        3,
        6,
        TASK_ID);
  }

  private static InspirationProposalAssignment assignment(int slot, String agentId) {
    return new InspirationProposalAssignment(
        InspirationContextMode.LOCAL,
        InspirationMechanism.BRIDGE_LEMMA,
        slot,
        agentId,
        "inspiration_proposer",
        true,
        TASK_ID);
  }

  private static InspirationSnapshot snapshot() {
    return new InspirationSnapshot(
        6,
        "problem-hash",
        "number_theory",
        List.of("route-1"),
        List.of("route-1"),
        Map.of("route-1", 2),
        0,
        Map.of("route-1", 3.5d),
        0.0d,
        List.of(3.5d, 3.5d),
        List.of("bridge-gap"),
        0.5d,
        List.of("bridge-obligation"),
        20,
        4,
        4,
        12,
        List.of("bridge-obligation"),
        Map.of("bridge-obligation", "subgoal"),
        false,
        false);
  }
}
