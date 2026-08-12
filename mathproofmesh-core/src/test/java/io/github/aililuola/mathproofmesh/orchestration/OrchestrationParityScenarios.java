package io.github.aililuola.mathproofmesh.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aililuola.mathproofmesh.contract.RouteRole;
import io.github.aililuola.mathproofmesh.orchestration.ContinuationFunctions.Checkpoint;
import io.github.aililuola.mathproofmesh.orchestration.teams.RiskAssessment;
import io.github.aililuola.mathproofmesh.orchestration.teams.RoleRunner;
import io.github.aililuola.mathproofmesh.orchestration.teams.RouteTeam;
import io.github.aililuola.mathproofmesh.orchestration.teams.RouteTeamFactory;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Production-facing semantic scenarios for all phase-12 authority modules. */
final class OrchestrationParityScenarios {
  private OrchestrationParityScenarios() {}

  static void verify(String testClass, String authorityFunction) {
    assertTrue(authorityFunction.startsWith("test_"));
    switch (testClass) {
      case "ActiveRouteTeamParityTest", "RouteTeamsParityTest" -> teamScenario();
      case "BudgetSchedulerParityTest",
          "CoreProofDebtParityTest",
          "SchedulerGraphSignalsParityTest",
          "SchedulerRouteFailurePropagationParityTest" -> budgetScenario();
      case "ContinuationParityTest", "RouteWaitingAndWakeParityTest" ->
          continuationAndCoordinatorScenario();
      case "CrossRoutePhaseParityTest", "RefereeClaimLedgerPropagationParityTest" ->
          brokerScenario();
      case "DeepExplorationPolicyParityTest",
          "DeepExplorationRuntimeParityTest",
          "ProofIdentityAndStagnationParityTest" -> deepExplorationScenario();
      case "FailureClassificationAndBlueprintParityTest",
          "VerifierIssueRiskMappingParityTest" -> failureAndRiskScenario();
      case "NoLegacyClaimRevisionBypassParityTest",
          "NoLegacyClaimSynthesisBypassParityTest" -> synthesisScenario();
      case "V082StaticNoSpecializationParityTest" -> staticBoundaryScenario();
      default -> throw new AssertionError("unmapped phase-12 parity class: " + testClass);
    }
  }

  private static void teamScenario() {
    EnumMap<RouteRole, List<String>> agents = new EnumMap<>(RouteRole.class);
    agents.put(RouteRole.SKEPTIC, List.of("skeptic"));
    agents.put(RouteRole.TOOL_SPECIALIST, List.of("tool"));
    agents.put(RouteRole.REFEREE, List.of("author", "referee"));
    RoleRunner runner = new RoleRunner(agents);
    RouteTeam team = new RouteTeam(0.45d);
    RiskAssessment risk =
        team.classifyRisk(
            new RouteTeam.RiskSignals(
                true, false, false, true, false, false, false, false, true, true));
    var plan = new RouteTeamFactory(runner).plan("route-a", "author", risk);
    assertEquals("skeptic", plan.skeptic().agentId());
    assertEquals("tool", plan.toolSpecialist().agentId());
    assertEquals("referee", plan.referee().agentId());
    assertNotEquals(plan.prover().agentId(), plan.referee().agentId());
    assertTrue(team.review(plan, true, true, true).globalShareAllowed());
    assertFalse(team.review(plan, true, false, true).globalShareAllowed());

    RiskAssessment low =
        team.classifyRisk(
            new RouteTeam.RiskSignals(
                false, false, false, false, false, false, false, false, false, false));
    assertFalse(low.needsSkeptic());
    assertNull(new RouteTeamFactory(runner).plan("route-b", "author-b", low).skeptic());
  }

  private static void budgetScenario() {
    SoftBudgetAllocator.Allocation allocation =
        new SoftBudgetAllocator().allocate(20, 4, null);
    assertEquals(16, allocation.schedulableCalls());
    assertEquals(4, allocation.finishReserve());
    assertEquals(16, allocation.calls().values().stream().mapToInt(Integer::intValue).sum());

    AdaptiveBudgetManager scheduler = new AdaptiveBudgetManager(4, 2);
    var decision =
        scheduler.decide(
            "decision-a",
            List.of(
                new AttemptEvidence(
                    "route-a",
                    false,
                    AttemptEvidence.FailureClass.STRUCTURAL,
                    2.0d,
                    0.8d,
                    2,
                    true)),
            1,
            10,
            0.2d,
            0.8d);
    assertTrue(decision.forcedWiden());
    assertEquals(decision, scheduler.decide("decision-a", List.of(), 4, 0, 1.0d, 0.0d));
    assertTrue(decision.actions().stream().allMatch(action -> action.rank() != null));

    RouteReviewEvidence review =
        new RouteReviewEvidence("route-a", "author", "reviewer", true, false, 0.9d, "");
    assertTrue(review.globallyShareable());
    assertThrows(
        IllegalArgumentException.class,
        () -> new RouteReviewEvidence("route", "same", "same", true, false, 1.0d, ""));
  }

  private static void continuationAndCoordinatorScenario() {
    Checkpoint seed = checkpoint();
    ContinuationFunctions.CheckpointLedger ledger = new ContinuationFunctions.CheckpointLedger();
    ledger.seed(seed);
    var delta = ContinuationFunctions.boundedDelta(seed, "author", "reviewer", 4, 2, true);
    var committed = ledger.commit("main", delta);
    assertTrue(committed.committed());
    assertEquals(1, committed.checkpoint().segmentIndex());
    assertThrows(
        IllegalStateException.class,
        () -> ledger.commit("main", delta));
    var rejected =
        ContinuationFunctions.boundedDelta(
            committed.checkpoint(), "author", "reviewer", 1, 1, false);
    assertFalse(ledger.commit("main", rejected).committed());
    assertEquals(committed.checkpoint(), ledger.latest("main"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ContinuationFunctions.boundedDelta(seed, "author", "reviewer", 17, 0, true));

    InProcessRunCoordinator coordinator = new InProcessRunCoordinator();
    var lease = coordinator.acquire("run-a", "worker-a");
    assertThrows(
        IllegalStateException.class, () -> coordinator.acquire("run-a", "worker-b"));
    var paused =
        coordinator.execute(
            lease,
            InProcessRunCoordinator.MockOutcome.PARTIAL,
            null,
            committed.checkpoint());
    assertEquals(InProcessRunCoordinator.Status.PAUSED, paused.status());
    assertNotNull(paused.lastCommittedCheckpoint());
    var completed = coordinator.resume(lease, InProcessRunCoordinator.MockOutcome.COMPLETE);
    assertEquals(InProcessRunCoordinator.Status.COMPLETED, completed.status());
  }

  private static void brokerScenario() {
    BrokerPhaseService broker = new BrokerPhaseService();
    var accepted =
        new BrokerPhaseService.ReviewedClaim(
            "c1",
            "verified statement",
            List.of(),
            List.of("evidence"),
            "delta-a",
            "author-secret",
            "raw hidden reasoning",
            true,
            true,
            true);
    var wrongDelta =
        new BrokerPhaseService.ReviewedClaim(
            "c2",
            "other statement",
            List.of(),
            List.of(),
            "delta-b",
            "author",
            "raw",
            true,
            true,
            true);
    List<BrokerPhaseService.BrokerPacket> packets =
        new CrossRoutePhaseService(broker).share(List.of(accepted, wrongDelta), "delta-a");
    assertEquals(1, packets.size());
    assertTrue(packets.getFirst().crossRouteBoundary());
    assertFalse(packets.getFirst().toString().contains("author-secret"));
    assertFalse(CrossRoutePhaseService.directRouteBypassAllowed());
  }

  private static void deepExplorationScenario() {
    ExplorationSignature signature =
        new ExplorationSignature("number-theory", "modular", List.of("reduce"));
    assertEquals(
        signature,
        new ExplorationSignature("number-theory", "modular", List.of("reduce")));
    DeepExplorationRegistry registry = new DeepExplorationRegistry();
    ExplorationEvidence capacity =
        new ExplorationEvidence(false, false, false, false, 20, 2, 0);
    var lease = registry.admit(signature, ExplorationModel.DEEP_96K, capacity);
    assertTrue(lease.accepted());
    assertFalse(registry.admit(signature, ExplorationModel.DEEP_96K, capacity).accepted());
    assertFalse(
        new DeepExplorationRegistry()
            .admit(signature, ExplorationModel.DEEP_128K, capacity)
            .accepted());
    var record =
        registry.complete(
            lease,
            signature,
            new ExplorationOutcome(
                false,
                false,
                false,
                ExplorationOutcome.Failure.NO_ARTIFACT,
                2,
                "no artifact"));
    assertEquals(1, record.strikeCount());
    assertFalse(registry.running(signature));
    assertEquals(60, DeepExplorationRegistry.firstChunkTimeoutSeconds());
    assertEquals(300, DeepExplorationRegistry.streamStallTimeoutSeconds());
    assertFalse(DeepExplorationRegistry.elapsedTimeCountsAsMathematicalProgress());
  }

  private static void failureAndRiskScenario() {
    Checkpoint checkpoint = checkpoint();
    PostFailureBottleneckExtractor extractor = new PostFailureBottleneckExtractor();
    var result =
        extractor.extract(
            "reasoning_only_stall",
            checkpoint,
            List.of("step-1"),
            List.of("obligation-1"),
            true);
    assertNotNull(result);
    assertFalse(result.privateReasoningRecovered());
    assertTrue(
        extractor
            .extract(
                "reasoning_only_stall",
                checkpoint,
                List.of(),
                List.of(),
                true)
            .reused());
    assertThrows(
        IllegalArgumentException.class,
        () -> extractor.extract("http_error", checkpoint, List.of(), List.of(), true));
    new BottleneckPivotRecord(
        "route", "bottleneck", "prior", "replacement", true, "certified pivot");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BottleneckPivotRecord(
                "route", "bottleneck", "prior", "replacement", false, "timer"));
  }

  private static void synthesisScenario() {
    SynthesisPhaseService service = new SynthesisPhaseService();
    var dependency =
        new SynthesisPhaseService.VerifiedClaim(
            "c0", "lemma", List.of(), "author-0", true, true);
    var conclusion =
        new SynthesisPhaseService.VerifiedClaim(
            "c1", "theorem", List.of("c0"), "author-1", true, true);
    var packet =
        service.synthesize(
            List.of(dependency, conclusion),
            Set.of("c1"),
            List.of(
                new SynthesisPhaseService.NegativeEvidence(
                    "counterexample", "excluded boundary case", true)),
            100);
    assertEquals(2, packet.verifiedFactPackets().size());
    assertTrue(packet.complete());
    assertFalse(packet.toString().contains("author-1"));
    assertThrows(
        IllegalStateException.class,
        () ->
            service.synthesize(
                List.of(
                    new SynthesisPhaseService.VerifiedClaim(
                        "bad", "unreviewed", List.of(), "author", true, false)),
                Set.of("bad"),
                List.of(),
                0));
  }

  private static void staticBoundaryScenario() {
    assertEquals(
        RoutePipelineFunctions.RunStage.TRIAGE,
        RoutePipelineFunctions.next(RoutePipelineFunctions.RunStage.FREEZE_PROBLEM));
    var context =
        RoutePipelineFunctions.isolatedInitialContext(
            "strategy", List.of("verified fact"), List.of("raw"), List.of("other"));
    assertTrue(context.isolated());
    assertTrue(context.rawTranscripts().isEmpty());
    assertTrue(context.otherRouteReasoning().isEmpty());
    assertEquals(15, RoutePipelineFunctions.FIXED_STAGES.size());
    assertFalse(CrossRoutePhaseService.directRouteBypassAllowed());
  }

  private static Checkpoint checkpoint() {
    return new Checkpoint(
        "checkpoint-0", "", "problem-hash", "path-a", "strategy-a", 0, "main", true);
  }
}
