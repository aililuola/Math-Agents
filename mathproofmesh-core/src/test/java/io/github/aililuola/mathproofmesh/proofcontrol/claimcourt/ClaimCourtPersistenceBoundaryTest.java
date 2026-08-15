package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ClaimProofPatch;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ClaimCourtPersistenceBoundaryTest {
  @Test
  void stageExecutionLedgerEnforcesExactlyOnceTransitionsAndReplayIdentity() {
    ClaimCourtStageExecutionLedger ledger = new ClaimCourtStageExecutionLedger();
    ClaimCourtStageExecutionRecord reserved =
        ledger.reserve(
            "case-1", ClaimCourtStage.PROOF_AUDIT, List.of("claim-1"), "input-1", "auditor");
    assertThat(
            ledger.reserve(
                "case-1",
                ClaimCourtStage.PROOF_AUDIT,
                List.of("claim-1"),
                "input-1",
                "auditor"))
        .isSameAs(reserved);
    assertThatThrownBy(
            () ->
                ledger.reserve(
                    "case-1",
                    ClaimCourtStage.PROOF_AUDIT,
                    List.of("claim-1"),
                    "input-1",
                    "different-auditor"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("identity collision");
    assertThatThrownBy(
            () ->
                ledger.recordResult(
                    reserved.executionId(), JsonNodeFactory.instance.objectNode().put("ok", true)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("running");
    assertThatThrownBy(() -> ledger.complete(reserved.executionId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("durable");
    assertThat(ledger.quarantineInterrupted(reserved.executionId())).isSameAs(reserved);

    ClaimCourtStageExecutionRecord running = ledger.start(reserved.executionId());
    assertThat(ledger.start(reserved.executionId())).isSameAs(running);
    var payload = JsonNodeFactory.instance.objectNode().put("verdict", "valid");
    ClaimCourtStageExecutionRecord durable = ledger.recordResult(reserved.executionId(), payload);
    assertThat(ledger.recordResult(reserved.executionId(), payload)).isSameAs(durable);
    assertThatThrownBy(
            () ->
                ledger.recordResult(
                    reserved.executionId(),
                    JsonNodeFactory.instance.objectNode().put("verdict", "changed")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("changed on replay");
    ClaimCourtStageExecutionRecord completed = ledger.complete(reserved.executionId());
    assertThat(ledger.complete(reserved.executionId())).isSameAs(completed);
    assertThat(ledger.recordResult(reserved.executionId(), payload)).isSameAs(completed);
    assertThat(ledger.quarantineInterrupted(reserved.executionId())).isSameAs(completed);
    assertThat(ledger.get(reserved.executionId())).isSameAs(completed);
    assertThat(ledger.records()).containsExactly(completed);
    assertThat(ledger.stableHash()).isNotBlank();

    ClaimCourtStageExecutionRecord interrupted =
        ledger.reserve(
            "case-2",
            ClaimCourtStage.MINIMAL_REPAIR,
            List.of("claim-2"),
            "input-2",
            "repairer");
    ledger.start(interrupted.executionId());
    assertThat(ledger.quarantineInterrupted()).hasSize(1);
    assertThat(ledger.get(interrupted.executionId()).status())
        .isEqualTo(ClaimCourtStageExecutionStatus.QUARANTINED);
    assertThat(ledger.quarantineInterrupted()).isEmpty();
    assertThatThrownBy(() -> ledger.get("missing-execution"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void stageExecutionSnapshotsRejectCorruptionAndDefaultMissingState() {
    ClaimCourtStageExecutionLedger ledger = new ClaimCourtStageExecutionLedger();
    ClaimCourtStageExecutionRecord record =
        ledger.reserve(
            "case", ClaimCourtStage.STATEMENT_FALSIFICATION, List.of("claim"), "input", "agent");
    ClaimCourtStageExecutionLedger restored = new ClaimCourtStageExecutionLedger();
    restored.restore(ledger.snapshot());
    assertThat(restored.stableHash()).isEqualTo(ledger.stableHash());
    restored.restore(null);
    assertThat(restored.records()).isEmpty();

    Map<String, ClaimCourtStageExecutionRecord> wrongKey = new LinkedHashMap<>();
    wrongKey.put("wrong", record);
    ClaimCourtStageExecutionSnapshot malformed =
        new ClaimCourtStageExecutionSnapshot(
            ClaimCourtStageExecutionSnapshot.CURRENT_SCHEMA_VERSION, wrongKey);
    assertThatThrownBy(() -> restored.restore(malformed))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("key mismatch");
    assertThatThrownBy(() -> new ClaimCourtStageExecutionSnapshot(-1, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ClaimCourtStageExecutionSnapshot(
                    ClaimCourtStageExecutionSnapshot.CURRENT_SCHEMA_VERSION + 1, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new ClaimCourtStageExecutionSnapshot(0, null).records()).isEmpty();
  }

  @Test
  void proofRevisionLedgerRejectsIdentityMutationAndInvalidTransitions() {
    var claim = ClaimCourtTestFixtures.linearClaim();
    FrozenClaimSnapshot frozen = ClaimCourtTestFixtures.freeze(claim);
    ClaimProofRevisionLedger ledger = new ClaimProofRevisionLedger();
    ClaimProofRevisionRecord original =
        ledger.createOriginal(frozen, claim.proofSteps(), claim.evidenceRefs());
    assertThat(ledger.createOriginal(frozen, claim.proofSteps(), claim.evidenceRefs()))
        .isSameAs(original);
    assertThatThrownBy(
            () ->
                ledger.createOriginal(
                    frozen,
                    List.of(ClaimCourtTestFixtures.step("changed", "changed", "changed")),
                    List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("identity collision");

    var audit = ClaimCourtRepairTestFixtures.localAudit(claim.claimId(), "linear-step");
    ClaimProofPatch patch =
        ClaimCourtRepairTestFixtures.patch(frozen, original, "linear-step", "issue-linear-step");
    var validated =
        new ClaimProofPatchValidator(ClaimCourtConfig.defaults())
            .validate(frozen, original, audit, patch, java.util.Set.of());
    ClaimProofRevisionRecord repaired =
        ledger.createRepaired(
            frozen, original, patch, validated.proofSteps(), validated.evidenceRefs(), "repairer");
    assertThat(
            ledger.createRepaired(
                frozen, original, patch, validated.proofSteps(), validated.evidenceRefs(), "repairer"))
        .isSameAs(repaired);
    assertThat(ledger.recordsForClaim(claim.claimId())).hasSize(2);
    assertThat(ledger.recordsForClaim("another-claim")).isEmpty();
    assertThat(ledger.get(repaired.revisionId())).isSameAs(repaired);
    assertThat(ledger.stableHash()).isNotBlank();

    ClaimProofRevisionRecord verified = ledger.markBlindVerified(repaired.revisionId());
    assertThat(ledger.markBlindVerified(repaired.revisionId())).isSameAs(verified);
    assertThatThrownBy(() -> ledger.markBlindRejected(repaired.revisionId()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> ledger.get("missing-revision"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                ledger.createRepaired(
                    frozen,
                    original,
                    patch,
                    validated.proofSteps(),
                    frozen.authorAgentId()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("repairer must differ");

    ClaimProofRevisionLedger originalBlind = new ClaimProofRevisionLedger();
    ClaimProofRevisionRecord anotherOriginal =
        originalBlind.createOriginal(frozen, claim.proofSteps(), claim.evidenceRefs());
    assertThat(originalBlind.markBlindRejected(anotherOriginal.revisionId()).status())
        .isEqualTo(ClaimProofRevisionStatus.BLIND_REJECTED);
  }

  @Test
  void proofRevisionSnapshotsAndFrozenBindingsFailClosed() {
    var claim = ClaimCourtTestFixtures.linearClaim();
    FrozenClaimSnapshot frozen = ClaimCourtTestFixtures.freeze(claim);
    ClaimProofRevisionLedger ledger = new ClaimProofRevisionLedger();
    ClaimProofRevisionRecord original =
        ledger.createOriginal(frozen, claim.proofSteps(), claim.evidenceRefs());
    ClaimProofPatch validPatch =
        ClaimCourtRepairTestFixtures.patch(
            frozen, original, "linear-step", "issue-linear-step");

    assertFrozenMutationRejected(ledger, frozen, original, copyPatch(validPatch, "wrong-claim", null, null, null));
    assertFrozenMutationRejected(ledger, frozen, original, copyPatch(validPatch, null, "wrong-semantic", null, null));
    assertFrozenMutationRejected(ledger, frozen, original, copyPatch(validPatch, null, null, "wrong-revision", null));
    assertFrozenMutationRejected(ledger, frozen, original, copyPatch(validPatch, null, null, null, "wrong-proof"));

    ClaimProofRevisionLedger restored = new ClaimProofRevisionLedger();
    restored.restore(ledger.snapshot());
    assertThat(restored.stableHash()).isEqualTo(ledger.stableHash());
    restored.restore(null);
    assertThat(restored.records()).isEmpty();

    Map<String, ClaimProofRevisionRecord> wrongKey = new LinkedHashMap<>();
    wrongKey.put("wrong", original);
    ClaimProofRevisionSnapshot malformed =
        new ClaimProofRevisionSnapshot(
            ClaimProofRevisionSnapshot.CURRENT_SCHEMA_VERSION, wrongKey, List.of());
    assertThatThrownBy(() -> restored.restore(malformed))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("key mismatch");
    assertThatThrownBy(() -> new ClaimProofRevisionSnapshot(-1, Map.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new ClaimProofRevisionSnapshot(0, null, null).records()).isEmpty();
  }

  @Test
  void persistenceRecordsValidateRequiredPayloadAndProvenance() {
    assertThatThrownBy(
            () ->
                new ClaimCourtStageExecutionRecord(
                    "execution",
                    "case",
                    ClaimCourtStage.PROOF_AUDIT,
                    List.of(),
                    "input",
                    "agent",
                    ClaimCourtStageExecutionStatus.RESERVED,
                    null,
                    null,
                    0,
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ClaimCourtStageExecutionRecord(
                    "execution",
                    "case",
                    ClaimCourtStage.PROOF_AUDIT,
                    List.of("claim"),
                    "input",
                    "agent",
                    ClaimCourtStageExecutionStatus.RESULT_DURABLE,
                    null,
                    null,
                    0,
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ClaimCourtStageExecutionRecord(
                    "execution",
                    "case",
                    ClaimCourtStage.PROOF_AUDIT,
                    List.of("claim"),
                    "input",
                    "agent",
                    ClaimCourtStageExecutionStatus.RESERVED,
                    null,
                    null,
                    -1,
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class);

    var claim = ClaimCourtTestFixtures.linearClaim();
    FrozenClaimSnapshot frozen = ClaimCourtTestFixtures.freeze(claim);
    String proofHash = CanonicalJson.stableHash(claim.proofSteps());
    assertThatThrownBy(
            () ->
                new ClaimProofRevisionRecord(
                    "revision",
                    frozen.claimId(),
                    frozen.claimSemanticHash(),
                    "base",
                    claim.proofSteps(),
                    List.of(),
                    List.of(),
                    proofHash,
                    frozen.authorAgentId(),
                    null,
                    "patch",
                    ClaimProofRevisionStatus.REPAIRED_PENDING_ADJUDICATION,
                    0,
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("provenance");
    assertThatThrownBy(
            () ->
                new ClaimProofRevisionRecord(
                    "revision",
                    frozen.claimId(),
                    frozen.claimSemanticHash(),
                    null,
                    claim.proofSteps(),
                    List.of(),
                    List.of(),
                    proofHash,
                    frozen.authorAgentId(),
                    null,
                    null,
                    ClaimProofRevisionStatus.REPAIRED_PENDING_ADJUDICATION,
                    0,
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("original proof");
  }

  private static void assertFrozenMutationRejected(
      ClaimProofRevisionLedger ledger,
      FrozenClaimSnapshot frozen,
      ClaimProofRevisionRecord original,
      ClaimProofPatch patch) {
    assertThatThrownBy(
            () ->
                ledger.createRepaired(
                    frozen, original, patch, original.proofSteps(), "independent-repairer"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("FROZEN_CLAIM_MUTATION");
  }

  private static ClaimProofPatch copyPatch(
      ClaimProofPatch source,
      String claimId,
      String semanticHash,
      String revisionId,
      String proofHash) {
    return new ClaimProofPatch(
        source.patchId(),
        claimId == null ? source.claimId() : claimId,
        semanticHash == null ? source.claimSemanticHash() : semanticHash,
        revisionId == null ? source.baseProofRevisionId() : revisionId,
        proofHash == null ? source.baseProofHash() : proofHash,
        source.issueIds(),
        source.changedStepIds(),
        source.operations(),
        source.expectedResolvedIssueIds());
  }
}
