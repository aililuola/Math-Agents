package io.github.aililuola.mathproofmesh.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aililuola.mathproofmesh.workflow.CheckpointMigrationService.ClaimLink;
import io.github.aililuola.mathproofmesh.workflow.CheckpointMigrationService.LegacyCheckpoint;
import io.github.aililuola.mathproofmesh.workflow.ReliabilityRecoveryPolicy.FailureType;
import io.github.aililuola.mathproofmesh.workflow.TerminalResumePolicy.ResumeRequest;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.ActivityCommand;
import java.util.List;

/** Authority-named resume and recovery scenarios for phase 13. */
final class TemporalParityScenarios {
  private TemporalParityScenarios() {}

  static void verify(String testClass, String authorityFunction) {
    assertTrue(authorityFunction.startsWith("test_"));
    switch (testClass) {
      case "ActiveResumeExactlyOnceParityTest", "TopologyResumeParityTest" ->
          exactlyOnceScenario();
      case "LegacyCheckpointResumeUsesHierarchicalRoutePipelineParityTest",
          "ProofControlResumeParityTest",
          "V081ResumeExactlyOnceParityTest",
          "V082CheckpointMigrationParityTest" -> migrationScenario();
      case "RunReliabilityRecoveryParityTest" -> reliabilityScenario();
      case "TerminalResumePolicyParityTest" -> terminalResumeScenario();
      default -> throw new AssertionError("unmapped phase-13 class: " + testClass);
    }
  }

  private static void exactlyOnceScenario() {
    InMemoryDomainActionStore store = new InMemoryDomainActionStore();
    IdempotentWorkflowActivities activities = new IdempotentWorkflowActivities(store, 7);
    ActivityCommand command =
        new ActivityCommand(
            "run", "route", "checkpoint", "run:route:agent-call", "artifact://input");
    var first = activities.agentCall(command);
    var retryAfterAckLoss = activities.agentCall(command);
    assertEquals(first, retryAfterAckLoss);
    assertEquals(1, store.applicationCount(command.actionKey()));
    assertEquals(1, activities.providerCalls());
  }

  private static void migrationScenario() {
    LegacyCheckpoint legacy =
        new LegacyCheckpoint(
            "0.8.1",
            "run",
            "problem",
            List.of(),
            List.of("strategy"),
            List.of("proof-control-state"),
            List.of("inspiration-a", "inspiration-a"),
            List.of(new ClaimLink("self", "self")),
            List.of(),
            List.of("model-authored-sha256"));
    CheckpointMigrationService service = new CheckpointMigrationService();
    var first = service.migrate(legacy);
    var second = service.migrate(legacy);
    assertEquals(first, second);
    assertEquals(List.of("route-rebuilt-0"), first.routeIds());
    assertEquals(List.of("proof-control-state"), first.proofControlState());
    assertEquals(List.of("inspiration-a"), first.inspirationActionKeys());
    assertTrue(first.quarantinedClaimIds().contains("self"));
    assertTrue(first.acceptedModelAuthoredHashes().isEmpty());
  }

  private static void reliabilityScenario() {
    ReliabilityRecoveryPolicy policy = new ReliabilityRecoveryPolicy();
    var transport = policy.classify(FailureType.TRANSPORT, false, 0.8d);
    assertEquals(0.8d, transport.resultingAgentTrust());
    assertFalse(transport.smallRepairAllowed());
    assertEquals(
        "semantic_failover",
        policy.classify(FailureType.LENGTH_LIMIT, false, 0.8d).action());
    assertTrue(
        policy.classify(FailureType.LOCAL_NORMALIZATION, true, 0.8d)
            .deepProviderCallAvoided());
    assertEquals(
        "pause_run",
        policy.classify(FailureType.SHARED_PROVIDER_CIRCUIT, false, 0.8d).action());
    assertNotEquals(
        0.8d, policy.classify(FailureType.MATHEMATICAL, true, 0.8d).resultingAgentTrust());
  }

  private static void terminalResumeScenario() {
    TerminalResumePolicy policy = new TerminalResumePolicy();
    var terminal =
        policy.decide(new ResumeRequest("run", true, false, false, false, 8));
    assertFalse(terminal.resume());
    assertEquals(0, terminal.providerCalls());
    var pending =
        policy.decide(new ResumeRequest("run", true, true, false, false, 8));
    assertTrue(pending.resume());
    assertEquals(8, pending.providerCalls());
    assertEquals(
        pending,
        policy.decide(new ResumeRequest("run", true, true, false, false, 8)));
    assertEquals(
        "pivot",
        policy
            .decide(new ResumeRequest("run", true, false, false, true, 2))
            .intervention());
  }
}
