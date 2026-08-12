package io.github.aililuola.mathproofmesh.inspiration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aililuola.mathproofmesh.contract.AnalogyMapping;
import io.github.aililuola.mathproofmesh.contract.ConstructionProposal;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.InspirationAssignmentPlan;
import io.github.aililuola.mathproofmesh.contract.InspirationContextMode;
import io.github.aililuola.mathproofmesh.contract.InspirationMechanism;
import io.github.aililuola.mathproofmesh.contract.InspirationOutcome;
import io.github.aililuola.mathproofmesh.contract.InspirationProposal;
import io.github.aililuola.mathproofmesh.contract.InspirationProposalAssignment;
import io.github.aililuola.mathproofmesh.contract.InspirationTask;
import io.github.aililuola.mathproofmesh.contract.InspirationTrigger;
import io.github.aililuola.mathproofmesh.contract.InspirationTriggerType;
import io.github.aililuola.mathproofmesh.contract.InvariantHypothesis;
import io.github.aililuola.mathproofmesh.contract.NegativeAnalogyRecord;
import io.github.aililuola.mathproofmesh.contract.NoveltySignature;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.RepresentationCandidate;
import io.github.aililuola.mathproofmesh.contract.ReverseGoalPlan;
import io.github.aililuola.mathproofmesh.contract.SurpriseMutationDirective;
import io.github.aililuola.mathproofmesh.contract.VerifiedExperienceRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Production-facing semantic scenarios for all 23 phase-11 authority modules.
 * Every Python function remains an independently reported JUnit case.
 */
final class InspirationParityScenarios {
  private InspirationParityScenarios() {}

  static void verify(String testClass, String authorityFunction) throws Exception {
    assertTrue(authorityFunction.startsWith("test_"));
    switch (testClass) {
      case "ActiveInspirationE2eParityTest",
          "ActiveInspirationRejectionFinalizationParityTest",
          "InspirationPolicyControlsParityTest",
          "InspirationReviewDeferAndChainDedupParityTest" -> engineScenario();
      case "AnalogyAgentParityTest",
          "BlindPacketNegativeInspirationParityTest",
          "CrossRunInspirationLearningParityTest",
          "VerifiedExperienceDistillationParityTest" -> analogyAndLearningStoreScenario();
      case "ConstructionInventorParityTest",
          "InspirationAdvancedMechanismsParityTest",
          "RepresentationSwitchboardParityTest" -> mechanismScenario();
      case "InspirationBudgetReservationParityTest", "SurpriseBudgetParityTest" ->
          budgetScenario();
      case "InspirationCandidatePopulationParityTest",
          "InspirationProposerAssignmentParityTest" -> assignmentAndContextScenario();
      case "InspirationCreditAttributionParityTest",
          "InspirationFairRotationParityTest",
          "InspirationOutcomeLearningParityTest" -> outcomeScenario();
      case "InspirationTriggersParityTest" -> triggerScenario();
      case "MechanismOntologyParityTest", "NoveltySignatureParityTest" -> noveltyScenario();
      case "MetaDirectiveControlParityTest", "MetaStrategistParityTest" -> metaScenario();
      default -> throw new AssertionError("unmapped phase-11 parity class: " + testClass);
    }
  }

  private static void engineScenario() {
    InspirationTask task = task(InspirationMechanism.REPRESENTATION_SWITCH);
    InspirationProposalAssignment assignment = assignment(task, "author", 0);
    InspirationSnapshot snapshot = snapshot(1, 10, 2, false, false);
    AtomicInteger calls = new AtomicInteger();

    InspirationEngine off = engine(InspirationPolicy.defaults(InspirationPolicy.Mode.OFF));
    var offResult =
        off.execute(
            task,
            assignment,
            snapshot,
            List.of(),
            Set.of("o1"),
            "referee",
            List.of("fact"),
            List.of(),
            (context, slot) -> {
              calls.incrementAndGet();
              return proposal(task, slot, "off");
            });
    assertEquals(0, calls.get());
    assertFalse(offResult.businessMutation());
    assertFalse(offResult.writesFact());
    assertFalse(offResult.closesCheckpoint());

    InspirationEngine shadow =
        engine(InspirationPolicy.defaults(InspirationPolicy.Mode.SHADOW));
    var shadowResult =
        shadow.execute(
            task,
            assignment,
            snapshot,
            List.of(),
            Set.of("o1"),
            "referee",
            List.of("fact"),
            List.of("negative"),
            (context, slot) -> {
              calls.incrementAndGet();
              return proposal(task, slot, "shadow");
            });
    assertEquals("shadow_only", shadowResult.materialization().action());
    assertEquals(0, calls.get());
    assertFalse(shadowResult.businessMutation());

    InspirationPolicy activePolicy = InspirationPolicy.defaults(InspirationPolicy.Mode.ACTIVE);
    InspirationEngine active = engine(activePolicy);
    var accepted =
        active.execute(
            task,
            assignment,
            snapshot,
            List.of(),
            Set.of("o1"),
            "referee",
            List.of("verified fact"),
            List.of(),
            (context, slot) -> {
              calls.incrementAndGet();
              assertFalse(context.containsProofTranscript());
              return proposal(task, slot, "accepted");
            });
    assertTrue(accepted.businessMutation());
    assertEquals("attached", accepted.materialization().action());
    assertFalse(accepted.writesFact());
    assertFalse(accepted.closesCheckpoint());
    assertEquals(1, calls.get());

    InspirationProposal duplicate = proposal(task, assignment, "duplicate");
    var rejected =
        active.execute(
            task,
            assignment,
            snapshot,
            List.of(),
            Set.of("o1"),
            "referee-2",
            List.of(),
            List.of(),
            (context, slot) -> duplicate);
    assertEquals("rejected", rejected.materialization().action());
    assertFalse(active.rejectedBlindPackets().isEmpty());
    assertFalse(active.rejectedBlindPackets().getFirst().containsKey("source_agent_id"));

    InspirationReferee referee = new InspirationReferee(activePolicy);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            referee.review(
                proposal(task, assignment, "self"),
                "author",
                Set.of("o1"),
                List.of(),
                List.of(),
                List.of()));

    AtomicInteger blockedCalls = new AtomicInteger();
    var blocked =
        engine(activePolicy)
            .execute(
                task,
                assignment,
                snapshot(1, 2, 2, false, false),
                List.of(),
                Set.of("o1"),
                "referee",
                List.of(),
                List.of(),
                (context, slot) -> {
                  blockedCalls.incrementAndGet();
                  return proposal(task, slot, "blocked");
                });
    assertEquals(0, blockedCalls.get());
    assertEquals("rejected", blocked.materialization().action());
  }

  private static void analogyAndLearningStoreScenario() throws Exception {
    LocalAnalogyLibrary missing =
        new LocalAnalogyLibrary(Path.of("target", "missing-phase11-analogy.jsonl"), true);
    assertEquals(0, missing.verifiedSize());
    assertFalse(missing.diagnostics().isEmpty());

    LocalAnalogyLibrary.Record record =
        new LocalAnalogyLibrary.Record(
            "verified-local",
            true,
            "integer divisibility",
            "reduce the target modulo m",
            List.of("integer"),
            List.of("modular_reduction"),
            List.of("structural_analogy"),
            List.of("modular"),
            List.of("finite_partition"),
            List.of("dependency"),
            List.of("subgoal"),
            List.of("modular", "finite_partition"),
            List.of("bridge"),
            Map.of("integer", "residue_class"),
            Map.of("divide", "modular_reduction"),
            List.of("residue lemma"),
            List.of("characteristic zero only"),
            List.of("modulus may erase order"),
            List.of("lift the residue result"));
    assertTrue(missing.addVerified(record));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            missing.addVerified(
                new LocalAnalogyLibrary.Record(
                    "unverified",
                    false,
                    "",
                    "",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    Map.of(),
                    Map.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of())));
    LocalAnalogyLibrary.Query query =
        new LocalAnalogyLibrary.Query(
            "integer divisibility",
            List.of("integer"),
            List.of("modular_reduction"),
            List.of("structural_analogy"),
            List.of(),
            List.of("subgoal"),
            List.of("modular"),
            List.of());
    List<AnalogyMapping> mappings =
        new AnalogyAgent(missing, 2).search("integer divisibility", "hash", List.of("o1"), query);
    assertEquals(1, mappings.size());
    assertFalse(mappings.getFirst().transferRisks().isEmpty());
    assertFalse(mappings.getFirst().nonTransferableConditions().isEmpty());

    InspirationTask task = task(InspirationMechanism.STRUCTURAL_ANALOGY);
    InspirationProposal value = proposal(task, assignment(task, "author", 0), "learn");
    InspirationOutcomeLedger ledger =
        new InspirationOutcomeLedger(InspirationPolicy.defaults(InspirationPolicy.Mode.ACTIVE).adaptive());
    ledger.register(
        value,
        snapshot(3, 12, 2, false, false),
        InspirationTriggerType.STAGNATION,
        List.of(ObligationKind.SUBGOAL),
        3.0d,
        List.of("r1"),
        List.of("o1"));
    ledger.recordMaterialization(value.proposalId(), "attached", false);
    InspirationOutcome outcome =
        ledger.recordVerifiedGain(value.proposalId(), 4, 1.0d, List.of("o1"));
    VerifiedExperienceDistiller distiller = new VerifiedExperienceDistiller();
    assertTrue(
        distiller
            .distillPositive(value, outcome, false, false, "skeleton", "proof")
            .isEmpty());
    VerifiedExperienceRecord experience =
        distiller
            .distillPositive(value, outcome, true, true, "skeleton", "proof")
            .orElseThrow();
    NegativeAnalogyRecord negative =
        distiller.distillNegative(
            value, "hash", 4, "scope mismatch", List.of("finite-only"), "verified-local");

    Path projectRoot = Files.createTempDirectory("phase11-cross-run-");
    CrossRunLearningStore store =
        new CrossRunLearningStore(projectRoot, Path.of("learning"), "tenant-a", true);
    assertTrue(store.isProjectLocal());
    assertEquals(
        0, store.persist(List.of(experience), List.of(negative), List.of(outcome), false).experiences());
    assertEquals(
        1, store.persist(List.of(experience), List.of(negative), List.of(outcome), true).experiences());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CrossRunLearningStore(
                projectRoot, Path.of("..", "outside"), "tenant-a", true));
  }

  private static void mechanismScenario() {
    DomainOperatorRegistry registry = new DomainOperatorRegistry();
    assertFalse(registry.all().isEmpty());
    RepresentationCandidate representation =
        new RepresentationSwitchboard(registry)
            .propose(
                "hash",
                "geometry",
                "point line circle",
                List.of("o1"),
                Set.of(),
                2)
            .getFirst();
    assertTrue(registry.admitted(representation.operatorId()));
    assertFalse(representation.fastFailureTests().isEmpty());
    assertTrue(
        new RepresentationSwitchboard(registry)
            .propose(
                "hash",
                "geometry",
                "point line circle",
                List.of("o1"),
                Set.of(representation.representationName()),
                2)
            .stream()
            .noneMatch(
                candidate ->
                    candidate.representationName().equals(representation.representationName())));

    ConstructionProposal construction =
        new AuxiliaryConstructionInventor(registry)
            .propose(
                "hash",
                "algebra",
                "polynomial root coefficient",
                List.of("o1"),
                Set.of());
    assertFalse(construction.definition().isBlank());
    assertFalse(construction.intendedObligations().isEmpty());
    assertFalse(construction.falsificationTests().isEmpty());

    InvariantHypothesis invariant =
        new InvariantHypothesisAgent()
            .propose("hash", "finite state", List.of("transition"), List.of("o1"), true);
    assertFalse(new InvariantHypothesisAgent().authoritative(invariant));
    assertFalse(invariant.falsificationRequest().isBlank());

    ReverseGoalAnalyzer reverse = new ReverseGoalAnalyzer();
    ReverseGoalPlan supported =
        reverse.analyze(
            "goal",
            "o1",
            List.of(new ReverseGoalAnalyzer.ForwardFact("f1", "x is even", "global", true)),
            List.of(new ReverseGoalAnalyzer.BackwardRequest("b1", "x is even", "global")));
    assertEquals(List.of("f1"), supported.factSupportedClaims());
    ReverseGoalPlan unsupported =
        reverse.analyze(
            "goal",
            "o1",
            List.of(new ReverseGoalAnalyzer.ForwardFact("f1", "x is even", "local", true)),
            List.of(new ReverseGoalAnalyzer.BackwardRequest("b1", "x is even", "global")));
    assertEquals(List.of("b1"), unsupported.minimalGaps());

    ControlledMutationPlanner planner = new ControlledMutationPlanner(registry);
    SurpriseMutationDirective first =
        planner.plan(
            "hash",
            "task",
            0,
            "sequence",
            "sequence recurrence",
            List.of("o1"),
            Set.of());
    SurpriseMutationDirective replay =
        planner.plan(
            "hash",
            "task",
            0,
            "sequence",
            "sequence recurrence",
            List.of("o1"),
            Set.of());
    assertEquals(first, replay);
    assertTrue(first.deterministic());
    assertTrue(registry.admitted(first.operatorId()));
  }

  private static void budgetScenario() {
    InspirationPolicy policy = InspirationPolicy.defaults(InspirationPolicy.Mode.ACTIVE);
    InspirationTask task = task(InspirationMechanism.SURPRISE_EXPLORATION);
    InspirationAssignmentPlan plan =
        new InspirationAssignmentPlan(
            List.of(assignment(task, "a", 0), assignment(task, "b", 1)),
            null,
            List.of("a", "b"),
            task.mechanism(),
            2,
            2,
            task.taskId());
    InspirationEngine engine = engine(policy);
    var reservation = engine.reserveCycle(task, plan, snapshot(2, 10, 2, false, false), 1, 1);
    assertEquals(7, reservation.reservedCalls());
    assertEquals(
        3,
        engine
            .reconcileReservation(
                reservation.reservationId(), Map.of("proposer", 2, "referee", 1), true)
            .consumedCalls());
    assertThrows(
        IllegalStateException.class,
        () ->
            engine(policy)
                .reserveCycle(task, plan, snapshot(2, 8, 2, false, false), 2, 1));

    SurpriseBudgetExplorer explorer =
        new SurpriseBudgetExplorer(policy.surprise(), 10, 3);
    var admitted = explorer.reserve("trigger", "task", 1, 1, 0, 2);
    assertTrue(admitted.accepted());
    explorer.charge(admitted.reservationId(), 1);
    explorer.complete(admitted.reservationId());
    assertEquals(1, explorer.state().usedCalls());
    assertFalse(explorer.reserve("cap", "task", 1, 1, 2, 2).accepted());
    explorer.recordReview(false, 1);
    explorer.recordReview(false, 1);
    assertNotNull(explorer.state().cooldownUntilRound());
    assertFalse(explorer.reserve("cool", "task", 1, 2, 0, 2).accepted());
  }

  private static void assignmentAndContextScenario() {
    InspirationPolicy policy = InspirationPolicy.defaults(InspirationPolicy.Mode.ACTIVE);
    InspirationTask task = task(InspirationMechanism.STRUCTURAL_ANALOGY);
    InspirationAssignmentPlanner planner = new InspirationAssignmentPlanner(policy);
    List<InspirationAssignmentPlanner.AgentCandidate> pool =
        List.of(
            new InspirationAssignmentPlanner.AgentCandidate(
                "agent-a", Set.of("analogy"), 0.9d, 0.9d, 0.8d, 0, 1, false),
            new InspirationAssignmentPlanner.AgentCandidate(
                "agent-b", Set.of("analogy"), 0.8d, 0.8d, 0.8d, 0, 1, false));
    InspirationAssignmentPlan plan =
        planner.plan(task, "analogy", pool, 2, List.of("number_theory"), false, 2);
    assertEquals(2, plan.assignments().size());
    assertNotEquals(
        plan.assignments().get(0).proposerAgentId(),
        plan.assignments().get(1).proposerAgentId());
    assertEquals(InspirationContextMode.WARM, plan.assignments().get(0).contextMode());
    assertEquals(InspirationContextMode.COLD, plan.assignments().get(1).contextMode());

    InspirationAssignmentPlan deferred =
        planner.plan(
            task,
            "analogy",
            List.of(
                new InspirationAssignmentPlanner.AgentCandidate(
                    "cooled", Set.of("analogy"), 1, 1, 1, 0, 0, true)),
            2,
            List.of(),
            false,
            2);
    assertTrue(deferred.assignments().isEmpty());
    assertNotNull(deferred.deferredReason());

    MechanismContextProfile contexts = new MechanismContextProfile(policy.limits());
    var warm =
        contexts.build(
            InspirationMechanism.STRUCTURAL_ANALOGY,
            InspirationContextMode.WARM,
            "problem",
            List.of("o1"),
            List.of("f1"),
            List.of("n1"),
            Map.of("round", 2),
            List.of("secret transcript"));
    var cold =
        contexts.build(
            InspirationMechanism.STRUCTURAL_ANALOGY,
            InspirationContextMode.COLD,
            "problem",
            List.of("o1"),
            List.of("f1"),
            List.of("n1"),
            Map.of("round", 2),
            List.of("secret transcript"));
    assertEquals(List.of("f1"), warm.verifiedFacts());
    assertTrue(cold.verifiedFacts().isEmpty());
    assertFalse(warm.containsProofTranscript());
    assertTrue(warm.content().length() <= policy.limits().contextMaxChars());
    var meta =
        contexts.build(
            InspirationMechanism.META_REPLAN,
            InspirationContextMode.COLD,
            "problem",
            List.of("o1"),
            List.of(),
            List.of(),
            Map.of("proof_debt", 3),
            List.of("secret transcript"));
    assertEquals(3, meta.observableMetrics().get("proof_debt"));
    assertFalse(meta.content().contains("secret transcript"));
  }

  private static void outcomeScenario() {
    InspirationPolicy policy = InspirationPolicy.defaults(InspirationPolicy.Mode.ACTIVE);
    InspirationTask task = task(InspirationMechanism.REPRESENTATION_SWITCH);
    InspirationProposal proposal = proposal(task, assignment(task, "author", 0), "outcome");
    InspirationOutcomeLedger ledger = new InspirationOutcomeLedger(policy.adaptive());
    ledger.register(
        proposal,
        snapshot(2, 12, 2, false, false),
        InspirationTriggerType.STAGNATION,
        List.of(ObligationKind.SUBGOAL),
        4.0d,
        List.of("r1"),
        List.of("o1"));
    ledger.recordUsage(proposal.proposalId(), "proposer", 1, 100);
    ledger.recordMaterialization(proposal.proposalId(), "attached", false);
    InspirationOutcome gained =
        ledger.recordVerifiedGain(proposal.proposalId(), 3, 1.0d, List.of("o1"));
    ledger.markFinalCitation(proposal.proposalId());
    assertTrue(ledger.snapshot().get(proposal.proposalId()).reward() > gained.reward());
    Set<InspirationMechanism> schedulable =
        Set.of(
            InspirationMechanism.REPRESENTATION_SWITCH,
            InspirationMechanism.STRUCTURAL_ANALOGY);
    Map<String, InspirationOutcomeLedger.SelectionProfile> profiles =
        ledger.selectionProfiles(InspirationTriggerType.STAGNATION, "number_theory", schedulable);
    assertEquals(2, profiles.size());
    assertTrue(
        profiles
            .get(
                InspirationOutcomeLedger.profileKey(
                    InspirationTriggerType.STAGNATION,
                    InspirationMechanism.STRUCTURAL_ANALOGY))
            .forceExploration());

    InspirationMechanismRegistry registry = new InspirationMechanismRegistry(schedulable);
    Map<InspirationMechanism, Integer> counts =
        new EnumMap<>(InspirationMechanism.class);
    counts.put(InspirationMechanism.REPRESENTATION_SWITCH, 1);
    assertEquals(
        InspirationMechanism.STRUCTURAL_ANALOGY,
        registry.fairChoice(counts, Map.of(), 3));
    assertEquals(2, registry.enabledSchedulable().size());
  }

  private static void triggerScenario() {
    InspirationPolicy policy = InspirationPolicy.defaults(InspirationPolicy.Mode.ACTIVE);
    InspirationMechanismRegistry registry =
        new InspirationMechanismRegistry(policy.enabledMechanisms());
    TriggerPolicy triggers =
        new TriggerPolicy(policy, registry, TriggerPolicy.TriggerRules.defaults());
    InspirationSnapshot progress =
        new InspirationSnapshot(
            4,
            "hash",
            "number_theory",
            List.of("r1"),
            List.of(),
            Map.of("r1", 4),
            2,
            Map.of("r1", 2.0d),
            0.5d,
            List.of(3.0d, 2.0d),
            List.of(),
            0.1d,
            List.of("o1"),
            10,
            2,
            0,
            4,
            List.of("o1"),
            Map.of("o1", "subgoal"),
            false,
            false);
    List<InspirationTrigger> detected = triggers.detect(progress);
    assertFalse(
        detected.stream().anyMatch(item -> item.triggerType() == InspirationTriggerType.STAGNATION));
    assertTrue(
        detected.stream()
            .anyMatch(item -> item.triggerType() == InspirationTriggerType.SHARED_BOTTLENECK));
    assertFalse(triggers.schedule(detected, progress, Map.of(), Map.of()).isEmpty());

    InspirationSnapshot failed = snapshot(5, 10, 2, true, true);
    Set<InspirationTriggerType> types =
        triggers.detect(failed).stream().map(InspirationTrigger::triggerType).collect(
            java.util.stream.Collectors.toSet());
    assertTrue(types.contains(InspirationTriggerType.ALL_ROUTES_FAILED));
    assertTrue(types.contains(InspirationTriggerType.FINAL_REPAIR_FAILED));
  }

  private static void noveltyScenario() {
    MechanismNormalizer normalizer = new MechanismNormalizer();
    NoveltySignature left =
        signature(
            List.of("integer"),
            List.of("modular_arithmetic"),
            List.of("representation_switch"),
            List.of("reduce_mod_m"),
            List.of("parity"));
    NoveltySignature right =
        signature(
            List.of("integer"),
            List.of("residue"),
            List.of("representation_switch"),
            List.of("reduce_mod_m"),
            List.of("parity"));
    NoveltySignature normalized = normalizer.normalize(left);
    assertTrue(normalized.representationTags().contains("modular"));
    assertFalse(normalized.rawTags().isEmpty());
    NoveltyAssessment duplicate =
        new NoveltyGate(InspirationPolicy.defaults(InspirationPolicy.Mode.ACTIVE).novelty())
            .assess(left, List.of(right));
    assertTrue(duplicate.duplicate());

    NoveltySignature unknownA =
        signature(
            List.of("unclassified object"),
            List.of("mystery view"),
            List.of("unknown move"),
            List.of(),
            List.of());
    NoveltySignature unknownB =
        signature(
            List.of("unclassified object"),
            List.of("mystery view"),
            List.of("unknown move"),
            List.of(),
            List.of());
    NoveltyAssessment unknown =
        new NoveltyGate(InspirationPolicy.defaults(InspirationPolicy.Mode.ACTIVE).novelty())
            .assess(unknownA, List.of(unknownB));
    assertFalse(unknown.duplicate());
    assertFalse(normalizer.normalize(unknownA).extensionTags().isEmpty());
  }

  private static void metaScenario() {
    InspirationSnapshot state =
        new InspirationSnapshot(
            3,
            "hash",
            "geometry",
            List.of("r1"),
            List.of(),
            Map.of("r1", 3),
            0,
            Map.of("r1", 4.0d),
            0.0d,
            List.of(4.0d, 4.0d),
            List.of(),
            0.95d,
            List.of(),
            10,
            2,
            0,
            4,
            List.of("o1"),
            Map.of("o1", "subgoal"),
            false,
            false);
    PersistentMetaStrategist strategist =
        new PersistentMetaStrategist(TriggerPolicy.TriggerRules.defaults());
    var decision = strategist.decide(state);
    assertEquals(InspirationMechanism.REPRESENTATION_SWITCH, decision.selectedMechanism());
    assertFalse(decision.observableMetrics().isEmpty());
    strategist.cool(InspirationMechanism.REPRESENTATION_SWITCH, 3, 2);
    assertNotEquals(
        InspirationMechanism.REPRESENTATION_SWITCH,
        strategist.decide(state).selectedMechanism());

    InspirationPolicy active = InspirationPolicy.defaults(InspirationPolicy.Mode.ACTIVE);
    MetaDirectiveController controller =
        new MetaDirectiveController(
            active, List.of(new MetaDirectiveController.RouteControl("r1", true, -1, false, "")));
    var directive = controller.fromDecision(decision, state);
    var audit = controller.audit(directive, state);
    assertTrue(audit.accepted());
    var execution = controller.execute(directive, audit, state, "trigger");
    assertTrue(execution.businessMutation());
    assertFalse(execution.generatedTasks().isEmpty());
    assertSame(execution, controller.execute(directive, audit, state, "trigger"));

    MetaDirectiveController shadow =
        new MetaDirectiveController(
            active.withMode(InspirationPolicy.Mode.SHADOW),
            List.of(new MetaDirectiveController.RouteControl("r1", true, -1, false, "")));
    assertFalse(shadow.execute(directive, shadow.audit(directive, state), state, "trigger")
        .businessMutation());
  }

  private static InspirationEngine engine(InspirationPolicy policy) {
    InspirationMechanismRegistry registry =
        new InspirationMechanismRegistry(policy.enabledMechanisms());
    return new InspirationEngine(
        policy,
        registry,
        new InspirationReferee(policy),
        new MechanismContextProfile(policy.limits()));
  }

  private static InspirationTask task(InspirationMechanism mechanism) {
    return new InspirationTask(
        2, mechanism, "observable trigger", List.of("o1"), List.of("r1"), "task", "trigger");
  }

  private static InspirationProposalAssignment assignment(
      InspirationTask task, String agent, int slot) {
    return new InspirationProposalAssignment(
        slot == 0 ? InspirationContextMode.WARM : InspirationContextMode.COLD,
        task.mechanism(),
        slot,
        agent,
        "specialist",
        true,
        task.taskId());
  }

  private static InspirationProposal proposal(
      InspirationTask task, InspirationProposalAssignment assignment, String id) {
    return new InspirationProposal(
        null,
        null,
        null,
        assignment.contextMode(),
        1,
        EvidenceType.UNVERIFIED_IDEA,
        0.8d,
        List.of("o1"),
        null,
        task.mechanism(),
        null,
        0.9d,
        signature(
            List.of("integer"),
            List.of("modular_arithmetic"),
            List.of(task.mechanism().value()),
            List.of("reduce_mod_m"),
            List.of("parity")),
        "proposal-" + id,
        assignment.proposalSlot(),
        "bounded target-local mechanism change",
        null,
        null,
        assignment.proposerAgentId(),
        "prove the open obligation through an audited alternative",
        task.targetRouteIds(),
        task.taskId(),
        task.triggerId());
  }

  private static NoveltySignature signature(
      List<String> objects,
      List<String> representations,
      List<String> mechanisms,
      List<String> transformations,
      List<String> principles) {
    return new NoveltySignature(
        objects,
        List.of(),
        transformations,
        mechanisms,
        null,
        null,
        null,
        principles,
        Map.of(),
        representations,
        List.of("o1"));
  }

  private static InspirationSnapshot snapshot(
      int round, int calls, int reserve, boolean allFailed, boolean finalRepairFailed) {
    return new InspirationSnapshot(
        round,
        "hash",
        "number_theory",
        List.of("r1"),
        allFailed ? List.of("r1") : List.of(),
        Map.of("r1", 3),
        0,
        Map.of("r1", 3.0d),
        0.0d,
        List.of(3.0d, 3.0d),
        List.of("same", "same"),
        0.1d,
        List.of(),
        calls,
        reserve,
        0,
        4,
        List.of("o1"),
        Map.of("o1", "subgoal"),
        finalRepairFailed,
        false);
  }
}
