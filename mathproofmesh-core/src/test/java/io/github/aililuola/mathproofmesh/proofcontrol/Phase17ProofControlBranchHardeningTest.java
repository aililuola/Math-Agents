package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.proofcontrol.ExecutableTaskController.TaskKind;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.DependencyKind;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.DependencyRef;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.IndexScope;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.ObjectScope;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.PropertyStrength;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.RelationSignature;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.ScopeSignature;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.SetRelationKind;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.TaskStatus;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.UniformityScope;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.WakeCondition;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.WakeConditionKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Phase17ProofControlBranchHardeningTest {

  @Test
  void executableTasksExerciseEveryCreationWakeAndTerminalBranch() {
    ExecutableTaskController controller = new ExecutableTaskController();
    var deferred =
        controller.create(
            TaskKind.COUNTERMODEL,
            null,
            List.of(" obligation ", "", "obligation"),
            List.of("route", "route"),
            " ",
            null,
            null,
            false,
            1);
    assertThat(deferred.status()).isEqualTo(TaskStatus.DEFERRED);
    assertThat(deferred.executable()).isFalse();
    assertThat(deferred.terminal()).isFalse();
    assertThat(
            controller.create(
                TaskKind.COUNTERMODEL,
                null,
                List.of("obligation"),
                List.of("route"),
                null,
                null,
                null,
                false,
                9))
        .isEqualTo(deferred);
    assertThatThrownBy(() -> controller.markRunning(deferred.id(), 2))
        .isInstanceOf(IllegalStateException.class);

    assertThat(controller.evaluateWakeConditions(WakeConditionKind.PROVIDER_AVAILABLE, "wrong", 0))
        .isEmpty();
    assertThat(controller.evaluateWakeConditions(WakeConditionKind.PROVIDER_AVAILABLE, "countermodel", 1))
        .singleElement()
        .satisfies(task -> assertThat(task.status()).isEqualTo(TaskStatus.CREATED));
    assertThat(controller.lifecycleClosed()).isFalse();

    var rewrite =
        controller.create(
            TaskKind.FALSIFICATION,
            List.of("claim"),
            List.of(),
            List.of(),
            "handler",
            null,
            "contract",
            true,
            2);
    assertThat(rewrite.status()).isEqualTo(TaskStatus.NEEDS_REWRITE);
    assertThat(controller.evaluateWakeConditions(WakeConditionKind.PROVIDER_AVAILABLE, "contract", 2))
        .isEmpty();
    assertThat(controller.evaluateWakeConditions(WakeConditionKind.TASK_RECOMPILED, "contract", 2))
        .singleElement()
        .satisfies(task -> assertThat(task.status()).isEqualTo(TaskStatus.READY));

    var assigned =
        controller.create(
            TaskKind.ROUTE_UPDATE,
            List.of("claim"),
            List.of(),
            List.of(),
            null,
            " agent ",
            "contract-2",
            false,
            2);
    assertThat(assigned.status()).isEqualTo(TaskStatus.ASSIGNED);
    var emptyTarget =
        new WakeCondition("wake-manual", WakeConditionKind.ROUND_ADVANCED, "", 5, false);
    assertThat(controller.defer(assigned.id(), emptyTarget, " wait ", 3).status())
        .isEqualTo(TaskStatus.DEFERRED);
    assertThat(controller.evaluateWakeConditions(WakeConditionKind.ROUND_ADVANCED, "anything", 4))
        .isEmpty();
    assertThat(controller.evaluateWakeConditions(WakeConditionKind.ROUND_ADVANCED, "anything", 5))
        .singleElement()
        .satisfies(task -> assertThat(task.status()).isEqualTo(TaskStatus.ASSIGNED));

    var ready =
        controller.create(
            TaskKind.INSPIRATION_REVIEW,
            List.of("c2"),
            List.of(),
            List.of(),
            "handler",
            null,
            "contract-3",
            false,
            1);
    assertThat(ready.status()).isEqualTo(TaskStatus.READY);
    var running = controller.markRunning(ready.id(), 2);
    assertThat(controller.markRunning(ready.id(), 3)).isEqualTo(running);
    var completed = controller.complete(ready.id(), List.of(" result ", "", "result"), true, 4);
    assertThat(completed.status()).isEqualTo(TaskStatus.COMPLETED);
    assertThat(completed.terminal()).isTrue();
    assertThat(controller.complete(ready.id(), List.of("ignored"), false, 5)).isEqualTo(completed);
    assertThat(controller.defer(ready.id(), emptyTarget, "ignored", 6)).isEqualTo(completed);

    var readyTwo =
        controller.create(
            TaskKind.META_PIVOT_STEP,
            List.of("c3"),
            List.of(),
            List.of(),
            "handler",
            null,
            "contract-4",
            false,
            1);
    assertThatThrownBy(() -> controller.complete(readyTwo.id(), List.of(), false, 2))
        .isInstanceOf(IllegalStateException.class);
    controller.markRunning(readyTwo.id(), 2);
    assertThat(controller.complete(readyTwo.id(), null, false, 3).status())
        .isEqualTo(TaskStatus.INCONCLUSIVE);
    assertThat(controller.snapshots()).isSortedAccordingTo(
        java.util.Comparator.comparing(ExecutableTaskController.Snapshot::id));
    assertThat(controller.get(ready.id())).isEqualTo(completed);
    assertThatThrownBy(() -> controller.get("unknown"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void dependencyResolverExercisesEveryNamespaceAndResolutionBucket() {
    DependencyResolver resolver = new DependencyResolver();
    List<String> canonical =
        List.of(
            "step:s",
            "local-step:s",
            "claim:c",
            "local_claim:c",
            "fact:f",
            "global_fact:f",
            "message:m",
            "obligation:o",
            "tool_certificate:t",
            "formal-certificate:q",
            "external_result:e");
    assertThat(canonical.stream().map(resolver::parseCanonical).map(DependencyRef::kind))
        .contains(
            DependencyKind.LOCAL_STEP,
            DependencyKind.LOCAL_CLAIM,
            DependencyKind.GLOBAL_FACT,
            DependencyKind.MESSAGE,
            DependencyKind.OBLIGATION,
            DependencyKind.TOOL_CERTIFICATE,
            DependencyKind.FORMAL_CERTIFICATE,
            DependencyKind.EXTERNAL_RESULT);
    for (String invalid : List.of("x", ":x", "x:", "external:x", "unknown:x")) {
      assertThatThrownBy(() -> resolver.parseCanonical(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }

    assertThat(
            resolver.migrateLegacy(
                null, null, null, null, null, null, null))
        .satisfies(
            result -> {
              assertThat(result.status()).isEqualTo("complete");
              assertThat(result.task()).isNull();
              assertThat(result.refs()).isEmpty();
              assertThat(result.invalidatesClaim()).isFalse();
            });
    var migrated =
        resolver.migrateLegacy(
            List.of(
                "message:m",
                "s",
                "c",
                "f",
                "ambiguous",
                "message:m"),
            null,
            "delta",
            "route",
            Set.of("s"),
            Set.of("c"),
            Set.of("f"));
    assertThat(migrated.status()).isEqualTo("ambiguous");
    assertThat(migrated.task()).isNotNull();
    assertThat(migrated.task().ambiguousIds()).containsExactly("ambiguous");
    assertThat(migrated.refs()).hasSize(4);

    DependencyResolver.ResolutionContext context =
        new DependencyResolver.ResolutionContext(
            "attempt",
            "delta",
            Set.of("s"),
            Set.of("c"),
            Set.of("f"),
            Set.of("m"),
            Set.of("o"),
            Set.of("t", "q"),
            Set.of("e"),
            Set.of("bad"));
    List<DependencyRef> all = new ArrayList<>();
    all.add(ref(DependencyKind.LOCAL_STEP, "s", null, null, null));
    all.add(ref(DependencyKind.LOCAL_CLAIM, "c", "attempt", null, null));
    all.add(ref(DependencyKind.GLOBAL_FACT, "f", null, null, null));
    all.add(ref(DependencyKind.MESSAGE, "m", null, null, null));
    all.add(ref(DependencyKind.OBLIGATION, "o", null, null, null));
    all.add(ref(DependencyKind.TOOL_CERTIFICATE, "t", null, null, null));
    all.add(ref(DependencyKind.FORMAL_CERTIFICATE, "q", null, null, null));
    all.add(ref(DependencyKind.EXTERNAL_RESULT, "e", null, null, null));
    assertThat(resolver.resolve(all, context).resolved()).isTrue();

    all.add(ref(DependencyKind.LOCAL_STEP, "s", null, "other", null));
    all.add(ref(DependencyKind.LOCAL_CLAIM, "c", "other", null, null));
    all.add(ref(DependencyKind.MESSAGE, "missing", null, null, null));
    all.add(ref(DependencyKind.MESSAGE, "bad", null, null, null));
    all.add(ref(DependencyKind.MESSAGE, "m", null, null, "ambiguous legacy"));
    var resolution = resolver.resolve(all, context);
    assertThat(resolution.resolved()).isFalse();
    assertThat(resolution.resolvedRefs()).hasSize(8);
    assertThat(resolution.missingRefs()).hasSize(3);
    assertThat(resolution.invalidRefs()).hasSize(1);
    assertThat(resolution.ambiguousRefs()).hasSize(1);

    DependencyResolver.ResolutionContext empty =
        new DependencyResolver.ResolutionContext(
            null, null, null, null, null, null, null, null, null, null);
    assertThat(empty.attemptId()).isEmpty();
    assertThat(empty.localStepIds()).isEmpty();
  }

  @Test
  void inferenceScannerExercisesEveryTaxonomyAndStrengtheningBranch() {
    InferenceRiskScanner scanner = new InferenceRiskScanner();
    String everyMarker =
        String.join(
            "; ",
            "necessary therefore sufficient",
            "eventually therefore all",
            "pointwise therefore uniform",
            "finite range therefore finite state",
            "image is contained",
            "equal projections imply equal",
            "locally therefore globally",
            "for each there exists therefore there exists one",
            "pairwise therefore common witness",
            "checked cases therefore all",
            "nonempty intersection therefore subset",
            "one component therefore every component",
            "partial property therefore total",
            "covers therefore exhaustive",
            "there exists x for all y",
            "therefore assume missing dependency");
    assertThat(scanner.scanText("all", everyMarker))
        .extracting(ProofControlModels.InferenceRisk::type)
        .hasSize(16);
    assertThat(scanner.scanText("none", "a valid direct argument")).isEmpty();

    ScopeGuard guard = new ScopeGuard();
    ScopeSignature all = scope("all", IndexScope.ALL, UniformityScope.UNIFORM);
    assertThat(scanner.scanScope("same", all, all, guard)).isEmpty();
    assertThat(
            scanner.scanScope(
                "stronger",
                all,
                scope("single", IndexScope.SINGLE_INSTANCE, UniformityScope.UNIFORM),
                guard))
        .isEmpty();
    assertThat(
            scanner.scanScope(
                "eventual",
                scope("eventual", IndexScope.EVENTUAL, UniformityScope.UNIFORM),
                all,
                guard))
        .extracting(ProofControlModels.InferenceRisk::type)
        .containsExactly(ProofControlModels.InferenceRiskType.EVENTUAL_TO_GLOBAL);
    assertThat(
            scanner.scanScope(
                "pointwise",
                scope("point", IndexScope.ALL, UniformityScope.POINTWISE),
                all,
                guard))
        .extracting(ProofControlModels.InferenceRisk::type)
        .containsExactly(ProofControlModels.InferenceRiskType.POINTWISE_TO_UNIFORM);
    assertThat(
            scanner.scanScope(
                "generic",
                scope("projection", IndexScope.ALL, UniformityScope.UNIFORM, ObjectScope.PROJECTION),
                all,
                guard))
        .extracting(ProofControlModels.InferenceRisk::type)
        .containsExactly(ProofControlModels.InferenceRiskType.SCOPE_MISMATCH);

    RelationSignature weak =
        new RelationSignature(
            SetRelationKind.NONEMPTY_INTERSECTION, PropertyStrength.EXISTENTIAL, "component");
    RelationSignature strong =
        new RelationSignature(SetRelationKind.SUBSET, PropertyStrength.UNIVERSAL, "coverage");
    assertThat(scanner.scanRelationStrengthening("relation", weak, strong)).hasSize(2);
    assertThat(
            scanner.scanRelationStrengthening(
                "some",
                new RelationSignature(
                    SetRelationKind.EQUALITY, PropertyStrength.EXISTENTIAL, "witness"),
                new RelationSignature(
                    SetRelationKind.EQUALITY, PropertyStrength.UNIVERSAL, "witness")))
        .extracting(ProofControlModels.InferenceRisk::type)
        .containsExactly(ProofControlModels.InferenceRiskType.SOME_WITNESS_TO_ALL_WITNESSES);
    assertThat(
            scanner.scanRelationStrengthening(
                "partial",
                new RelationSignature(SetRelationKind.EQUALITY, PropertyStrength.PARTIAL, "x"),
                new RelationSignature(SetRelationKind.EQUALITY, PropertyStrength.UNIVERSAL, "x")))
        .extracting(ProofControlModels.InferenceRisk::type)
        .containsExactly(ProofControlModels.InferenceRiskType.PARTIAL_PROPERTY_TO_TOTAL_PROPERTY);
    assertThat(
            scanner.scanRelationStrengthening(
                "coverage",
                new RelationSignature(SetRelationKind.EQUALITY, PropertyStrength.PARTIAL, "coverage"),
                new RelationSignature(SetRelationKind.EQUALITY, PropertyStrength.EXHAUSTIVE, "x")))
        .extracting(ProofControlModels.InferenceRisk::type)
        .containsExactly(ProofControlModels.InferenceRiskType.COVERAGE_TO_EXHAUSTIVENESS);
    assertThat(
            scanner.scanRelationStrengthening(
                "none",
                new RelationSignature(SetRelationKind.EQUALITY, PropertyStrength.UNIVERSAL, "x"),
                new RelationSignature(SetRelationKind.EQUALITY, PropertyStrength.UNIVERSAL, "x")))
        .isEmpty();

    var open = scanner.scanText("bridge", "local implies global").getFirst();
    assertThat(scanner.clearWithVerifiedBridge(open, "evidence").open()).isFalse();
    assertThatThrownBy(() -> scanner.clearWithVerifiedBridge(open, " "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void inductionMeasuresCoverTriggersValidationAndIndependentActivation() {
    InductionMeasureSelector selector = new InductionMeasureSelector();
    assertThat(selector.detectTrigger("plain argument")).isFalse();
    assertThat(selector.detectTrigger("ordinary induction alone")).isFalse();
    assertThat(selector.detectTrigger("ordinary induction with a repeated feature")).isTrue();
    assertThat(selector.detectTrigger("the first occurrence is decisive")).isTrue();
    assertThat(selector.detectTrigger("use a strictly smaller subobject")).isTrue();
    assertThatThrownBy(() -> selector.propose("r", null, "trigger", "author"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> selector.propose("r", List.of(), "trigger", "author"))
        .isInstanceOf(IllegalArgumentException.class);

    var structural =
        selector.propose("r", List.of("o2", "o1", "o1"), "recursive subobject", "author");
    assertThat(structural.measureName()).isEqualTo("structural_complexity");
    assertThat(selector.wellFounded(structural)).isTrue();
    assertThat(selector.propose("r", List.of("o2", "o1", "o1"), "recursive subobject", "other"))
        .isEqualTo(structural);
    var occurrence =
        selector.propose("r2", List.of("o3"), "feature appears repeatedly", "author");
    assertThat(occurrence.measureName()).isEqualTo("occurrence_count");
    assertThat(selector.wellFounded(occurrence)).isTrue();

    assertThat(selector.wellFounded(proposalLike(structural, "integers", List.of("base"), "x < y", "strict")))
        .isFalse();
    assertThat(selector.wellFounded(proposalLike(structural, "natural numbers", List.of(), "x < y", "strict")))
        .isFalse();
    assertThat(selector.wellFounded(proposalLike(structural, "natural numbers", List.of("base"), "x < y", "weak")))
        .isFalse();
    assertThat(selector.wellFounded(proposalLike(structural, "natural numbers", List.of("base"), "x <= y", "lowers")))
        .isTrue();
    assertThat(selector.wellFounded(proposalLike(structural, "natural numbers", List.of("base"), "x = y", "lowers")))
        .isFalse();

    assertThatThrownBy(() -> selector.accept("unknown", "reviewer", List.of("e")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> selector.promptDirective(structural))
        .isInstanceOf(IllegalArgumentException.class);
    for (String reviewer : new String[] {null, "", " ", "author"}) {
      assertThatThrownBy(() -> selector.accept(occurrence.id(), reviewer, List.of("e")))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> selector.accept(occurrence.id(), "reviewer", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> selector.accept(occurrence.id(), "reviewer", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    var accepted = selector.accept(occurrence.id(), "reviewer", List.of(" e2 ", "e1", "e1"));
    assertThat(accepted.status()).isEqualTo("accepted");
    assertThat(accepted.reviewEvidenceIds()).containsExactly(" e2 ", "e1");
    assertThat(selector.promptDirective(accepted)).contains("occurrence_count", "o3");
    assertThat(selector.accept(occurrence.id(), "reviewer", List.of("different")).activationActionId())
        .isEqualTo(accepted.activationActionId());
  }

  @Test
  void falsificationCompilationAndFastLaneCoverEveryOperatorAndGuard() {
    FalsificationService service = new FalsificationService();
    for (String operator : List.of(">=", "<=", "==", "!=", ">", "<")) {
      var compiled = service.compile("check n in [-2, 2]: n " + operator + " 0", "claim", 5);
      assertThat(compiled.status())
          .isEqualTo(ProofControlModels.FalsificationCompilationStatus.EXECUTABLE);
      assertThat(compiled.registeredHandler()).isEqualTo("bounded_integer_search");
    }
    assertThat(service.compile("check n in [2, 1]: n > 0", "claim", 10).status())
        .isEqualTo(ProofControlModels.FalsificationCompilationStatus.NEEDS_REWRITE);
    assertThat(service.compile("check n in [0, 10]: n > 0", "claim", 2).reason())
        .contains("exceeds");
    assertThat(service.compile("not executable", "claim", 0))
        .satisfies(
            contract -> {
              assertThat(contract.maxCases()).isEqualTo(256);
              assertThat(contract.status())
                  .isEqualTo(ProofControlModels.FalsificationCompilationStatus.NON_AUTOMATABLE);
              assertThat(contract.parameters()).isEmpty();
              assertThat(contract.registeredHandler()).isNull();
            });
    assertThatThrownBy(() -> service.compile("not executable", " ", 1))
        .isInstanceOf(IllegalArgumentException.class);

    var executable = service.compile("check n in [0, 1]: n > 0", "claim", 2);
    assertThat(
            service.fastLane(
                executable,
                new FalsificationService.FastLaneContext("claim", null, 2, 1.0d, true, false),
                2,
                1.0d)
                .eligible())
        .isTrue();
    assertThat(
            service.fastLane(
                executable,
                new FalsificationService.FastLaneContext(null, "obligation", 2, 1.0d, true, false),
                2,
                1.0d)
                .eligible())
        .isTrue();
    List<FalsificationService.FastLaneContext> rejected =
        List.of(
            new FalsificationService.FastLaneContext(null, null, 2, 1.0d, true, false),
            new FalsificationService.FastLaneContext(" ", " ", 2, 1.0d, true, false),
            new FalsificationService.FastLaneContext("claim", null, 3, 1.0d, true, false),
            new FalsificationService.FastLaneContext("claim", null, 2, 2.0d, true, false),
            new FalsificationService.FastLaneContext("claim", null, 2, 1.0d, false, false),
            new FalsificationService.FastLaneContext("claim", null, 2, 1.0d, true, true));
    for (var context : rejected) {
      assertThat(service.fastLane(executable, context, 2, 1.0d).eligible()).isFalse();
    }
    var nonAutomatable = service.compile("not executable", "claim", 2);
    assertThat(
            service.fastLane(
                nonAutomatable,
                new FalsificationService.FastLaneContext("claim", null, 1, 0.1d, true, false),
                2,
                1.0d)
                .eligible())
        .isFalse();
    for (ProofControlModels.FalsificationOutcome outcome :
        ProofControlModels.FalsificationOutcome.values()) {
      assertThat(service.classify(outcome).factPromotionAllowed()).isFalse();
    }
    assertThat(
            service.classify(ProofControlModels.FalsificationOutcome.COUNTEREXAMPLE_FOUND)
                .conclusiveRefutation())
        .isTrue();
  }

  @Test
  void messageUtilityCoversContractBroadcastUsageAndExpiryBranches() {
    MessageUtilityController controller = new MessageUtilityController();
    Set<String> known = Set.of("o1", "o2");
    assertThatThrownBy(
            () ->
                controller.registerContract(
                    "missing", "r", null, ProofControlModels.MessageExpectedEffect.REDUCE,
                    null, 1.0d, 2, known))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                controller.registerContract(
                    "empty", "r", List.of(), ProofControlModels.MessageExpectedEffect.REDUCE,
                    null, 1.0d, 2, known))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                controller.registerContract(
                    "unknown", "r", List.of("absent"), ProofControlModels.MessageExpectedEffect.REDUCE,
                    null, 1.0d, 2, known))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MessageUtilityController.Contract(
                    "id", "m", "r", List.of(), ProofControlModels.MessageExpectedEffect.REDUCE,
                    List.of(), -1.0d, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MessageUtilityController.Contract(
                    "id", "m", "r", List.of(), ProofControlModels.MessageExpectedEffect.REDUCE,
                    List.of(), 0.0d, -1))
        .isInstanceOf(IllegalArgumentException.class);

    var contract =
        controller.registerContract(
            "contracted",
            "r",
            List.of("o2", "o1", "o1"),
            ProofControlModels.MessageExpectedEffect.REDUCE,
            null,
            2.0d,
            3,
            known);
    assertThat(contract.targetObligationIds()).containsExactly("o1", "o2");
    assertThat(
            controller.registerContract(
                "contracted", "different", List.of("o1"),
                ProofControlModels.MessageExpectedEffect.CLOSE,
                List.of("a"), 0.0d, 0, known))
        .isEqualTo(contract);

    var counterexample = controller.decideBroadcast("counter", true, false, false, 1);
    assertThat(counterexample.decision()).isEqualTo(ProofControlModels.BroadcastDecision.BROADCAST);
    var high = controller.decideBroadcast("high", false, true, true, 1);
    assertThat(high.decision()).isEqualTo(ProofControlModels.BroadcastDecision.BROADCAST);
    var positive = controller.decideBroadcast("contracted", false, false, false, 3);
    assertThat(positive.decision()).isEqualTo(ProofControlModels.BroadcastDecision.BROADCAST);
    assertThat(controller.decideBroadcast("contracted", false, false, false, 99))
        .isEqualTo(positive);
    assertThat(controller.decideBroadcast("local", false, true, false, 1).decision())
        .isEqualTo(ProofControlModels.BroadcastDecision.KEEP_LOCAL);

    var unused =
        controller.recordUsage("contracted", "consumer", null, Set.of(), null, null, false);
    assertThat(unused.verifiedUse()).isFalse();
    assertThat(unused.utilityScore()).isZero();
    var steps =
        controller.recordUsage(
            "contracted", "consumer-steps", List.of("s2", "missing", "s1", "s1"),
            Set.of("s1", "s2"), List.of(), List.of(), false);
    assertThat(steps.referencedVerifiedStepIds()).containsExactly("s1", "s2");
    assertThat(steps.utilityScore()).isEqualTo(2.0d);
    var effects =
        controller.recordUsage(
            "contracted", "consumer-effects", List.of(), Set.of(),
            List.of("o1", "o2"), List.of("claim"), true);
    assertThat(effects.verifiedUse()).isTrue();
    assertThat(effects.utilityScore()).isEqualTo(9.0d);
    assertThat(
            controller.recordUsage(
                "contracted", "consumer-effects", List.of(), Set.of(),
                List.of("o1", "o2"), List.of("claim"), true))
        .isEqualTo(effects);
    assertThatThrownBy(
            () ->
                new MessageUtilityController.UsageReceipt(
                    "id", "m", "r", List.of(), List.of(), List.of(), false, false, -1.0d))
        .isInstanceOf(IllegalArgumentException.class);

    controller.registerContract(
        "expired-unused", "r", List.of("o1"), ProofControlModels.MessageExpectedEffect.REDUCE,
        List.of(), 0.0d, 1, known);
    controller.registerContract(
        "expired-used", "r", List.of("o1"), ProofControlModels.MessageExpectedEffect.REDUCE,
        List.of(), 0.0d, 1, known);
    controller.recordUsage(
        "expired-used", "consumer", List.of("s"), Set.of("s"), null, null, false);
    assertThat(controller.expiredUnusedCount(1)).isZero();
    assertThat(controller.expiredUnusedCount(2)).isEqualTo(1);
  }

  private static DependencyRef ref(
      DependencyKind kind, String target, String attempt, String delta, String audit) {
    return new DependencyRef(kind, target, attempt, delta, "route", null, audit);
  }

  private static ScopeSignature scope(
      String id, IndexScope index, UniformityScope uniformity) {
    return scope(id, index, uniformity, ObjectScope.FULL_OBJECT);
  }

  private static ScopeSignature scope(
      String id, IndexScope index, UniformityScope uniformity, ObjectScope object) {
    return new ScopeSignature(
        id, index, uniformity, object, List.of(), List.of(), List.of(), 1.0d);
  }

  private static InductionMeasureSelector.Proposal proposalLike(
      InductionMeasureSelector.Proposal source,
      String domain,
      List<String> baseCases,
      String step,
      String decrease) {
    return new InductionMeasureSelector.Proposal(
        source.id(),
        source.routeId(),
        source.targetObligationIds(),
        source.measureName(),
        domain,
        baseCases,
        step,
        decrease,
        source.naturalIndexInsufficiency(),
        source.triggerFeatures(),
        source.sourceAgentId(),
        source.status(),
        source.reviewerAgentId(),
        source.reviewEvidenceIds(),
        source.activationActionId());
  }
}
