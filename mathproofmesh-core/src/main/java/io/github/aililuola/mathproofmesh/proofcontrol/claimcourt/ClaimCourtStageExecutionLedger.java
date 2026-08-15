package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Durable exactly-once frontier for each bounded Claim Court provider stage. */
public final class ClaimCourtStageExecutionLedger {
  private final Map<String, ClaimCourtStageExecutionRecord> records = new LinkedHashMap<>();

  public synchronized ClaimCourtStageExecutionRecord reserve(
      String courtCaseId,
      ClaimCourtStage stage,
      List<String> claimIds,
      String inputHash,
      String assignedAgentId) {
    String caseId = ClaimCourtValues.required(courtCaseId, "courtCaseId");
    String input = ClaimCourtValues.required(inputHash, "inputHash");
    List<String> claims = ClaimCourtValues.copy(claimIds);
    String executionId =
        "claim-court-execution-"
            + CanonicalJson.stableHash(List.of(caseId, stage.name(), claims, input))
                .substring(0, 24);
    ClaimCourtStageExecutionRecord candidate =
        new ClaimCourtStageExecutionRecord(
            executionId,
            caseId,
            stage,
            claims,
            input,
            assignedAgentId,
            ClaimCourtStageExecutionStatus.RESERVED,
            null,
            null,
            0L,
            List.of("execution reserved"));
    ClaimCourtStageExecutionRecord existing = records.putIfAbsent(executionId, candidate);
    if (existing != null && !sameIdentity(existing, candidate)) {
      throw new IllegalStateException("claim court execution identity collision");
    }
    return existing == null ? candidate : existing;
  }

  public synchronized ClaimCourtStageExecutionRecord start(String executionId) {
    ClaimCourtStageExecutionRecord current = required(executionId);
    if (current.status() != ClaimCourtStageExecutionStatus.RESERVED) {
      return current;
    }
    return transition(current, ClaimCourtStageExecutionStatus.RUNNING, null, null, "execution started");
  }

  public synchronized ClaimCourtStageExecutionRecord recordResult(
      String executionId, JsonNode resultPayload) {
    ClaimCourtStageExecutionRecord current = required(executionId);
    JsonNode payload = java.util.Objects.requireNonNull(resultPayload, "resultPayload").deepCopy();
    String resultHash = CanonicalJson.stableHash(payload);
    if (current.status() == ClaimCourtStageExecutionStatus.RESULT_DURABLE
        || current.status() == ClaimCourtStageExecutionStatus.COMPLETED) {
      if (!MessageDigest.isEqual(
          resultHash.getBytes(StandardCharsets.UTF_8),
          current.resultHash().getBytes(StandardCharsets.UTF_8))) {
        throw new IllegalStateException("durable stage result changed on replay");
      }
      return current;
    }
    if (current.status() != ClaimCourtStageExecutionStatus.RUNNING) {
      throw new IllegalStateException("stage result requires a running execution");
    }
    return transition(
        current,
        ClaimCourtStageExecutionStatus.RESULT_DURABLE,
        payload,
        resultHash,
        "result made durable");
  }

  public synchronized ClaimCourtStageExecutionRecord complete(String executionId) {
    ClaimCourtStageExecutionRecord current = required(executionId);
    if (current.status() == ClaimCourtStageExecutionStatus.COMPLETED) {
      return current;
    }
    if (current.status() != ClaimCourtStageExecutionStatus.RESULT_DURABLE) {
      throw new IllegalStateException("only a durable result can complete");
    }
    return transition(
        current,
        ClaimCourtStageExecutionStatus.COMPLETED,
        current.resultPayload(),
        current.resultHash(),
        "result projected");
  }

  public synchronized ClaimCourtStageExecutionRecord quarantineInterrupted(String executionId) {
    ClaimCourtStageExecutionRecord current = required(executionId);
    if (current.status() != ClaimCourtStageExecutionStatus.RUNNING) {
      return current;
    }
    return transition(
        current,
        ClaimCourtStageExecutionStatus.QUARANTINED,
        null,
        null,
        "ambiguous running execution quarantined after restore");
  }

  public synchronized List<ClaimCourtStageExecutionRecord> quarantineInterrupted() {
    List<String> running =
        records.values().stream()
            .filter(record -> record.status() == ClaimCourtStageExecutionStatus.RUNNING)
            .map(ClaimCourtStageExecutionRecord::executionId)
            .toList();
    return running.stream().map(this::quarantineInterrupted).toList();
  }

  public synchronized ClaimCourtStageExecutionRecord get(String executionId) {
    return required(executionId);
  }

  public synchronized List<ClaimCourtStageExecutionRecord> records() {
    return records.values().stream()
        .sorted(Comparator.comparing(ClaimCourtStageExecutionRecord::executionId))
        .toList();
  }

  public synchronized ClaimCourtStageExecutionSnapshot snapshot() {
    return new ClaimCourtStageExecutionSnapshot(
        ClaimCourtStageExecutionSnapshot.CURRENT_SCHEMA_VERSION, records);
  }

  public synchronized String stableHash() {
    return CanonicalJson.stableHash(snapshot());
  }

  public synchronized void restore(ClaimCourtStageExecutionSnapshot snapshot) {
    ClaimCourtStageExecutionSnapshot source =
        snapshot == null ? ClaimCourtStageExecutionSnapshot.empty() : snapshot;
    source.records().forEach(
        (id, record) -> {
          if (!id.equals(record.executionId())) {
            throw new IllegalArgumentException("claim court execution snapshot key mismatch");
          }
        });
    records.clear();
    records.putAll(source.records());
  }

  private ClaimCourtStageExecutionRecord transition(
      ClaimCourtStageExecutionRecord current,
      ClaimCourtStageExecutionStatus status,
      JsonNode payload,
      String resultHash,
      String detail) {
    if (!allowed(current.status(), status)) {
      throw new IllegalStateException("invalid claim court execution transition");
    }
    List<String> history = new ArrayList<>(current.history());
    history.add(detail);
    ClaimCourtStageExecutionRecord updated =
        new ClaimCourtStageExecutionRecord(
            current.executionId(),
            current.courtCaseId(),
            current.stage(),
            current.claimIds(),
            current.inputHash(),
            current.assignedAgentId(),
            status,
            payload,
            resultHash,
            current.version() + 1L,
            history);
    records.put(updated.executionId(), updated);
    return updated;
  }

  private static boolean allowed(
      ClaimCourtStageExecutionStatus from, ClaimCourtStageExecutionStatus to) {
    return (from == ClaimCourtStageExecutionStatus.RESERVED
            && to == ClaimCourtStageExecutionStatus.RUNNING)
        || (from == ClaimCourtStageExecutionStatus.RUNNING
            && (to == ClaimCourtStageExecutionStatus.RESULT_DURABLE
                || to == ClaimCourtStageExecutionStatus.QUARANTINED))
        || (from == ClaimCourtStageExecutionStatus.RESULT_DURABLE
            && to == ClaimCourtStageExecutionStatus.COMPLETED);
  }

  private ClaimCourtStageExecutionRecord required(String executionId) {
    ClaimCourtStageExecutionRecord record =
        records.get(ClaimCourtValues.required(executionId, "executionId"));
    if (record == null) {
      throw new IllegalArgumentException("unknown claim court execution: " + executionId);
    }
    return record;
  }

  private static boolean sameIdentity(
      ClaimCourtStageExecutionRecord left, ClaimCourtStageExecutionRecord right) {
    return left.courtCaseId().equals(right.courtCaseId())
        && left.stage() == right.stage()
        && left.claimIds().equals(right.claimIds())
        && left.inputHash().equals(right.inputHash())
        && left.assignedAgentId().equals(right.assignedAgentId());
  }
}
