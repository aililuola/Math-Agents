package io.github.aililuola.mathproofmesh.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ShadowComparatorTest {
  @Test
  void equalSnapshotsCoverEveryRequiredParitySection() {
    ObjectNode snapshot = completeSnapshot();

    ShadowComparator.ShadowComparisonReport report =
        new ShadowComparator().compare(snapshot, snapshot.deepCopy(), Set.of());

    assertTrue(report.passed());
    assertEquals(ShadowComparator.REQUIRED_SECTIONS, report.sectionsCompared());
    assertTrue(report.explainedDifferences().isEmpty());
    assertTrue(report.criticalDifferences().isEmpty());
    assertEquals(report.pythonSnapshotHash(), report.javaSnapshotHash());
  }

  @Test
  void missingRequiredSectionIsCritical() {
    ObjectNode javaSnapshot = completeSnapshot();
    javaSnapshot.remove("memory");

    ShadowComparator.ShadowComparisonReport report =
        new ShadowComparator().compare(completeSnapshot(), javaSnapshot, Set.of());

    assertFalse(report.passed());
    assertTrue(
        report.criticalDifferences().stream()
            .anyMatch(difference -> difference.pointer().equals("/memory")));
  }

  @Test
  void contractHashDifferenceCannotBeWaived() {
    ObjectNode javaSnapshot = completeSnapshot();
    ((ObjectNode) javaSnapshot.path("problem_contract")).put("integrity_hash", "b".repeat(64));

    ShadowComparator.ShadowComparisonReport report =
        new ShadowComparator()
            .compare(
                completeSnapshot(),
                javaSnapshot,
                Set.of("/problem_contract/integrity_hash"));

    assertFalse(report.passed());
    assertEquals("/problem_contract/integrity_hash", report.criticalDifferences().getFirst().pointer());
  }

  @Test
  void stateDifferenceCannotBeWaived() {
    ObjectNode javaSnapshot = completeSnapshot();
    ((ObjectNode) javaSnapshot.path("final_state")).put("status", "failed");

    ShadowComparator.ShadowComparisonReport report =
        new ShadowComparator()
            .compare(completeSnapshot(), javaSnapshot, Set.of("/final_state/status"));

    assertFalse(report.passed());
    assertTrue(report.criticalDifferences().getFirst().critical());
  }

  @Test
  void explicitlyDeclaredNaturalLanguageDifferenceIsExplained() {
    ObjectNode javaSnapshot = completeSnapshot();
    ((ObjectNode) javaSnapshot.path("final_state")).put("explanation", "Equivalent Java wording");

    ShadowComparator.ShadowComparisonReport report =
        new ShadowComparator()
            .compare(
                completeSnapshot(),
                javaSnapshot,
                Set.of("/final_state/explanation"));

    assertTrue(report.passed());
    assertEquals(1, report.explainedDifferences().size());
    assertFalse(report.explainedDifferences().getFirst().critical());
  }

  @Test
  void undeclaredNaturalLanguageDifferenceFails() {
    ObjectNode javaSnapshot = completeSnapshot();
    ((ObjectNode) javaSnapshot.path("final_state")).put("explanation", "Undeclared wording");

    ShadowComparator.ShadowComparisonReport report =
        new ShadowComparator().compare(completeSnapshot(), javaSnapshot, Set.of());

    assertFalse(report.passed());
    assertEquals("/final_state/explanation", report.criticalDifferences().getFirst().pointer());
  }

  @Test
  void arrayOrderRemainsStructuralAndCannotDrift() {
    ObjectNode javaSnapshot = completeSnapshot();
    com.fasterxml.jackson.databind.node.ArrayNode messages = javaSnapshot.withArray("messages");
    messages.add(messages.remove(0));

    ShadowComparator.ShadowComparisonReport report =
        new ShadowComparator().compare(completeSnapshot(), javaSnapshot, Set.of());

    assertFalse(report.passed());
  }

  @Test
  void invalidWaiverPointerIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ShadowComparator()
                .compare(completeSnapshot(), completeSnapshot(), Set.of("final_state/text")));
  }

  @Test
  void reportsContainHashesRatherThanRawDifferingText() {
    ObjectNode javaSnapshot = completeSnapshot();
    ((ObjectNode) javaSnapshot.path("final_state")).put("explanation", "not-for-reporting");

    ShadowComparator.ShadowComparisonReport report =
        new ShadowComparator()
            .compare(
                completeSnapshot(),
                javaSnapshot,
                Set.of("/final_state/explanation"));

    ShadowComparator.Difference difference = report.explainedDifferences().getFirst();
    assertEquals(64, difference.pythonValueHash().length());
    assertEquals(64, difference.javaValueHash().length());
    assertFalse(difference.toString().contains("not-for-reporting"));
  }

  @Test
  void typeDifferenceIsCritical() {
    ObjectNode javaSnapshot = completeSnapshot();
    javaSnapshot.put("usage", "not-an-object");

    ShadowComparator.ShadowComparisonReport report =
        new ShadowComparator().compare(completeSnapshot(), javaSnapshot, Set.of());

    assertFalse(report.passed());
    assertTrue(
        report.criticalDifferences().stream()
            .anyMatch(difference -> difference.kind().equals("type")));
  }

  static ObjectNode completeSnapshot() {
    return (ObjectNode)
        ContractObjectMapper.parseTree(
            """
            {
              "problem_contract": {
                "problem_id": "problem-1",
                "integrity_hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "kind": "theorem"
              },
              "strategies": [
                {"strategy_id": "strategy-1", "status": "active", "score": 0.9}
              ],
              "messages": [
                {"message_id": "message-1", "status": "admitted", "content_hash": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},
                {"message_id": "message-2", "status": "delivered", "content_hash": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}
              ],
              "deliveries": [
                {"delivery_id": "delivery-1", "state": "prompt_consumed"}
              ],
              "memory": {
                "facts": [{"memory_id": "memory-1", "status": "verified"}],
                "insights": [],
                "negative": []
              },
              "proof_graph": {
                "nodes": ["goal", "lemma-1"],
                "edges": [{"source_id": "lemma-1", "target_id": "goal", "status": "active"}]
              },
              "checkpoints": [
                {"checkpoint_id": "checkpoint-1", "status": "committed", "parent_checkpoint_id": ""}
              ],
              "recovery": {
                "checkpoint_id": "checkpoint-1",
                "provider_calls_before_resume": 0
              },
              "usage": {
                "provider_calls": 0,
                "input_tokens": 0,
                "output_tokens": 0
              },
              "final_state": {
                "status": "completed",
                "verified_claim_hash": "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                "explanation": "Python wording"
              }
            }
            """);
  }
}
