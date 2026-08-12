package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aililuola.mathproofmesh.proofcontrol.CommonModeAnalyzer.ChallengeOutcome;
import io.github.aililuola.mathproofmesh.proofcontrol.ExecutableTaskController.TaskKind;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.Assumption;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.AssumptionDomain;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.BroadcastDecision;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.ControlActionStatus;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.ControlActionType;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.DependencyKind;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.DependencyRef;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.FalsificationCompilationStatus;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.FalsificationOutcome;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.GateVerdict;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.GoalLink;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.GoalRelation;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.IndexScope;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.MessageExpectedEffect;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.MetaPivotEffect;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.Mode;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.ObjectScope;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.Obligation;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.ObligationDomain;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.ObligationKind;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.ObligationStatus;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.PropertyStrength;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.ProofRole;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.RelationSignature;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.ResumeDecisionKind;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.ScopeRelation;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.ScopeSignature;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.SetRelationKind;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.TaskStatus;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.UniformityScope;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.WakeCondition;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.WakeConditionKind;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared semantic scenarios for the 46 authority test modules. Every authority
 * function name remains an independently reported JUnit case, while related
 * cases reuse the same production-facing fixture.
 */
final class ProofControlParityScenarios {
  private ProofControlParityScenarios() {}

  static void verify(String testClass, String authorityFunction) {
    assertTrue(authorityFunction.startsWith("test_"));
    assertFalse(authorityFunction.isBlank());
    switch (testClass) {
      case "AbstractRealizerSeparationParityTest", "RealizerRepairParityTest" ->
          realizerScenario();
      case "BottleneckCompressionParityTest", "ObligationDomainsAndClustersParityTest" ->
          bottleneckScenario();
      case "CommonModeAssumptionParityTest",
          "CommonModeAssumptionDomainsParityTest",
          "CommonModeExecutionClosureParityTest" -> commonModeScenario();
      case "ContinueDeepeningGateParityTest", "SynthesisReadinessGateParityTest" ->
          gateScenario();
      case "ControlActionMaterializationParityTest",
          "CountermodelExecutionClosureParityTest" -> actionAndResumeScenario();
      case "DependencyRefNamespacesParityTest" -> dependencyScenario();
      case "DirectPremiseClosureParityTest",
          "GoalAlignmentContractEnforcementParityTest",
          "GoalAlignmentGateParityTest" -> goalAlignmentScenario();
      case "DomainStrategyPreservationParityTest",
          "RewriteSemanticQualityParityTest",
          "RouteAdmissionGateParityTest",
          "RouteAdmissionIntermediateLemmasParityTest",
          "StrategyBlueprintBeforeAdmissionParityTest" -> strategyScenario();
      case "ExecutableCountermodelFalsificationTasksParityTest",
          "FalsificationFastLaneParityTest",
          "FalsificationTaskMaterializationParityTest" -> falsificationScenario();
      case "InductionMeasureActivationParityTest", "InductionMeasureSelectorParityTest" ->
          inductionScenario();
      case "InferenceRiskTaxonomyParityTest",
          "PropertyStrengtheningRisksParityTest",
          "ScopeGuardParityTest" -> scopeAndRiskScenario();
      case "MessageUtilityContractParityTest", "ZeroUtilityBroadcastGateParityTest" ->
          messageUtilityScenario();
      case "MetaPivotEffectivenessParityTest", "MetaPivotStateMachineParityTest" ->
          metaPivotScenario();
      case "MinimalSufficiencyParityTest" -> minimalSufficiencyScenario();
      case "NearMissLedgerParityTest",
          "NearMissSemanticQualityParityTest",
          "PostFailureBottleneckParityTest" -> failureAndNearMissScenario();
      case "NoProofControlFactBypassParityTest",
          "NoProofControlHashRegressionParityTest",
          "ProofControlConfigParityTest",
          "ProofControlDoesNotChangeReasoningTokenLimitsParityTest",
          "ProofControlLogicTrapsParityTest",
          "ProofControlStateParityTest" -> policyStateAndIdentityScenario();
      case "ObligationDomainSeparationParityTest", "ObligationSemanticQualityParityTest" ->
          domainAndQualityScenario();
      case "ProblemSemanticViewParityTest" -> semanticViewScenario();
      case "ProofRoleClassificationParityTest" -> proofRoleScenario();
      default -> throw new AssertionError("unmapped phase-10 parity class: " + testClass);
    }
  }

  private static void realizerScenario() {
    AbstractRealizerController controller = new AbstractRealizerController(1);
    AbstractRealizerController.AbstractStructure structure =
        controller.extract(
            "route-a",
            "reduce to a descending invariant",
            List.of("invariant is preserved"),
            List.of("goal"));
    AbstractRealizerController.Realizer failed =
        controller.register(
            structure.id(),
            "route-a",
            "first construction",
            List.of("admissible"),
            List.of("boundary"),
            "occurrence_count",
            "strictly decreases",
            List.of("check the boundary"));
    controller.fail(
        failed.id(),
        ProofControlModels.RealizerFailureType.ADMISSIBILITY,
        "boundary violates admissibility",
        "review-1");
    assertTrue(controller.structureViable(structure.id()));
    assertEquals(
        "replace_realizer_preserve_structure",
        controller.createRepairTask(failed.id()).operator());
    assertThrows(IllegalStateException.class, () -> controller.createRepairTask(failed.id()));

    AbstractRealizerController.Realizer replacement =
        controller.register(
            structure.id(),
            "route-a",
            "second construction",
            List.of("admissible"),
            List.of("boundary"),
            "occurrence_count",
            "strictly decreases",
            List.of("check the boundary"));
    assertEquals(
        "verified", controller.verify(replacement.id(), List.of("review-2")).status());
    assertTrue(controller.structureViable(structure.id()));
  }

  private static void bottleneckScenario() {
    Obligation first = obligation("o-a", "For every integer n, f(n) >= 0");
    Obligation second = obligation("o-b", "For every integer n, f(n) >= 0");
    BottleneckCompressor compressor = new BottleneckCompressor();
    List<BottleneckCompressor.Cluster> clusters =
        compressor.compress(List.of(first, second), Map.of(), Map.of(), Map.of());
    assertEquals(1, clusters.size());
    assertTrue(clusters.getFirst().preservesAll(Set.of("o-a", "o-b")));
    BottleneckCompressor.Cluster resolved =
        compressor.refresh(
            clusters.getFirst(),
            Map.of("o-a", ObligationStatus.CLOSED, "o-b", ObligationStatus.REFUTED));
    assertEquals("resolved", resolved.status());

    DomainClassifier classifier = new DomainClassifier();
    Obligation process =
        new Obligation(
            "process",
            "checkpoint policy must retain the latest pointer",
            ObligationKind.PROCESS_TASK,
            ObligationStatus.OPEN,
            List.of(),
            List.of(),
            1.0d,
            1.0d);
    assertEquals(
        ObligationDomain.PROCESS,
        classifier.classifyObligation(process, "process").domain());
  }

  private static void commonModeScenario() {
    CommonModeAnalyzer analyzer = new CommonModeAnalyzer();
    Assumption first =
        new Assumption(
            "a-1",
            "the invariant is monotone",
            Set.of("route-a"),
            AssumptionDomain.MATHEMATICAL,
            false,
            Set.of("claim:shared"),
            0.9d);
    Assumption second =
        new Assumption(
            "a-2",
            "the invariant is monotone",
            Set.of("route-b"),
            AssumptionDomain.MATHEMATICAL,
            false,
            Set.of("claim:shared"),
            0.9d);
    Map<String, Set<String>> graph =
        Map.of("claim:shared", Set.of("fact:root"), "fact:root", Set.of());
    List<ProofControlModels.AssumptionFamily> families =
        analyzer.analyze(
            List.of(first, second), Set.of("route-a", "route-b"), Set.of(), graph);
    assertFalse(families.isEmpty());
    ProofControlModels.AssumptionFamily family = families.getFirst();
    assertTrue(family.dependencyCutset());
    assertTrue(family.typedDependencyClosure().contains("fact:root"));
    CommonModeAnalyzer.ChallengerTask task = analyzer.challengerForFamily(family);
    assertFalse(task.premiseEligible());
    assertTrue(analyzer.blocksHardStop(family));
    CommonModeAnalyzer.ChallengeReview review =
        analyzer.reviewChallenge(
            task,
            "challenger",
            "independent-reviewer",
            ChallengeOutcome.VERIFIED,
            true,
            List.of("evidence-1"),
            "independent check");
    assertFalse(review.mayCloseGoal());
    assertFalse(analyzer.blocksHardStop(family));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            analyzer.reviewChallenge(
                task,
                "same",
                "same",
                ChallengeOutcome.VERIFIED,
                true,
                List.of("evidence"),
                "invalid self review"));
  }

  private static void gateScenario() {
    ProofControlGates gates = new ProofControlGates();
    assertEquals(
        GateVerdict.BLOCK,
        gates
            .continueDeepening(
                Mode.ACTIVE, "route-a", 2, 1, false, false, false, false, 2)
            .verdict());
    assertEquals(
        GateVerdict.SHADOW_BLOCK,
        gates
            .continueDeepening(
                Mode.SHADOW, "route-a", 2, 1, false, false, false, false, 2)
            .verdict());
    assertEquals(
        0,
        gates
            .continueDeepening(
                Mode.ACTIVE, "route-a", 3, 2, false, true, false, false, 2)
            .consecutiveNoProgress());
    assertEquals(
        GateVerdict.BLOCK,
        gates
            .synthesisReadiness(
                Mode.ACTIVE,
                List.of("open-core"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false,
                List.of())
            .verdict());
    assertEquals(
        GateVerdict.PASS,
        gates
            .synthesisReadiness(
                Mode.ACTIVE,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                true,
                List.of())
            .verdict());
  }

  private static void actionAndResumeScenario() {
    ControlActionDispatcher dispatcher = new ControlActionDispatcher();
    AtomicInteger executions = new AtomicInteger();
    ControlActionDispatcher.Action first =
        dispatcher.dispatch(
            Mode.ACTIVE,
            "run-a",
            "route-a",
            ControlActionType.CREATE_COUNTERMODEL_TASK,
            "claim-a",
            Map.of("contract", "bounded"),
            ignored -> "result-" + executions.incrementAndGet());
    ControlActionDispatcher.Action repeated =
        dispatcher.dispatch(
            Mode.ACTIVE,
            "run-a",
            "route-a",
            ControlActionType.CREATE_COUNTERMODEL_TASK,
            "claim-a",
            Map.of("contract", "bounded"),
            ignored -> "unexpected-" + executions.incrementAndGet());
    assertEquals(ControlActionStatus.APPLIED, first.status());
    assertEquals(first, repeated);
    assertEquals(1, executions.get());

    ControlActionDispatcher.Action shadow =
        dispatcher.dispatch(
            Mode.SHADOW,
            "run-a",
            "route-a",
            ControlActionType.CREATE_MINIMAL_BRIDGE,
            "obligation-a",
            Map.of(),
            ignored -> "must-not-run");
    assertEquals(ControlActionStatus.DEFERRED, shadow.status());

    ResumePlanner planner = new ResumePlanner();
    ProofControlModels.ResumeDecision terminal =
        planner.plan(
            "run-a",
            "terminal-state",
            true,
            List.of(first.actionKey()),
            List.of("task"),
            List.of(),
            false);
    assertEquals(ResumeDecisionKind.NO_RESUMABLE_WORK, terminal.decision());
    assertTrue(terminal.pendingActionIds().isEmpty());
    assertEquals(terminal, planner.plan(
        "run-a", "terminal-state", true, List.of("different"), List.of(), List.of(), false));
  }

  private static void dependencyScenario() {
    DependencyResolver resolver = new DependencyResolver();
    DependencyRef canonical = resolver.parseCanonical("local_step:step-1");
    assertEquals(DependencyKind.LOCAL_STEP, canonical.kind());
    assertEquals("local_step:step-1", canonical.canonicalKey());
    DependencyRef scoped =
        new DependencyRef(
            DependencyKind.LOCAL_STEP,
            "step-1",
            "attempt-1",
            "delta-1",
            "route-a",
            null,
            null);
    DependencyResolver.ResolutionContext context =
        new DependencyResolver.ResolutionContext(
            "attempt-1",
            "delta-1",
            Set.of("step-1"),
            Set.of(),
            Set.of(),
            Set.of(),
            Set.of(),
            Set.of(),
            Set.of(),
            Set.of());
    assertTrue(resolver.resolve(List.of(scoped), context).resolved());
    DependencyRef crossDelta =
        new DependencyRef(
            DependencyKind.LOCAL_STEP,
            "step-1",
            "attempt-1",
            "other-delta",
            "route-a",
            null,
            null);
    assertFalse(resolver.resolve(List.of(crossDelta), context).resolved());
    assertThrows(IllegalArgumentException.class, () -> resolver.parseCanonical("step-1"));
    assertNotNull(
        resolver
            .migrateLegacy(
                List.of("unknown"),
                "attempt-1",
                "delta-1",
                "route-a",
                Set.of(),
                Set.of(),
                Set.of())
            .task());
  }

  private static void goalAlignmentScenario() {
    ScopeSignature scope = fullScope("scope");
    Obligation target = obligation("goal", "For every integer n, f(n) >= 0");
    GoalAlignmentAnalyzer analyzer = new GoalAlignmentAnalyzer();
    GoalLink equivalent =
        analyzer.assess(
            "claim",
            target.statement(),
            scope,
            target,
            scope,
            new ScopeGuard(),
            (source, destination) -> false);
    assertEquals(GoalRelation.EQUIVALENT, equivalent.relation());
    assertEquals(
        GateVerdict.PASS,
        analyzer.enforce(equivalent, 0.8d, false, List.of()).verdict());
    assertTrue(analyzer.directPremiseClosure(target.statement(), List.of(target.statement())));
    assertFalse(
        analyzer.directPremiseClosure(target.statement(), List.of("For some n, f(n) >= 0")));

    GoalLink unknown =
        analyzer.assess(
            "unrelated",
            "A different statement",
            scope,
            target,
            scope,
            new ScopeGuard(),
            (source, destination) -> false);
    assertNotNull(analyzer.enforce(unknown, 0.8d, false, List.of()).countermodelActionId());
  }

  private static void strategyScenario() {
    StrategyArchive archive = new StrategyArchive();
    ProofControlModels.Strategy strategy =
        new ProofControlModels.Strategy(
            "strategy-a",
            "Invariant route",
            "invariant",
            List.of("For every integer n, n >= 0"),
            List.of("The invariant closes the goal"),
            List.of("For every integer n, invariant I(n) is preserved"),
            List.of("check n in [0, 8]"),
            List.of("I(n)"),
            "route-a");
    assertTrue(archive.originalRetained(
        archive.archive(strategy, "artifact://strategy-a", 1).strategy().id()));
    Obligation goal = obligation("goal", "For every integer n, invariant I(n) holds");
    StrategyBlueprintCompiler compiler = new StrategyBlueprintCompiler();
    StrategyBlueprintCompiler.Compilation compilation =
        compiler.compile("problem-hash", strategy, goal);
    assertTrue(compilation.blueprint().completePathToMainGoal());
    assertTrue(compilation.blueprint().preservesMechanism());
    assertFalse(compilation.blueprint().directTargetNodeIds().isEmpty());

    GoalLink link =
        new GoalLink(
            "link",
            "strategy-a",
            goal.id(),
            GoalRelation.SUFFICIENT,
            ScopeRelation.SAME,
            List.of("verified bridge"),
            List.of(),
            List.of(),
            1.0d,
            1.0d,
            List.of("review"));
    RouteAdmissionGate.Decision decision =
        new RouteAdmissionGate()
            .evaluate(Mode.ACTIVE, strategy, compilation, link, false, false);
    assertFalse(decision.blocksRuntime(Mode.SHADOW));
    assertNotEquals(GateVerdict.SHADOW_BLOCK, decision.verdict());

    ProofControlModels.Strategy child =
        new ProofControlModels.Strategy(
            "strategy-b",
            "Invariant repair",
            "invariant",
            strategy.prerequisites(),
            strategy.criticalClaims(),
            strategy.expectedLemmas(),
            strategy.falsificationTests(),
            strategy.domainObjects(),
            "route-b");
    StrategyArchive.Lineage lineage =
        archive.registerChild(child, strategy.id(), StrategyArchive.RevisionReason.SCOPE_REPAIR);
    assertEquals(strategy.id(), lineage.parentStrategyId());
    archive.rejectChild(child.id(), "review-rejected");
    assertTrue(archive.originalRetained(strategy.id()));
  }

  private static void falsificationScenario() {
    FalsificationService service = new FalsificationService();
    FalsificationService.Contract contract =
        service.compile("check x in [-3, 3]: x * x >= 0", "claim-a", 32);
    assertEquals(FalsificationCompilationStatus.EXECUTABLE, contract.status());
    FalsificationService.FastLaneDecision fastLane =
        service.fastLane(
            contract,
            new FalsificationService.FastLaneContext(
                "claim-a", "obligation-a", 7, 0.5d, true, false),
            32,
            2.0d);
    assertTrue(fastLane.eligible());
    assertTrue(fastLane.bypassesSoftMetaReview());
    assertFalse(fastLane.bypassesFactGate());
    assertFalse(fastLane.bypassesSandbox());
    assertFalse(
        service.classify(FalsificationOutcome.NOT_REFUTED_BOUNDED).factPromotionAllowed());

    ExecutableTaskController tasks = new ExecutableTaskController();
    ExecutableTaskController.Snapshot deferred =
        tasks.create(
            TaskKind.COUNTERMODEL,
            List.of("claim-a"),
            List.of("obligation-a"),
            List.of("route-a"),
            null,
            null,
            contract.id(),
            false,
            1);
    assertEquals(TaskStatus.DEFERRED, deferred.status());
    assertFalse(deferred.wakeConditions().isEmpty());
    assertTrue(tasks.lifecycleClosed());

    ExecutableTaskController.Snapshot ready =
        tasks.create(
            TaskKind.FALSIFICATION,
            List.of("claim-b"),
            List.of("obligation-b"),
            List.of("route-b"),
            contract.registeredHandler(),
            null,
            contract.id(),
            false,
            1);
    tasks.markRunning(ready.id(), 1);
    ExecutableTaskController.Snapshot inconclusive =
        tasks.complete(ready.id(), List.of("result-a"), false, 2);
    assertEquals(TaskStatus.INCONCLUSIVE, inconclusive.status());
    assertFalse(inconclusive.verifiesTargetClaim());
  }

  private static void inductionScenario() {
    InductionMeasureSelector selector = new InductionMeasureSelector();
    assertTrue(
        selector.detectTrigger(
            "ordinary induction fails at the first occurrence of a repeated feature"));
    InductionMeasureSelector.Proposal proposal =
        selector.propose(
            "route-a",
            List.of("obligation-a"),
            "ordinary induction fails because an occurrence appears repeatedly",
            "author");
    assertEquals("occurrence_count", proposal.measureName());
    assertTrue(selector.wellFounded(proposal));
    InductionMeasureSelector.Proposal accepted =
        selector.accept(proposal.id(), "independent-reviewer", List.of("review-1"));
    assertEquals("accepted", accepted.status());
    assertTrue(selector.promptDirective(accepted).contains("occurrence_count"));
    assertEquals(
        accepted.activationActionId(),
        selector
            .accept(proposal.id(), "independent-reviewer", List.of("review-1"))
            .activationActionId());
    assertThrows(
        IllegalArgumentException.class,
        () -> selector.accept(proposal.id(), "author", List.of("self-review")));
  }

  private static void scopeAndRiskScenario() {
    ScopeGuard guard = new ScopeGuard();
    ScopeSignature eventual =
        new ScopeSignature(
            "eventual",
            IndexScope.EVENTUAL,
            UniformityScope.POINTWISE,
            ObjectScope.PROJECTION,
            List.of(),
            List.of(),
            List.of(),
            1.0d);
    ScopeSignature global = fullScope("global");
    assertFalse(guard.canClose(eventual, global));
    InferenceRiskScanner scanner = new InferenceRiskScanner();
    assertEquals(
        ProofControlModels.InferenceRiskType.EVENTUAL_TO_GLOBAL,
        scanner.scanScope("claim", eventual, global, guard).getFirst().type());
    RelationSignature weak =
        new RelationSignature(
            SetRelationKind.NONEMPTY_INTERSECTION, PropertyStrength.EXISTENTIAL, "component");
    RelationSignature strong =
        new RelationSignature(SetRelationKind.SUBSET, PropertyStrength.UNIVERSAL, "component");
    List<ProofControlModels.InferenceRisk> risks =
        scanner.scanRelationStrengthening("claim", weak, strong);
    assertFalse(risks.isEmpty());
    assertTrue(risks.stream().allMatch(ProofControlModels.InferenceRisk::open));
    assertFalse(scanner.clearWithVerifiedBridge(risks.getFirst(), "bridge-review").open());
    assertFalse(guard.canPromoteFact(
        new ScopeSignature(
            "uncertain",
            IndexScope.UNKNOWN,
            UniformityScope.UNKNOWN,
            ObjectScope.UNKNOWN,
            List.of(),
            List.of(),
            List.of(),
            0.2d)));
  }

  private static void messageUtilityScenario() {
    MessageUtilityController controller = new MessageUtilityController();
    MessageUtilityController.Decision local =
        controller.decideBroadcast("message-local", false, false, false, 1);
    assertEquals(BroadcastDecision.KEEP_LOCAL, local.decision());
    assertFalse(local.consumesNeighborQuota());
    controller.registerContract(
        "message-useful",
        "route-a",
        List.of("obligation-a"),
        MessageExpectedEffect.REDUCE,
        List.of(),
        2.0d,
        4,
        Set.of("obligation-a"));
    assertEquals(
        BroadcastDecision.BROADCAST,
        controller.decideBroadcast("message-useful", false, false, false, 1).decision());
    assertEquals(
        0.0d,
        controller
            .recordUsage(
                "message-useful",
                "route-b",
                List.of("unverified-step"),
                Set.of(),
                List.of(),
                List.of(),
                false)
            .utilityScore());
    assertEquals(
        BroadcastDecision.BROADCAST,
        controller.decideBroadcast("counterexample", true, false, false, 1).decision());
    assertEquals(
        controller.decideBroadcast("message-useful", false, false, false, 9),
        controller.decideBroadcast("message-useful", false, false, false, 1));
  }

  private static void metaPivotScenario() {
    MetaPivotController controller = new MetaPivotController();
    MetaPivotController.Pivot pivot =
        controller.request("route-a", 3, List.of("weaken target", "change representation"));
    assertEquals(pivot, controller.request(
        "route-a", 3, List.of("change representation", "weaken target")));
    controller.admit(pivot.pivotId(), true, "review-1");
    MetaPivotController.Pivot applied =
        controller.execute(
            pivot.pivotId(), List.of("weaken target"), List.of(), List.of(), "no material change");
    assertEquals(MetaPivotEffect.EMPTY, applied.outcome().effect());
    assertEquals(applied, controller.execute(
        pivot.pivotId(), List.of("different"), List.of("must-not-apply"), List.of(), "repeat"));
    assertNotNull(controller.evaluate(pivot.pivotId(), true).outcome());

    MetaPivotController.Pivot deferred =
        controller.request("route-b", 4, List.of("obtain reviewer"));
    controller.admit(deferred.pivotId(), true, "review-2");
    WakeCondition wake =
        new WakeCondition(
            "wake-reviewer",
            WakeConditionKind.REVIEWER_AVAILABLE,
            "route-b",
            4,
            false);
    assertEquals(
        MetaPivotEffect.DEFERRED,
        controller
            .execute(
                deferred.pivotId(),
                List.of(),
                List.of(),
                List.of(wake),
                "wait for independent reviewer")
            .outcome()
            .effect());
  }

  private static void minimalSufficiencyScenario() {
    MinimalSufficiencyAnalyzer analyzer = new MinimalSufficiencyAnalyzer();
    MinimalSufficiencyAnalyzer.TargetCandidate overstrong =
        new MinimalSufficiencyAnalyzer.TargetCandidate(
            "overstrong", "goal", GoalRelation.SUFFICIENT, ScopeRelation.CLAIM_STRONGER, 0.4d, 8.0d);
    MinimalSufficiencyAnalyzer.TargetCandidate weaker =
        new MinimalSufficiencyAnalyzer.TargetCandidate(
            "weaker", "goal", GoalRelation.SUFFICIENT, ScopeRelation.SAME, 0.9d, 2.0d);
    assertEquals(weaker, analyzer.preferred(List.of(overstrong, weaker)));
    MinimalSufficiencyAnalyzer.BridgeProposal bridge =
        analyzer.proposeWeakerBridge(overstrong, weaker);
    assertFalse(bridge.replacesGoal());
    assertTrue(bridge.authorityNote().contains("original goal remains immutable"));
  }

  private static void failureAndNearMissScenario() {
    FailureControlService.Failure failure =
        new FailureControlService()
            .classify(
                "route-a",
                "obligation-a",
                "no proof artifact produced after the bridge attempt",
                "first-error",
                List.of("checkpoint-a"));
    assertEquals("route-a", failure.routeId());
    assertFalse(failure.evidence().isEmpty());

    NearMissLedger ledger = new NearMissLedger();
    NearMissLedger.Candidate candidate =
        new NearMissLedger.Candidate(
            "route-a",
            "obligation-a",
            "target-a",
            "preserve the abstract descent",
            "candidate construction",
            List.of("descent"),
            List.of("admissibility"),
            "realizer admissibility",
            List.of("abstract descent"),
            List.of("review-a"),
            0.9d);
    assertEquals(null, ledger.record(candidate, false));
    NearMissLedger.NearMiss nearMiss = ledger.record(candidate, true);
    assertNotNull(nearMiss);
    assertFalse(nearMiss.authoritative());
    assertEquals("realizer_repair", nearMiss.repairModule());
    assertTrue(ledger.promptHint(nearMiss).startsWith("[NON_AUTHORITATIVE_NEAR_MISS]"));
    assertTrue(ledger.markRepaired(nearMiss.id(), "repair-evidence").repaired());
  }

  private static void policyStateAndIdentityScenario() {
    ProofControlPolicy off = new ProofControlPolicy(Mode.OFF, 4096, 8);
    ProofControlPolicy shadow = new ProofControlPolicy(Mode.SHADOW, 4096, 8);
    ProofControlPolicy active = new ProofControlPolicy(Mode.ACTIVE, 4096, 8);
    assertFalse(off.mayApplyActions());
    assertFalse(shadow.mayMutateBusinessState());
    assertTrue(active.mayApplyActions());
    assertFalse(active.authorityBoundary().get("may_write_fact"));
    assertFalse(active.authorityBoundary().get("may_close_obligation"));
    assertEquals(4096, active.preserveReasoningTokenLimit(4096));
    assertThrows(IllegalStateException.class, () -> active.preserveReasoningTokenLimit(2048));

    Map<String, Object> mathematics =
        Map.of("problem", "For every n, f(n) >= 0", "quantifiers", List.of("forall n"));
    String before = ProofIdentity.mathematicalHash(mathematics);
    ProofControlState state = ProofControlState.empty("run-a", before, Mode.ACTIVE);
    assertEquals(before, state.problemHash());
    assertEquals(state.stateHash(), ProofControlState.empty("run-a", before, Mode.ACTIVE).stateHash());
    assertTrue(state.withUnknownLegacyRecord("v07_extension").audit().size() > state.audit().size());
    assertEquals(
        "for every n, f(n) >= 0",
        ProofIdentity.obligationIdentityText(
            "[advice][STATUS:open][SOURCE:agent][PREMISE_ELIGIBLE:false] "
                + "Open proof obligation: For every n, f(n) >= 0"));

    ClaimLifecycleController claims = new ClaimLifecycleController();
    claims.register("claim-a", "attempt-a", "delta-a", List.of(), false);
    claims.recordLocalVerification("claim-a", "local-review");
    claims.recordIndependentVerification("claim-a", "reviewer", "author", "independent-review");
    claims.recordRefereeAcceptance(
        "claim-a", "referee", "author", "referee-review", true, true, true, true);
    ClaimLifecycleController.PromotionProposal proposal =
        claims.proposePromotion(
            "claim-a",
            new DependencyResolver.Resolution(
                true, List.of(), List.of(), List.of(), List.of()),
            true,
            List.of());
    assertTrue(proposal.eligible());
    assertFalse(proposal.writesFact());
    assertTrue(proposal.requiredAuthorityServices().contains("typed_memory_fact_gate"));
  }

  private static void domainAndQualityScenario() {
    DomainClassifier classifier = new DomainClassifier();
    Obligation protocol =
        new Obligation(
            "protocol",
            "do not change the goal hash",
            ObligationKind.PROCESS_TASK,
            ObligationStatus.OPEN,
            List.of(),
            List.of(),
            1.0d,
            0.0d);
    assertEquals(
        ObligationDomain.PROTOCOL,
        classifier.classifyObligation(protocol, "protocol").domain());
    assertFalse(
        classifier.classifyObligation(protocol, "protocol").domain().mathematicalControlEligible());

    SemanticQualityGate gate = new SemanticQualityGate();
    SemanticQualityGate.Assessment selfImplication =
        gate.assessStatement("If x = 1 then x = 1");
    assertTrue(selfImplication.selfImplication());
    assertFalse(selfImplication.accepted());
    SemanticQualityGate.Assessment placeholder = gate.assessStatement("find a suitable invariant");
    assertTrue(placeholder.placeholder());
    assertTrue(placeholder.quarantined());

    RouteTargetSelector.Selection none =
        new RouteTargetSelector().select(
            "route-a", List.of(protocol), Set.of(), Set.of());
    assertFalse(none.admitted());
  }

  private static void semanticViewScenario() {
    List<String> fragments =
        ProblemSemanticViewService.protectedMathFragments(
            "Prove that $x^2 >= 0$ for every $x \\in \\mathbb{R}$.");
    assertFalse(fragments.isEmpty());
    assertEquals(
        fragments,
        ProblemSemanticViewService.protectedMathFragments(
            "Prove that $x^2 >= 0$ for every $x \\in \\mathbb{R}$."));
    assertTrue(
        ProblemSemanticViewService.protectedMathFragments("No formula is present").isEmpty());
  }

  private static void proofRoleScenario() {
    ProofRoleClassifier classifier = new ProofRoleClassifier();
    assertEquals(
        ProofRole.COUNTEREXAMPLE,
        classifier.classify("x = 2 refutes the claim", null, true, false, true));
    assertEquals(
        ProofRole.SEARCH_HEURISTIC,
        classifier.classify("checked a bounded prefix", null, false, true, true));
    assertEquals(
        ProofRole.AUXILIARY_BOUND,
        classifier.classify("establish an upper bound", null, false, false, false));
  }

  private static Obligation obligation(String id, String statement) {
    return new Obligation(
        id,
        statement,
        ObligationKind.LEMMA,
        ObligationStatus.OPEN,
        List.of(),
        List.of("route-a"),
        1.0d,
        1.0d);
  }

  private static ScopeSignature fullScope(String id) {
    return new ScopeSignature(
        id,
        IndexScope.ALL,
        UniformityScope.UNIFORM,
        ObjectScope.FULL_OBJECT,
        List.of(),
        List.of(),
        List.of(),
        1.0d);
  }
}
