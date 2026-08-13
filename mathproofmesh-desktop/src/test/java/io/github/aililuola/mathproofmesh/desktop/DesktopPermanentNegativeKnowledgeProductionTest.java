package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeAuditEvent;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeDecisionCode;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeKind;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeRegistry;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeSurface;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopPermanentNegativeKnowledgeProductionTest {
  private static final int ROUNDS = 30;
  private static final int RESTORE_ROUND = 15;
  private static final int WIDENING_ROUNDS = 10;

  @TempDir Path temporaryDirectory;

  @Test
  void blocksPermanentAliasesAcrossEveryDesktopMutationAndActualCheckpointRestore()
      throws Exception {
    String runId = "desktop-permanent-negative-knowledge";
    Path runDirectory = temporaryDirectory.resolve("production-run");
    DesktopNegativeKnowledgeTestHarness harness =
        DesktopNegativeKnowledgeTestHarness.open(runDirectory, runId);
    Map<NegativeKnowledgeSurface, Integer> blocks =
        new EnumMap<>(NegativeKnowledgeSurface.class);
    int reentryAttempts = 0;
    int activeRouteLeaks = 0;
    int proofGraphLeaks = 0;
    int revisionLineageLeaks = 0;
    int inspirationMemoryLeaks = 0;
    int pendingTaskLeaks = 0;
    int factMemoryLeaks = 0;
    int postRestoreLeaks = 0;
    int permanentDowngrades = 0;
    String preRestoreHash = null;
    String postRestoreHash = null;

    try {
      harness.freezeAndCreateValidRoute();
      harness.addVerifiedAndTemporaryFixtures();
      NegativeKnowledgeRegistry initialRegistry =
          harness.typedMemory().negativeKnowledgeRegistry();
      assertThat(DesktopSolveCheckpoint.CURRENT_SCHEMA_VERSION).isEqualTo(7);
      assertThat(initialRegistry.records())
          .filteredOn(
              record ->
                  record.kinds().contains(NegativeKnowledgeKind.DETERMINISTIC_GUARDRAIL))
          .hasSize(4);
      assertThat(initialRegistry.records())
          .filteredOn(
              record ->
                  record.kinds().contains(NegativeKnowledgeKind.VERIFIED_COUNTEREXAMPLE))
          .hasSize(1);
      assertThat(initialRegistry.records())
          .filteredOn(
              record ->
                  !record.permanent()
                      && record.kinds()
                          .contains(NegativeKnowledgeKind.TEMPORARY_HYPOTHESIS_REJECTION))
          .hasSize(1);

      for (int round = 0; round < ROUNDS; round++) {
        if (round == RESTORE_ROUND) {
          preRestoreHash =
              harness.typedMemory().negativeKnowledgeRegistry().registryHash();
          DesktopSolveCheckpoint checkpoint = harness.checkpointRoundTrip();
          harness.close();
          harness = DesktopNegativeKnowledgeTestHarness.open(runDirectory, runId);
          harness.restore(checkpoint);
          postRestoreHash =
              harness.typedMemory().negativeKnowledgeRegistry().registryHash();
          assertThat(postRestoreHash).isEqualTo(preRestoreHash);
        }
        harness.setRound(round);
        if (round == 16) {
          harness.addWeakDuplicate(round, DesktopNegativeKnowledgeTestHarness.alias(0));
        }

        String strategyAlias = DesktopNegativeKnowledgeTestHarness.alias(round);
        String obligationAlias = DesktopNegativeKnowledgeTestHarness.alias(round + 1);
        String revisionAlias = DesktopNegativeKnowledgeTestHarness.alias(round + 2);
        String inspirationAlias = DesktopNegativeKnowledgeTestHarness.alias(round + 3);
        String factAlias = DesktopNegativeKnowledgeTestHarness.alias(round + 4);

        DesktopNegativeKnowledgeTestHarness.ProductionState beforeStrategy = harness.state();
        int auditStart = auditSize(harness);
        harness.attemptStrategyAdmission(round, strategyAlias);
        assertBlockedSince(
            harness, auditStart, NegativeKnowledgeSurface.STRATEGY_ADMISSION);
        blocks.merge(NegativeKnowledgeSurface.STRATEGY_ADMISSION, 1, Integer::sum);
        reentryAttempts++;
        if (harness.containsInvalidActiveState(round)
            || harness.state().routeCount() != beforeStrategy.routeCount()) {
          activeRouteLeaks++;
        }

        DesktopNegativeKnowledgeTestHarness.ProductionState beforeObligation = harness.state();
        auditStart = auditSize(harness);
        boolean obligationBlocked = false;
        try {
          harness.attemptObligation(round, obligationAlias);
        } catch (io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeBlockedException expected) {
          obligationBlocked = true;
        }
        assertThat(obligationBlocked).isTrue();
        assertBlockedSince(
            harness, auditStart, NegativeKnowledgeSurface.PROOF_OBLIGATION_CREATION);
        blocks.merge(NegativeKnowledgeSurface.PROOF_OBLIGATION_CREATION, 1, Integer::sum);
        reentryAttempts++;
        if (harness.state().obligationCount() != beforeObligation.obligationCount()) {
          proofGraphLeaks++;
        }

        DesktopNegativeKnowledgeTestHarness.ProductionState beforeRevision = harness.state();
        auditStart = auditSize(harness);
        assertThat(harness.attemptRevision(round, revisionAlias)).isFalse();
        assertBlockedSince(harness, auditStart, NegativeKnowledgeSurface.ROUTE_REVISION);
        blocks.merge(NegativeKnowledgeSurface.ROUTE_REVISION, 1, Integer::sum);
        reentryAttempts++;
        DesktopNegativeKnowledgeTestHarness.ProductionState afterRevision = harness.state();
        if (afterRevision.lineageCount() != beforeRevision.lineageCount()
            || afterRevision.checkpointBranchCount()
                != beforeRevision.checkpointBranchCount()
            || harness.containsInvalidActiveState(round)) {
          revisionLineageLeaks++;
        }

        DesktopNegativeKnowledgeTestHarness.ProductionState beforeInspiration = harness.state();
        auditStart = auditSize(harness);
        harness.attemptInspiration(round, inspirationAlias);
        assertBlockedSince(
            harness, auditStart, NegativeKnowledgeSurface.INSPIRATION_MATERIALIZATION);
        blocks.merge(NegativeKnowledgeSurface.INSPIRATION_MATERIALIZATION, 1, Integer::sum);
        reentryAttempts++;
        DesktopNegativeKnowledgeTestHarness.ProductionState afterInspiration = harness.state();
        if (afterInspiration.insightCount() != beforeInspiration.insightCount()) {
          inspirationMemoryLeaks++;
        }
        if (afterInspiration.pendingTaskCount() != beforeInspiration.pendingTaskCount()) {
          pendingTaskLeaks++;
        }
        if (afterInspiration.obligationCount() != beforeInspiration.obligationCount()) {
          proofGraphLeaks++;
        }

        DesktopNegativeKnowledgeTestHarness.ProductionState beforeFact = harness.state();
        auditStart = auditSize(harness);
        boolean factBlocked = false;
        try {
          harness.attemptFactPromotion(round, factAlias);
        } catch (io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeBlockedException expected) {
          factBlocked = true;
        }
        assertThat(factBlocked).isTrue();
        assertBlockedSince(harness, auditStart, NegativeKnowledgeSurface.FACT_PROMOTION);
        blocks.merge(NegativeKnowledgeSurface.FACT_PROMOTION, 1, Integer::sum);
        reentryAttempts++;
        if (harness.state().factCount() != beforeFact.factCount()) {
          factMemoryLeaks++;
        }

        if (round >= RESTORE_ROUND && harness.containsInvalidActiveState(round)) {
          postRestoreLeaks++;
        }
        if (harness.typedMemory().negativeKnowledgeRegistry().records().stream()
            .filter(record -> record.permanent())
            .anyMatch(record -> record.expiresAfterRound() != null)) {
          permanentDowngrades++;
        }
        assertThat(harness.state().routeCount()).isEqualTo(1);
        assertThat(harness.admittedStrategies())
            .extracting(io.github.aililuola.mathproofmesh.contract.StrategyCard::strategyId)
            .containsExactly("valid-production-strategy");
      }
    } finally {
      harness.close();
    }

    assertThat(preRestoreHash).isNotNull();
    assertThat(postRestoreHash).isEqualTo(preRestoreHash);
    assertThat(reentryAttempts).isEqualTo(150);
    assertThat(blocks.getOrDefault(NegativeKnowledgeSurface.STRATEGY_ADMISSION, 0))
        .isEqualTo(30);
    assertThat(blocks.getOrDefault(NegativeKnowledgeSurface.PROOF_OBLIGATION_CREATION, 0))
        .isEqualTo(30);
    assertThat(blocks.getOrDefault(NegativeKnowledgeSurface.ROUTE_REVISION, 0))
        .isEqualTo(30);
    assertThat(blocks.getOrDefault(NegativeKnowledgeSurface.INSPIRATION_MATERIALIZATION, 0))
        .isEqualTo(30);
    assertThat(blocks.getOrDefault(NegativeKnowledgeSurface.FACT_PROMOTION, 0))
        .isEqualTo(30);
    assertThat(activeRouteLeaks).isZero();
    assertThat(proofGraphLeaks).isZero();
    assertThat(revisionLineageLeaks).isZero();
    assertThat(inspirationMemoryLeaks).isZero();
    assertThat(pendingTaskLeaks).isZero();
    assertThat(factMemoryLeaks).isZero();
    assertThat(postRestoreLeaks).isZero();
    assertThat(permanentDowngrades).isZero();

    System.out.println("DESKTOP PERMANENT NEGATIVE KNOWLEDGE PRODUCTION DIAGNOSTIC");
    System.out.println("ROUNDS=" + ROUNDS);
    System.out.println("RESTORE_ROUND=" + RESTORE_ROUND);
    System.out.println("REENTRY_ATTEMPTS=" + reentryAttempts);
    System.out.println("STRATEGY_BLOCKS=" + blocks.get(NegativeKnowledgeSurface.STRATEGY_ADMISSION));
    System.out.println(
        "OBLIGATION_BLOCKS="
            + blocks.get(NegativeKnowledgeSurface.PROOF_OBLIGATION_CREATION));
    System.out.println("REVISION_BLOCKS=" + blocks.get(NegativeKnowledgeSurface.ROUTE_REVISION));
    System.out.println(
        "INSPIRATION_BLOCKS="
            + blocks.get(NegativeKnowledgeSurface.INSPIRATION_MATERIALIZATION));
    System.out.println("FACT_PROMOTION_BLOCKS=" + blocks.get(NegativeKnowledgeSurface.FACT_PROMOTION));
    System.out.println("ACTIVE_ROUTE_LEAKS=" + activeRouteLeaks);
    System.out.println("PROOF_GRAPH_LEAKS=" + proofGraphLeaks);
    System.out.println("REVISION_LINEAGE_LEAKS=" + revisionLineageLeaks);
    System.out.println("INSPIRATION_MEMORY_LEAKS=" + inspirationMemoryLeaks);
    System.out.println("PENDING_TASK_LEAKS=" + pendingTaskLeaks);
    System.out.println("FACT_MEMORY_LEAKS=" + factMemoryLeaks);
    System.out.println("POST_RESTORE_LEAKS=" + postRestoreLeaks);
    System.out.println("PERMANENT_DOWNGRADES=" + permanentDowngrades);
    System.out.println("PRE_RESTORE_REGISTRY_HASH=" + preRestoreHash);
    System.out.println("POST_RESTORE_REGISTRY_HASH=" + postRestoreHash);
    System.out.println("RESULT=PASS");
  }

  @Test
  void blockedNegativeCandidateCannotEnterThroughRealWidenRoutes() throws Exception {
    Path runDirectory = temporaryDirectory.resolve("widening-run");
    int wideningBlocks = 0;
    int routeLeaks = 0;
    int admittedStrategyLeaks = 0;
    int lineageLeaks = 0;
    int obligationLeaks = 0;

    try (DesktopNegativeKnowledgeTestHarness harness =
        DesktopNegativeKnowledgeTestHarness.open(
            runDirectory, "desktop-negative-widening")) {
      harness.freezeAndCreateValidRoute();
      for (int round = 0; round < WIDENING_ROUNDS; round++) {
        harness.setRound(round);
        int auditStart = auditSize(harness);
        DesktopNegativeKnowledgeTestHarness.WideningAttempt attempt =
            harness.attemptWidening(round, DesktopNegativeKnowledgeTestHarness.alias(round));

        assertThat(attempt.widened()).isFalse();
        assertThat(attempt.candidateRouted()).isFalse();
        assertBlockedSince(harness, auditStart, NegativeKnowledgeSurface.ROUTE_WIDENING);
        wideningBlocks++;
        if (attempt.after().routeCount() != attempt.before().routeCount()
            || attempt.candidateRouted()) {
          routeLeaks++;
        }
        if (attempt.after().admittedStrategyCount()
            != attempt.before().admittedStrategyCount()) {
          admittedStrategyLeaks++;
        }
        if (attempt.after().lineageCount() != attempt.before().lineageCount()) {
          lineageLeaks++;
        }
        if (attempt.after().obligationCount() != attempt.before().obligationCount()) {
          obligationLeaks++;
        }
      }
    }

    assertThat(wideningBlocks).isEqualTo(WIDENING_ROUNDS);
    assertThat(routeLeaks).isZero();
    assertThat(admittedStrategyLeaks).isZero();
    assertThat(lineageLeaks).isZero();
    assertThat(obligationLeaks).isZero();

    System.out.println("ROUTE WIDENING NEGATIVE KNOWLEDGE DIAGNOSTIC");
    System.out.println("ROUNDS=" + WIDENING_ROUNDS);
    System.out.println("ROUTE_WIDENING_BLOCK_TESTS=" + wideningBlocks);
    System.out.println("WIDENING_BLOCKS=" + wideningBlocks);
    System.out.println("ROUTE_LEAKS=" + routeLeaks);
    System.out.println("ADMITTED_STRATEGY_LEAKS=" + admittedStrategyLeaks);
    System.out.println("LINEAGE_LEAKS=" + lineageLeaks);
    System.out.println("OBLIGATION_LEAKS=" + obligationLeaks);
    System.out.println("RESULT=PASS");
  }

  private static int auditSize(DesktopNegativeKnowledgeTestHarness harness) {
    return harness.typedMemory().negativeKnowledgeRegistry().audit().size();
  }

  private static void assertBlockedSince(
      DesktopNegativeKnowledgeTestHarness harness,
      int auditStart,
      NegativeKnowledgeSurface surface) {
    java.util.List<NegativeKnowledgeAuditEvent> audit =
        harness.typedMemory().negativeKnowledgeRegistry().audit();
    assertThat(audit.subList(auditStart, audit.size()))
        .anySatisfy(
            event -> {
              assertThat(event.surface()).isEqualTo(surface);
              assertThat(event.decisionCode())
                  .isEqualTo(NegativeKnowledgeDecisionCode.BLOCK_PERMANENT);
            });
  }
}
