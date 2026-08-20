package io.github.aililuola.mathproofmesh.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class ConcurrencyDurabilityBoundaryTest {
  @Test
  void classifiesEveryTerminalStateAndReservedLeaseClass() {
    assertThat(
            Arrays.stream(ResearchWorkStatus.values())
                .filter(ResearchWorkStatus::settled)
                .toList())
        .containsExactly(
            ResearchWorkStatus.RESULT_DURABLE,
            ResearchWorkStatus.FAILED_DURABLE,
            ResearchWorkStatus.QUARANTINED_UNCERTAIN_CALL,
            ResearchWorkStatus.MERGED,
            ResearchWorkStatus.SUPERSEDED,
            ResearchWorkStatus.CANCELLED);
    assertThat(
            Arrays.stream(AgentLeaseClass.values())
                .filter(AgentLeaseClass::usesReservedCoordinationCapacity)
                .toList())
        .containsExactly(
            AgentLeaseClass.ADVERSARIAL_REVIEW,
            AgentLeaseClass.ADJUDICATION,
            AgentLeaseClass.COORDINATION);
  }

  @Test
  void valueObjectsNormalizeNullableCollectionsAndRejectMalformedBoundaries() {
    assertThat(AgentLeaseSnapshot.empty()).isEqualTo(new AgentLeaseSnapshot(null, 0L));
    assertThat(ResearchEpochSnapshot.empty()).isEqualTo(new ResearchEpochSnapshot(null, 0L));
    assertThat(ResearchTaskSnapshot.empty()).isEqualTo(new ResearchTaskSnapshot(null, 0L));
    assertThat(ResearchResultSnapshot.empty()).isEqualTo(new ResearchResultSnapshot(null, 0L));
    assertThat(ConcurrencyTelemetrySnapshot.empty())
        .isEqualTo(new ConcurrencyTelemetrySnapshot(null, 0L));
    assertThatThrownBy(() -> new AgentLeaseSnapshot(null, -1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ResearchEpochSnapshot(null, -1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ResearchTaskSnapshot(null, -1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ResearchResultSnapshot(null, -1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ConcurrencyTelemetrySnapshot(null, -1L))
        .isInstanceOf(IllegalArgumentException.class);

    ResearchEpochMutationSnapshot mutation = new ResearchEpochMutationSnapshot(null, null);
    assertThat(mutation).isEqualTo(ResearchEpochMutationSnapshot.empty());
    ConcurrencyMetrics metrics =
        new ConcurrencyMetrics(0, 0, 0, 0, 0, 0, 0, 0, null, null, 0, 0, 0);
    assertThat(metrics.perAgentBusyNanos()).isEmpty();
    assertThat(metrics.perAgentLeaseCount()).isEmpty();
    assertThat(new ResearchWorkReadSet(null, null)).isEqualTo(ResearchWorkReadSet.empty());
    assertThat(new ResearchWorkFailureArtifact("work", "epoch", "FAIL", null).publicDetail())
        .isEmpty();
    assertThat(
            new ResearchMergeReceipt("epoch", "merge", null, null, "authority")
                .acceptedResultHashes())
        .isEmpty();

    assertThatThrownBy(() -> new ConcurrencyTelemetryEvent(0L, 0L, null, null, null, null, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ConcurrencyTelemetryEvent(
                    1L, -1L, ConcurrencyEventType.WORK_QUEUED, null, null, null, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ConcurrencyTelemetryEvent(
                    1L, 0L, ConcurrencyEventType.WORK_QUEUED, null, null, null, -1))
        .isInstanceOf(IllegalArgumentException.class);
    ConcurrencyTelemetryEvent event =
        new ConcurrencyTelemetryEvent(
            1L, 0L, ConcurrencyEventType.WORK_QUEUED, null, null, null, 0);
    assertThat(event.epochId()).isEmpty();

    assertThatThrownBy(() -> ResearchEpochId.deterministic("run", -1, "authority"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ResearchWorkResultArtifact(" ", result(item(), "agent")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void frozenInputsAndResultsAreHashBoundAndDefensivelyNormalized() {
    ResearchAuthorityAnchor nullableAnchor =
        new ResearchAuthorityAnchor(
            "problem", "root", null, null, null, null, null, null, null, null, null, null, null,
            null, null);
    assertThat(nullableAnchor.negativeRegistryHash()).isEmpty();
    assertThatThrownBy(
            () ->
                new ResearchAuthorityAnchor(
                    " ", "root", null, null, null, null, null, null, null, null, null, null, null,
                    null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ResearchAuthorityAnchor(
                    null, "root", null, null, null, null, null, null, null, null, null, null, null,
                    null, null))
        .isInstanceOf(NullPointerException.class);

    FrozenResearchSnapshot emptyInputs =
        new FrozenResearchSnapshot("epoch", nullableAnchor, null);
    FrozenResearchSnapshot suppliedHash =
        new FrozenResearchSnapshot(
            "epoch", nullableAnchor, Map.of(), emptyInputs.snapshotHash());
    assertThat(suppliedHash).isEqualTo(emptyInputs);
    assertThatThrownBy(
            () -> new FrozenResearchSnapshot("epoch", nullableAnchor, Map.of(), "wrong"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FrozenResearchSnapshot(" ", nullableAnchor, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);

    ResearchWorkItem item = item();
    ResearchWorkResultEnvelope nullableCollections =
        new ResearchWorkResultEnvelope(
            item.workItemId(),
            item.epochId(),
            item.snapshotHash(),
            "agent",
            "request",
            ResearchWorkResultStatus.SUCCEEDED,
            null,
            null,
            null,
            null);
    ResearchWorkResultEnvelope suppliedResultHash =
        new ResearchWorkResultEnvelope(
            item.workItemId(),
            item.epochId(),
            item.snapshotHash(),
            "agent",
            "request",
            ResearchWorkResultStatus.SUCCEEDED,
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            nullableCollections.resultHash());
    assertThat(suppliedResultHash).isEqualTo(nullableCollections);
    assertThatThrownBy(
            () ->
                new ResearchWorkResultEnvelope(
                    item.workItemId(),
                    item.epochId(),
                    item.snapshotHash(),
                    "agent",
                    "request",
                    ResearchWorkResultStatus.SUCCEEDED,
                    Map.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    "wrong"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ResearchWorkResultEnvelope(
                    " ",
                    item.epochId(),
                    item.snapshotHash(),
                    "agent",
                    "request",
                    ResearchWorkResultStatus.SUCCEEDED,
                    Map.of(),
                    List.of(),
                    List.of(),
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void leaseAuthorityIsIdempotentConflictCheckedAndCrashRecoverable() {
    AgentLeaseRequest nullableRequest =
        new AgentLeaseRequest(
            "run",
            "epoch",
            "work",
            AgentLeaseClass.RESEARCH,
            "role",
            null,
            null,
            null,
            null,
            1);
    assertThat(nullableRequest.excludedAgentIds()).isEmpty();
    assertThat(nullableRequest.specialtyHints()).isEmpty();
    assertThatThrownBy(
            () ->
                new AgentLeaseRequest(
                    "run",
                    "epoch",
                    "work",
                    AgentLeaseClass.RESEARCH,
                    "role",
                    Set.of(),
                    List.of(),
                    "",
                    "",
                    0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AgentLeaseRequest(
                    "run",
                    "epoch",
                    "work",
                    AgentLeaseClass.RESEARCH,
                    "role",
                    Set.of(),
                    List.of(),
                    "author",
                    "",
                    1))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                new AgentLeaseRecord(
                    "lease",
                    "run",
                    "epoch",
                    "work",
                    "agent",
                    AgentLeaseClass.RESEARCH,
                    AgentLeaseStatus.ACQUIRED,
                    -1L,
                    0L,
                    1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AgentLeaseRecord(
                    "lease",
                    "run",
                    "epoch",
                    "work",
                    "agent",
                    AgentLeaseClass.RESEARCH,
                    AgentLeaseStatus.ACQUIRED,
                    0L,
                    -1L,
                    1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AgentLeaseRecord(
                    "lease",
                    "run",
                    "epoch",
                    "work",
                    "agent",
                    AgentLeaseClass.RESEARCH,
                    AgentLeaseStatus.ACQUIRED,
                    0L,
                    0L,
                    0L))
        .isInstanceOf(IllegalArgumentException.class);

    AgentLeaseRecord base = lease("lease", "run", "work", AgentLeaseStatus.ACQUIRED);
    assertThat(base.transition(AgentLeaseStatus.RUNNING, 11L).releasedNanos()).isZero();
    assertThat(base.transition(AgentLeaseStatus.RELEASED, 5L).releasedNanos()).isEqualTo(10L);
    assertThat(base.transition(AgentLeaseStatus.EXPIRED, 12L).releasedNanos()).isEqualTo(12L);
    assertThat(base.transition(AgentLeaseStatus.ABANDONED, 13L).releasedNanos()).isEqualTo(13L);
    for (AgentLeaseStatus terminal :
        List.of(
            AgentLeaseStatus.RELEASED,
            AgentLeaseStatus.EXPIRED,
            AgentLeaseStatus.ABANDONED)) {
      AgentLeaseRecord record = lease("lease-" + terminal, "run", "work", terminal);
      assertThat(record.terminal()).isTrue();
      assertThat(record.transition(AgentLeaseStatus.RUNNING, 20L)).isSameAs(record);
    }
    assertThat(base.terminal()).isFalse();

    AgentLeaseLedger ledger = new AgentLeaseLedger();
    assertThat(ledger.acquire("lease", nullableRequest, "agent", 10L))
        .isSameAs(ledger.acquire("lease", nullableRequest, "agent", 20L));
    AgentLeaseRequest otherWork =
        new AgentLeaseRequest(
            "run",
            "epoch",
            "other",
            AgentLeaseClass.RESEARCH,
            "role",
            Set.of(),
            List.of(),
            "",
            "",
            1);
    assertThatThrownBy(() -> ledger.acquire("lease", otherWork, "agent", 20L))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> ledger.acquire("lease", nullableRequest, "other-agent", 20L))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> ledger.transition("missing", AgentLeaseStatus.RELEASED, 20L))
        .isInstanceOf(IllegalArgumentException.class);

    AgentLeaseSnapshot restoreSnapshot =
        new AgentLeaseSnapshot(
            List.of(
                lease("active", "run", "a", AgentLeaseStatus.RUNNING),
                lease("foreign", "other-run", "b", AgentLeaseStatus.RUNNING),
                lease("terminal", "run", "c", AgentLeaseStatus.RELEASED)),
            3L);
    ledger.restore(restoreSnapshot, "run");
    assertThat(ledger.snapshot().leases())
        .extracting(AgentLeaseRecord::status)
        .containsExactly(
            AgentLeaseStatus.ABANDONED,
            AgentLeaseStatus.RUNNING,
            AgentLeaseStatus.RELEASED);
  }

  @Test
  void epochTransitionsAndPlanningFailClosedAcrossEveryFrontier() {
    ResearchEpochRecord initial =
        new ResearchEpochRecord(
            "epoch", "snapshot", ResearchEpochStatus.PLANNED, null, null, null, 1L);
    assertThat(initial.transition(ResearchEpochStatus.PLANNED, null, null).status())
        .isEqualTo(ResearchEpochStatus.PLANNED);
    ResearchEpochRecord dispatching =
        initial.transition(ResearchEpochStatus.DISPATCHING, null, null);
    ResearchEpochRecord settled =
        dispatching.transition(ResearchEpochStatus.ALL_SETTLED, List.of("result"), null);
    ResearchEpochRecord prepared =
        settled.transition(ResearchEpochStatus.MERGE_PREPARED, null, "merge");
    ResearchEpochRecord committed =
        prepared.transition(ResearchEpochStatus.COMMITTED, null, null);
    assertThat(committed.status()).isEqualTo(ResearchEpochStatus.COMMITTED);
    for (ResearchEpochStatus terminal :
        List.of(
            ResearchEpochStatus.ABORTED,
            ResearchEpochStatus.QUARANTINED,
            ResearchEpochStatus.STALE_SNAPSHOT)) {
      assertThat(initial.transition(terminal, null, null).status()).isEqualTo(terminal);
      assertThatThrownBy(() -> committed.transition(terminal, null, null))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThatThrownBy(
            () -> initial.transition(ResearchEpochStatus.ALL_SETTLED, null, null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> dispatching.transition(ResearchEpochStatus.COMMITTED, null, null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> settled.transition(ResearchEpochStatus.COMMITTED, null, null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> prepared.transition(ResearchEpochStatus.DISPATCHING, null, null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                new ResearchEpochRecord(
                    "epoch",
                    "snapshot",
                    ResearchEpochStatus.PLANNED,
                    List.of(),
                    List.of(),
                    "",
                    0L))
        .isInstanceOf(IllegalArgumentException.class);

    FrozenResearchSnapshot frozen = ConcurrencyTestFixtures.snapshot();
    ResearchEpochLedger ledger = new ResearchEpochLedger();
    assertThat(ledger.plan(frozen, List.of("work")))
        .isSameAs(ledger.plan(frozen, List.of("different-work")));
    FrozenResearchSnapshot conflicting =
        new FrozenResearchSnapshot(
            frozen.epochId(), frozen.authority(), Map.of("other", "artifact://other"));
    assertThatThrownBy(() -> ledger.plan(conflicting, List.of()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> ledger.require("missing"))
        .isInstanceOf(IllegalArgumentException.class);
    ResearchEpochLedger restored = new ResearchEpochLedger();
    restored.restore(ledger.snapshot());
    assertThat(restored.require(frozen.epochId()).snapshotHash())
        .isEqualTo(frozen.snapshotHash());

    ResearchEpochPlanner planner = new ResearchEpochPlanner();
    ResearchWorkItem valid = ConcurrencyTestFixtures.item(frozen, 0, "route", null);
    assertThat(planner.plan(frozen, List.of(valid), 1).workItems()).containsExactly(valid);
    assertThat(new ResearchEpochPlanner.Plan(frozen, null).workItems()).isEmpty();
    assertThatThrownBy(
            () -> planner.plan(frozen, List.of(rebound(valid, "other-epoch", valid.snapshotHash())), 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> planner.plan(frozen, List.of(rebound(valid, valid.epochId(), "other-hash")), 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void taskAndResultLedgersPreserveExactlyOnceIdentity() {
    FrozenResearchSnapshot frozen = ConcurrencyTestFixtures.snapshot();
    ResearchWorkItem item = ConcurrencyTestFixtures.item(frozen, 0, "route", null);
    ResearchTaskLedger tasks = new ResearchTaskLedger();
    assertThat(tasks.allSettled(frozen.epochId())).isFalse();
    assertThat(tasks.plan(item)).isSameAs(tasks.plan(item));
    assertThatThrownBy(() -> tasks.plan(withRoute(item, "other-route")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> tasks.require("missing"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(tasks.allSettled(frozen.epochId())).isFalse();
    tasks.transition(
        item.workItemId(), ResearchWorkStatus.RESULT_DURABLE, "agent", "request", "ref", "hash");
    assertThat(tasks.allSettled(frozen.epochId())).isTrue();
    long version = tasks.snapshot().version();
    tasks.transition(
        item.workItemId(), ResearchWorkStatus.MERGED, null, null, null, null);
    tasks.transition(
        item.workItemId(), ResearchWorkStatus.PLANNED, null, null, null, null);
    assertThat(tasks.snapshot().version()).isEqualTo(version + 1L);

    assertThatThrownBy(
            () ->
                new ResearchWorkRecord(
                    item, ResearchWorkStatus.PLANNED, null, null, null, null, 0L))
        .isInstanceOf(IllegalArgumentException.class);
    ResearchWorkRecord superseded =
        new ResearchWorkRecord(
            item, ResearchWorkStatus.SUPERSEDED, null, null, null, null, 1L);
    assertThat(
            superseded.transition(
                ResearchWorkStatus.RUNNING, "agent", "request", "ref", "hash"))
        .isSameAs(superseded);

    ResearchResultLedger results = new ResearchResultLedger();
    ResearchWorkResultEnvelope result = result(item, "agent");
    ResearchWorkResultArtifact artifact = results.store(result);
    assertThat(results.store(result)).isSameAs(artifact);
    assertThat(results.require(item.workItemId())).isSameAs(artifact);
    assertThatThrownBy(() -> results.require("missing"))
        .isInstanceOf(IllegalArgumentException.class);
    ResearchWorkResultEnvelope conflicting = result(item, "different-agent");
    assertThatThrownBy(() -> results.store(conflicting))
        .isInstanceOf(IllegalStateException.class);
    ResearchResultSnapshot duplicate = new ResearchResultSnapshot(List.of(artifact, artifact), 2L);
    assertThatThrownBy(() -> results.restore(duplicate))
        .isInstanceOf(IllegalArgumentException.class);
    results.restore(new ResearchResultSnapshot(List.of(artifact), 1L));
    assertThat(results.snapshot().artifacts()).containsExactly(artifact);
  }

  @Test
  void mergePlanningClassifiesFailureStalenessAndAuthorityChanges() {
    FrozenResearchSnapshot frozen = ConcurrencyTestFixtures.snapshot();
    ResearchWorkItem a = ConcurrencyTestFixtures.item(frozen, 0, "a", null);
    ResearchWorkItem b = ConcurrencyTestFixtures.item(frozen, 1, "b", null);
    ResearchWorkItem c = ConcurrencyTestFixtures.item(frozen, 2, "c", null);
    ResearchWorkResultEnvelope succeeded = result(a, "agent-a");
    ResearchWorkResultEnvelope failed =
        resultWithBinding(b, b.epochId(), b.snapshotHash(), ResearchWorkResultStatus.FAILED);
    ResearchWorkResultEnvelope stale =
        resultWithBinding(c, c.epochId(), "stale-hash", ResearchWorkResultStatus.SUCCEEDED);
    ResearchMergePlanner planner = new ResearchMergePlanner();
    ResearchMergePlan plan =
        planner.plan(frozen, List.of(c, b, a), List.of(stale, failed, succeeded));
    assertThat(plan.decisions())
        .extracting(ResearchMergeDecision::reason)
        .containsExactly("accepted", "result_not_successful", "stale_snapshot");

    assertThatThrownBy(
            () -> planner.plan(frozen, List.of(rebound(a, "other", a.snapshotHash())), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> planner.plan(frozen, List.of(rebound(a, a.epochId(), "other")), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> planner.plan(frozen, List.of(a, a), List.of(succeeded)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                planner.plan(
                    frozen,
                    List.of(a),
                    List.of(result(item(frozen, 99, "unknown"), "agent"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> planner.plan(frozen, List.of(a), List.of(succeeded, succeeded)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> planner.plan(frozen, List.of(a, b), List.of(succeeded)))
        .isInstanceOf(IllegalStateException.class);

    ResearchMergePlan empty = new ResearchMergePlan(frozen.epochId(), frozen.snapshotHash(), null);
    assertThat(empty.decisions()).isEmpty();
    assertThatThrownBy(
            () ->
                new ResearchMergePlan(
                    frozen.epochId(), frozen.snapshotHash(), plan.decisions(), "wrong"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ResearchMergePlan(" ", frozen.snapshotHash(), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ResearchMergePlan(frozen.epochId(), " ", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ResearchMergeDecision("", "hash", true, "reason", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ResearchMergeDecision("work", "", true, "reason", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ResearchMergeDecision("work", "hash", true, "", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ResearchMergeDecision("work", "hash", true, "reason", -1))
        .isInstanceOf(IllegalArgumentException.class);

    ResearchEpochCommitter committer = new ResearchEpochCommitter();
    ResearchMergeReceipt receipt =
        committer
            .commit(
                frozen,
                plan,
                frozen::authority,
                new ResearchAuthorityMutationTransaction<ResearchEpochMutationSnapshot>() {
                  @Override
                  public ResearchEpochMutationSnapshot snapshot() {
                    return ResearchEpochMutationSnapshot.empty();
                  }

                  @Override
                  public ResearchAuthorityMutationReceipt apply(List<String> accepted) {
                    return ResearchAuthorityMutationReceipt.create(
                        frozen.epochId(),
                        plan.mergePlanHash(),
                        frozen.authority().stableHash(),
                        "authority-after",
                        accepted,
                        List.of(),
                        List.of(),
                        List.of());
                  }

                  @Override
                  public void restore(ResearchEpochMutationSnapshot snapshot) {}
                })
            .mergeReceipt();
    assertThat(receipt.acceptedResultHashes()).containsExactly(succeeded.resultHash());
    assertThat(receipt.rejectedResultHashes())
        .containsExactly(failed.resultHash(), stale.resultHash());
    assertThatThrownBy(
            () ->
                committer.commit(
                    frozen,
                    plan,
                    () ->
                        new ResearchAuthorityAnchor(
                            "other-problem",
                            "root",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            ""),
                    new ResearchAuthorityMutationTransaction<ResearchEpochMutationSnapshot>() {
                      @Override
                      public ResearchEpochMutationSnapshot snapshot() {
                        return ResearchEpochMutationSnapshot.empty();
                      }

                      @Override
                      public ResearchAuthorityMutationReceipt apply(List<String> accepted) {
                        throw new AssertionError("stale authority must fail before mutation");
                      }

                      @Override
                      public void restore(ResearchEpochMutationSnapshot snapshot) {}
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("STALE_SNAPSHOT");
  }

  @Test
  void conflictPolicyAndReadyQueueCoverEveryConflictDimension() {
    FrozenResearchSnapshot frozen = ConcurrencyTestFixtures.snapshot();
    List<ResearchWorkConflictSet> conflictSets =
        List.of(
            new ResearchWorkConflictSet(Set.of("r"), null, null, null, null),
            new ResearchWorkConflictSet(null, Set.of("c"), null, null, null),
            new ResearchWorkConflictSet(null, null, Set.of("p"), null, null),
            new ResearchWorkConflictSet(null, null, null, Set.of("o"), null),
            new ResearchWorkConflictSet(null, null, null, null, Set.of("s")));
    for (ResearchWorkConflictSet conflictSet : conflictSets) {
      assertThat(conflictSet.conflictsWith(conflictSet)).isTrue();
    }
    assertThat(ResearchWorkConflictSet.empty().conflictsWith(ResearchWorkConflictSet.empty()))
        .isFalse();

    ResearchWorkItem first = withConflict(item(frozen, 0, "a"), conflictSets.getFirst());
    ResearchWorkItem conflicting = withConflict(item(frozen, 1, "b"), conflictSets.getFirst());
    ResearchWorkItem independent = item(frozen, 2, "c");
    ResearchWorkConflictPolicy policy = new ResearchWorkConflictPolicy();
    assertThat(policy.maximumStableIndependentSet(List.of(conflicting, independent, first), 2))
        .containsExactly(first, independent);
    assertThat(policy.maximumStableIndependentSet(List.of(first), 0)).isEmpty();
    assertThatThrownBy(() -> policy.maximumStableIndependentSet(List.of(), -1))
        .isInstanceOf(IllegalArgumentException.class);

    ResearchReadyQueue queue = new ResearchReadyQueue();
    queue.addAll(List.of(conflicting, independent));
    assertThat(queue.pollCompatible(List.of(first))).contains(independent);
    assertThat(queue.pollCompatible(List.of(first))).isEmpty();
    assertThat(queue.isEmpty()).isFalse();
    assertThat(queue.size()).isEqualTo(1);
    assertThat(queue.pollCompatible(List.of())).contains(conflicting);
    assertThat(queue.isEmpty()).isTrue();
  }

  @Test
  void telemetryMeasuresAllSettledBarrierAndIncompleteIntervals() {
    AtomicLong ticker = new AtomicLong(-1L);
    ConcurrencyTelemetryLedger ledger = new ConcurrencyTelemetryLedger(ticker::get);
    assertThat(ledger.metrics(4, 5).maxActiveProviderCalls()).isZero();
    assertThatThrownBy(() -> ledger.metrics(0, 5))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ledger.metrics(4, 3))
        .isInstanceOf(IllegalArgumentException.class);

    ledger.record(ConcurrencyEventType.WORK_QUEUED, "epoch", "a", null, 2);
    assertThat(ledger.metrics(4, 5).meanConcurrencyWholeRun()).isZero();
    ticker.set(1L);
    ledger.record(ConcurrencyEventType.LEASE_ACQUIRED, "epoch", "a", "agent-a", 2);
    ticker.set(2L);
    ledger.record(ConcurrencyEventType.PROVIDER_CALL_STARTED, "epoch", "a", "agent-a", 1);
    ticker.set(3L);
    ledger.record(ConcurrencyEventType.FIRST_TOKEN_RECEIVED, "epoch", "a", "agent-a", 1);
    ticker.set(4L);
    ledger.record(ConcurrencyEventType.PROVIDER_CALL_STARTED, "epoch", "b", "agent-b", 1);
    ticker.set(5L);
    ledger.record(ConcurrencyEventType.BARRIER_RELEASED, "epoch", "", "", 1);
    ticker.set(6L);
    ledger.record(ConcurrencyEventType.BARRIER_ENTERED, "epoch", "", "", 1);
    ticker.set(7L);
    ledger.record(ConcurrencyEventType.PROVIDER_CALL_COMPLETED, "epoch", "missing", "agent", 1);
    ticker.set(8L);
    ledger.record(ConcurrencyEventType.PROVIDER_CALL_COMPLETED, "epoch", "a", "agent-a", 1);
    ticker.set(9L);
    ledger.record(ConcurrencyEventType.PROVIDER_CALL_COMPLETED, "epoch", "b", "agent-b", 0);
    ticker.set(10L);
    ledger.record(ConcurrencyEventType.BARRIER_RELEASED, "epoch", "", "", 0);
    for (ConcurrencyEventType type :
        List.of(
            ConcurrencyEventType.RESULT_DURABLE,
            ConcurrencyEventType.LEASE_RELEASED,
            ConcurrencyEventType.MERGE_STARTED,
            ConcurrencyEventType.MERGE_COMPLETED)) {
      ticker.incrementAndGet();
      ledger.record(type, "epoch", "a", "agent-a", 0);
    }
    ConcurrencyMetrics metrics = ledger.metrics(4, 5);
    assertThat(metrics.maxActiveProviderCalls()).isEqualTo(2);
    assertThat(metrics.perAgentBusyNanos()).containsKeys("agent-a", "agent-b");
    assertThat(metrics.perAgentLeaseCount()).containsEntry("agent-a", 1L);
    assertThat(metrics.barrierWaitNanos()).isEqualTo(4L);

    ConcurrencyTelemetryLedger restored = new ConcurrencyTelemetryLedger(ticker::get);
    restored.restore(ledger.snapshot());
    assertThat(restored.snapshot()).isEqualTo(ledger.snapshot());

    ConcurrencyTelemetryLedger incomplete = new ConcurrencyTelemetryLedger(ticker::get);
    incomplete.record(ConcurrencyEventType.WORK_QUEUED, "epoch", "a", "", 1);
    ticker.incrementAndGet();
    incomplete.record(ConcurrencyEventType.LEASE_ACQUIRED, "epoch", "a", "agent", 0);
    assertThat(incomplete.metrics(1, 1).meanConcurrencyProviderActiveWindow()).isZero();
  }

  private static FrozenResearchSnapshot snapshot() {
    return ConcurrencyTestFixtures.snapshot();
  }

  private static ResearchWorkItem item() {
    return item(snapshot(), 0, "route");
  }

  private static ResearchWorkItem item(
      FrozenResearchSnapshot snapshot, int ordinal, String routeId) {
    return ConcurrencyTestFixtures.item(snapshot, ordinal, routeId, null);
  }

  private static ResearchWorkItem rebound(
      ResearchWorkItem item, String epochId, String snapshotHash) {
    return new ResearchWorkItem(
        item.workItemId(),
        epochId,
        snapshotHash,
        item.kind(),
        item.routeId(),
        item.claimId(),
        item.obligationId(),
        item.canonicalTargetId(),
        item.requiredRole(),
        item.leaseClass(),
        item.excludedAgentIds(),
        item.readSet(),
        item.conflictSet(),
        item.inputArtifactRef(),
        item.expectedResultSchema(),
        item.stableOrdinal());
  }

  private static ResearchWorkItem withRoute(ResearchWorkItem item, String routeId) {
    return new ResearchWorkItem(
        item.workItemId(),
        item.epochId(),
        item.snapshotHash(),
        item.kind(),
        routeId,
        item.claimId(),
        item.obligationId(),
        item.canonicalTargetId(),
        item.requiredRole(),
        item.leaseClass(),
        item.excludedAgentIds(),
        item.readSet(),
        item.conflictSet(),
        item.inputArtifactRef(),
        item.expectedResultSchema(),
        item.stableOrdinal());
  }

  private static ResearchWorkItem withConflict(
      ResearchWorkItem item, ResearchWorkConflictSet conflictSet) {
    return new ResearchWorkItem(
        item.workItemId(),
        item.epochId(),
        item.snapshotHash(),
        item.kind(),
        item.routeId(),
        item.claimId(),
        item.obligationId(),
        item.canonicalTargetId(),
        item.requiredRole(),
        item.leaseClass(),
        item.excludedAgentIds(),
        item.readSet(),
        conflictSet,
        item.inputArtifactRef(),
        item.expectedResultSchema(),
        item.stableOrdinal());
  }

  private static ResearchWorkResultEnvelope result(ResearchWorkItem item, String agentId) {
    return new ResearchWorkResultEnvelope(
        item.workItemId(),
        item.epochId(),
        item.snapshotHash(),
        agentId,
        "request",
        ResearchWorkResultStatus.SUCCEEDED,
        Map.of("answer", agentId),
        List.of(),
        List.of(),
        List.of());
  }

  private static ResearchWorkResultEnvelope resultWithBinding(
      ResearchWorkItem item,
      String epochId,
      String snapshotHash,
      ResearchWorkResultStatus status) {
    return new ResearchWorkResultEnvelope(
        item.workItemId(),
        epochId,
        snapshotHash,
        "agent-" + item.stableOrdinal(),
        "request-" + item.stableOrdinal(),
        status,
        Map.of("status", status.name()),
        List.of(),
        List.of(),
        List.of());
  }

  private static AgentLeaseRecord lease(
      String leaseId, String runId, String workItemId, AgentLeaseStatus status) {
    return new AgentLeaseRecord(
        leaseId,
        runId,
        "epoch",
        workItemId,
        "agent",
        AgentLeaseClass.RESEARCH,
        status,
        10L,
        status == AgentLeaseStatus.RELEASED
                || status == AgentLeaseStatus.EXPIRED
                || status == AgentLeaseStatus.ABANDONED
            ? 11L
            : 0L,
        1L);
  }
}
