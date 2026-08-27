package io.github.aililuola.mathproofmesh.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class Phase17StructuredPayloadHardeningTest {

  @Test
  void serverOwnedFieldsAreStrippedRecursivelyAcrossObjectsAndArrays() {
    ObjectNode payload =
        object(
            """
            {
              "content_hash":"model",
              "normalized_hash":"model",
              "request_hash":"model",
              "code_hash":"model",
              "result_hash":"model",
              "semantic_hash":"model",
              "kind_migration":"forged",
              "nested":{
                "content_hash":"nested",
                "items":[
                  {"request_hash":"array","kind_migration":"forged"},
                  "plain"
                ]
              }
            }
            """);
    StructuredPayloadNormalizer.stripServerOwnedHashes(payload);

    for (String field :
        List.of(
            "content_hash",
            "normalized_hash",
            "request_hash",
            "code_hash",
            "result_hash",
            "semantic_hash")) {
      assertTrue(payload.path(field).asText().isEmpty());
    }
    assertFalse(payload.has("kind_migration"));
    assertTrue(payload.path("nested").path("content_hash").asText().isEmpty());
    assertFalse(payload.path("nested").path("items").get(0).has("kind_migration"));
    assertTrue(
        payload.path("nested").path("items").get(0).path("request_hash").asText().isEmpty());
  }

  @Test
  void everyKnownMetaAliasRequiresAnExactActionMatch() {
    assertAlias("invent_auxiliary_construction", "auxiliary_construction");
    assertAlias("search_analogy", "structural_analogy");
    assertAlias("switch_representation", "representation_switch");
    assertAlias("rewrite_plan", "meta_replan");

    ObjectNode mismatch =
        object(
            """
            {
              "selected_mechanism":"search_analogy",
              "action":"rewrite_plan"
            }
            """);
    assertTrue(
        StructuredPayloadNormalizer.normalize(mismatch, MetaStrategyDecision.class).isEmpty());
    assertEquals("search_analogy", mismatch.path("selected_mechanism").asText());

    ObjectNode missing = object("{}");
    assertTrue(
        StructuredPayloadNormalizer.normalize(missing, MetaStrategyDecision.class).isEmpty());
  }

  @Test
  void dependencyNamespacesCoverAmbiguousMissingNestedAndNonExternalReferences() {
    ObjectNode payload =
        object(
            """
            {
              "step_id":"shared",
              "claim_id":"shared",
              "nested":[
                {
                  "step_id":"step-only",
                  "claim_id":"claim-only",
                  "dependency_refs":[
                    {"kind":"external","target_id":"shared"},
                    {"kind":"external"},
                    {"kind":"external","target_id":"step-only"},
                    {"kind":"external","target_id":"claim-only"},
                    {"kind":"local_step","target_id":"step-only"},
                    "not-an-object"
                  ]
                }
              ]
            }
            """);
    List<String> actions = StructuredPayloadNormalizer.normalize(payload, ClaimBatch.class);
    ArrayNode refs =
        (ArrayNode) payload.path("nested").get(0).path("dependency_refs");

    assertEquals("external_result", refs.get(0).path("kind").asText());
    assertEquals("external_result", refs.get(1).path("kind").asText());
    assertEquals("local_step", refs.get(2).path("kind").asText());
    assertEquals("local_claim", refs.get(3).path("kind").asText());
    assertEquals("local_step", refs.get(4).path("kind").asText());
    assertEquals(4, actions.size());
  }

  @Test
  void computationNormalizationLeavesAlreadySafeAndNonComputationTurnsAlone() {
    ObjectNode alreadySafe =
        object(
            """
            {
              "action":"request_computation",
              "experiment_impact":null,
              "experiment_spec":{"purpose":"discover_pattern","broad_search":true}
            }
            """);
    assertTrue(
        StructuredPayloadNormalizer.normalize(alreadySafe, InitialExplorationTurn.class)
            .isEmpty());

    ObjectNode targeted =
        object(
            """
            {
              "action":"request_computation",
              "experiment_impact":null,
              "experiment_spec":{"purpose":"falsify_claim","broad_search":false}
            }
            """);
    assertTrue(
        StructuredPayloadNormalizer.normalize(targeted, ContinuationTurn.class).isEmpty());

    ObjectNode nonComputation =
        object(
            """
            {
              "action":"submit_delta",
              "experiment_impact":"execution",
              "experiment_spec":"not-an-object"
            }
            """);
    assertTrue(
        StructuredPayloadNormalizer.normalize(nonComputation, InitialExplorationTurn.class)
            .isEmpty());
    assertEquals("execution", nonComputation.path("experiment_impact").asText());
  }

  @Test
  void proofDeltaExpansionAndMissingAnswerRecoveryAreIdempotent() {
    ObjectNode delta =
        object(
            """
            {
              "proof_complete":true,
              "candidate_final_answer":null,
              "remaining_subgoals":null,
              "current_goal":null,
              "new_steps":[
                {"step_id":"s1","statement":"first"},
                {"statement":"anonymous"},
                "not-an-object"
              ],
              "new_claims":[
                {"proof_steps":["s1","missing",{"step_id":"inline"}]},
                {"statement":"without proof steps"},
                "not-an-object"
              ],
              "detected_conflicts":[],
              "normalization_notes":[]
            }
            """);
    List<String> first = StructuredPayloadNormalizer.normalize(delta, ProofDelta.class);
    assertTrue(first.contains("expanded new_claims[0].proof_steps reference 's1'"));
    assertTrue(first.contains("missing_final_answer_downgraded_to_partial"));
    assertFalse(delta.path("proof_complete").asBoolean());
    assertTrue(delta.path("ready_for_verification").asBoolean());
    assertTrue(delta.path("current_goal").asText().contains("self-contained"));
    assertEquals(1, delta.path("remaining_subgoals").size());
    assertEquals(1, delta.path("normalization_notes").size());
    assertTrue(delta.path("new_claims").get(0).path("proof_steps").get(0).isObject());

    List<String> second = StructuredPayloadNormalizer.normalize(delta, ProofDelta.class);
    assertTrue(second.isEmpty());
    assertEquals(1, delta.path("normalization_notes").size());
  }

  @Test
  void continuationRecoveryCoversEmptyArraysConflictsAndReplacementNotes() {
    ObjectNode turn =
        object(
            """
            {
              "action":"complete",
              "delta":{
                "proof_complete":true,
                "candidate_final_answer":" ",
                "remaining_subgoals":[],
                "new_steps":[],
                "new_claims":"not-an-array",
                "detected_conflicts":[{"kind":"conflict"}],
                "normalization_notes":"replace-me"
              }
            }
            """);
    assertEquals(
        List.of("missing_final_answer_downgraded_to_partial"),
        StructuredPayloadNormalizer.normalize(turn, ContinuationTurn.class));
    assertEquals("submit_delta", turn.path("action").asText());
    assertTrue(turn.path("delta").path("ready_for_verification").asBoolean());
    assertEquals(1, turn.path("delta").path("remaining_subgoals").size());
    assertEquals(1, turn.path("delta").path("normalization_notes").size());

    ObjectNode unchanged =
        object(
            """
            {
              "proof_complete":false,
              "candidate_final_answer":"answer",
              "new_steps":"not-an-array",
              "new_claims":[null,{"proof_steps":"not-an-array"}]
            }
            """);
    assertTrue(StructuredPayloadNormalizer.normalize(unchanged, ProofDelta.class).isEmpty());
  }

  private static void assertAlias(String legacy, String canonical) {
    ObjectNode payload =
        object(
            "{\"selected_mechanism\":\""
                + legacy
                + "\",\"action\":\""
                + legacy
                + "\"}");
    assertEquals(
        1, StructuredPayloadNormalizer.normalize(payload, MetaStrategyDecision.class).size());
    assertEquals(canonical, payload.path("selected_mechanism").asText());
  }

  private static ObjectNode object(String json) {
    return (ObjectNode) ContractObjectMapper.parseTree(json);
  }
}
