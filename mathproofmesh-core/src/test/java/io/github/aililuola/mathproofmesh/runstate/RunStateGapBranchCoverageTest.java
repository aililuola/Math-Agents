package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RunStateGapBranchCoverageTest {
  @Test
  void commitEnvelopeBindsStateToItsTransitionFrontier() {
    RunStateSnapshot state =
        RunStateTestSupport.state(
            RunExecutionStatus.RUNNING,
            RunStateTestSupport.partial(),
            RunStateTestSupport.usage(1, 2, 3),
            null,
            RunReportStatus.PARTIAL);
    RunStateTransition transition =
        new RunStateTransition(
            "transition-one",
            state.authority().runId(),
            0L,
            "",
            state.stateHash(),
            RunStateTransitionTrigger.RUN_CREATED,
            Map.of(),
            Instant.parse("2026-01-01T00:00:00Z"));
    RunStateTransitionSnapshot transitions =
        new RunStateTransitionSnapshot(List.of(transition), null);
    RunStateCommitEnvelope envelope = RunStateCommitEnvelope.create(state, transitions);

    assertThat(envelope.commitHash()).hasSize(64);
    assertThat(new RunStateCommitEnvelope(1, state, RunStateTransitionSnapshot.empty(), null))
        .isNotNull();
    assertThat(
            new RunStateCommitEnvelope(
                1, state, transitions, envelope.commitHash()))
        .isEqualTo(envelope);
    assertThatThrownBy(() -> new RunStateCommitEnvelope(2, state, transitions, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RunStateCommitEnvelope(1, null, transitions, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RunStateCommitEnvelope(1, state, null, null))
        .isInstanceOf(NullPointerException.class);
    RunStateTransition wrongFrontier =
        new RunStateTransition(
            "transition-wrong",
            state.authority().runId(),
            0L,
            "",
            "f".repeat(64),
            RunStateTransitionTrigger.RUN_CREATED,
            Map.of(),
            Instant.parse("2026-01-01T00:00:00Z"));
    assertThatThrownBy(
            () ->
                RunStateCommitEnvelope.create(
                    state, new RunStateTransitionSnapshot(List.of(wrongFrontier), null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new RunStateCommitEnvelope(1, state, transitions, "0".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void progressSnapshotNormalizesClaimIdentitySetsAndVerifiesTheirHashes() {
    List<String> ids = List.of("claim-a", "claim-b");
    String idsHash = CanonicalJson.stableHash(ids);
    RunMathematicalProgressSnapshot normalized =
        progress(0, 0, List.of(" claim-b ", "", "claim-a", "claim-a"), null, idsHash, null);

    assertThat(normalized.verifiedLocalClaims()).isEqualTo(2);
    assertThat(normalized.verifiedClaimIds()).containsExactlyElementsOf(ids);
    assertThat(normalized.refutedClaimIds()).isEmpty();
    assertThat(normalized.verifiedClaimIdSetHash()).isEqualTo(idsHash);
    assertThat(normalized.refutedClaimIdSetHash()).isEmpty();
    assertThat(normalized.finalProofHash()).isEmpty();
    assertThat(normalized.finalReviewHash()).isEmpty();
    assertThatThrownBy(
            () -> progress(0, 0, ids, List.of(), "0".repeat(64), ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void progressReconcilerMergesLegacyHashesAndQuarantinesAuthorityConflicts() {
    RunMathematicalProgressReconciler reconciler = new RunMathematicalProgressReconciler();
    String hashA = "a".repeat(64);
    String hashB = "b".repeat(64);
    assertThat(reconciler.reconcile(null, null).progress())
        .isEqualTo(RunMathematicalProgressSnapshot.empty());

    RunMathematicalProgressSnapshot prior =
        progress(2, 2, List.of(), List.of(), hashA, hashA, hashA, hashA);
    RunMathematicalProgressSnapshot stronger =
        progress(3, 3, List.of(), List.of(), hashB, hashB, hashB, hashB);
    RunMathematicalProgressReconciliationResult authorityConflict =
        reconciler.reconcile(stronger, prior);
    assertThat(authorityConflict.progress().verifiedClaimIdSetHash()).isEqualTo(hashB);
    assertThat(authorityConflict.progress().refutedClaimIdSetHash()).isEqualTo(hashB);
    assertThat(authorityConflict.conflicts())
        .extracting(RunStateConflict::code)
        .containsExactlyInAnyOrder("FINAL_PROOF_HASH_CONFLICT", "FINAL_REVIEW_HASH_CONFLICT");

    RunMathematicalProgressSnapshot weaker =
        progress(1, 1, List.of(), List.of(), "", "", "", "");
    RunMathematicalProgressReconciliationResult preserved = reconciler.reconcile(weaker, prior);
    assertThat(preserved.progress().verifiedClaimIdSetHash()).isEqualTo(hashA);
    assertThat(preserved.progress().refutedClaimIdSetHash()).isEqualTo(hashA);
    assertThat(preserved.progress().finalProofHash()).isEqualTo(hashA);
    assertThat(preserved.progress().finalReviewHash()).isEqualTo(hashA);

    RunMathematicalProgressSnapshot sameCountConflict =
        progress(2, 2, List.of(), List.of(), hashB, hashB, hashA, hashA);
    assertThat(reconciler.reconcile(sameCountConflict, prior).conflicts())
        .extracting(RunStateConflict::code)
        .contains("VERIFIED_CLAIM_SET_CONFLICT", "REFUTED_CLAIM_SET_CONFLICT");

    RunMathematicalProgressSnapshot identified =
        progress(
            0,
            0,
            List.of("verified-b", "verified-a"),
            List.of("refuted-a"),
            "",
            "");
    RunMathematicalProgressSnapshot additional =
        progress(
            0,
            0,
            List.of("verified-c"),
            List.of("refuted-b"),
            "",
            "");
    RunMathematicalProgressSnapshot union = reconciler.reconcile(additional, identified).progress();
    assertThat(union.verifiedClaimIds())
        .containsExactly("verified-a", "verified-b", "verified-c");
    assertThat(union.refutedClaimIds()).containsExactly("refuted-a", "refuted-b");
  }

  private static RunMathematicalProgressSnapshot progress(
      int verified,
      int refuted,
      List<String> verifiedIds,
      List<String> refutedIds,
      String verifiedHash,
      String refutedHash) {
    return progress(
        verified, refuted, verifiedIds, refutedIds, verifiedHash, refutedHash, null, null);
  }

  private static RunMathematicalProgressSnapshot progress(
      int verified,
      int refuted,
      List<String> verifiedIds,
      List<String> refutedIds,
      String verifiedHash,
      String refutedHash,
      String finalProofHash,
      String finalReviewHash) {
    return new RunMathematicalProgressSnapshot(
        verified,
        refuted,
        0,
        true,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        true,
        verifiedIds,
        refutedIds,
        verifiedHash,
        refutedHash,
        finalProofHash,
        finalReviewHash);
  }
}
