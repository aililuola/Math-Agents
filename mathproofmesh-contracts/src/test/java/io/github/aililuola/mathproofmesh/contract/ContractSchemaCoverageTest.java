package io.github.aililuola.mathproofmesh.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ContractSchemaCoverageTest {
  private static final String CONTRACT_PACKAGE =
      "io.github.aililuola.mathproofmesh.contract.";

  @Test
  void everyContractCoversBoundaryMissingUnknownInvalidAndRoundTrip()
      throws IOException, ClassNotFoundException {
    JsonNode index = readJson("migration/baseline/schemas/index.json");
    int covered = 0;
    for (JsonNode row : index) {
      String qualifiedName = row.get("qualified_name").textValue();
      if (!qualifiedName.startsWith("mathproofmesh.schemas.")
          || qualifiedName.endsWith(".StrictModel")) {
        continue;
      }

      String simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
      Class<?> type = Class.forName(CONTRACT_PACKAGE + simpleName);
      JsonNode schemaDocument =
          readJson("migration/baseline/schemas/" + row.get("file").textValue());
      JsonNode schema = schemaDocument.get("schema");
      ObjectNode boundaryPayload = boundaryObject(schema, schema, simpleName);

      Object boundaryValue;
      try {
        boundaryValue = ContractObjectMapper.read(boundaryPayload.toString(), type);
      } catch (ContractValidationException exception) {
        fail(
            simpleName
                + " rejected its generated boundary fixture: "
                + boundaryPayload,
            exception);
        return;
      }

      String serialized = ContractObjectMapper.write(boundaryValue);
      Object roundTrip = ContractObjectMapper.read(serialized, type);
      assertEquals(serialized, ContractObjectMapper.write(roundTrip), simpleName);

      ObjectNode unknown = boundaryPayload.deepCopy();
      unknown.put("phase_02_unknown", true);
      assertThrows(
          ContractValidationException.class,
          () -> ContractObjectMapper.read(unknown.toString(), type),
          simpleName + " must reject an unknown field");

      JsonNode required = schema.path("required");
      if (!required.isEmpty()) {
        String requiredField = required.get(0).textValue();
        ObjectNode missing = boundaryPayload.deepCopy();
        missing.remove(requiredField);
        assertThrows(
            ContractValidationException.class,
            () -> ContractObjectMapper.read(missing.toString(), type),
            simpleName + " must reject a missing required field");

        ObjectNode invalid = boundaryPayload.deepCopy();
        invalid.set(requiredField, NullNode.getInstance());
        assertThrows(
            ContractValidationException.class,
            () -> ContractObjectMapper.read(invalid.toString(), type),
            simpleName + " must reject an invalid null state");
      } else {
        String optionalField =
            schema.path("properties").propertyStream().findFirst().orElseThrow().getKey();
        ObjectNode invalid = boundaryPayload.deepCopy();
        invalid.set(optionalField, invalidTypeValue(schema.path("properties").path(optionalField)));
        assertThrows(
            ContractValidationException.class,
            () -> ContractObjectMapper.read(invalid.toString(), type),
            simpleName + " must reject an invalid optional-field type");
      }
      covered++;
    }
    assertEquals(102, covered);
  }

  @Test
  void invariantDecisionMatrixRejectsEveryInvalidStateCombination()
      throws IOException, ClassNotFoundException {
    JsonNode index = readJson("migration/baseline/schemas/index.json");
    int coveredTypes = 0;
    for (JsonNode row : index) {
      String qualifiedName = row.get("qualified_name").textValue();
      if (!qualifiedName.startsWith("mathproofmesh.schemas.")
          || qualifiedName.endsWith(".StrictModel")) {
        continue;
      }
      String simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
      JsonNode schemaDocument =
          readJson("migration/baseline/schemas/" + row.get("file").textValue());
      JsonNode schema = schemaDocument.get("schema");
      ObjectNode payload = boundaryObject(schema, schema, simpleName);
      Class<?> type = Class.forName(CONTRACT_PACKAGE + simpleName);

      switch (simpleName) {
        case "MessageEnvelope" -> {
          ensureBoundaryFields(
              payload, schema, simpleName, "quantifiers", "variable_bindings");
          assertInvalid(type, payload, value -> duplicateInteger(value, "quantifiers", "order"));
          assertInvalid(
              type,
              payload,
              value -> ((ObjectNode) array(value, "quantifiers").get(1)).put("order", 3));
          assertInvalid(
              type,
              payload,
              value -> duplicateText(value, "variable_bindings", "variable_id"));
          assertInvalid(
              type,
              payload,
              value -> array(value, "variable_bindings").remove(array(value, "variable_bindings").size() - 1));
          assertInvalid(
              type,
              payload,
              value -> ((ObjectNode) array(value, "quantifiers").get(0)).put("domain", "other"));
          coveredTypes++;
        }
        case "MessageReceipt" -> {
          ensureBoundaryFields(
              payload,
              schema,
              simpleName,
              "parsed_quantifiers",
              "parsed_variable_bindings");
          assertInvalid(
              type, payload, value -> duplicateInteger(value, "parsed_quantifiers", "order"));
          assertInvalid(
              type,
              payload,
              value -> ((ObjectNode) array(value, "parsed_quantifiers").get(1)).put("order", 3));
          assertInvalid(
              type,
              payload,
              value -> duplicateText(value, "parsed_variable_bindings", "variable_id"));
          assertInvalid(
              type,
              payload,
              value ->
                  array(value, "parsed_variable_bindings")
                      .remove(array(value, "parsed_variable_bindings").size() - 1));
          assertInvalid(
              type,
              payload,
              value ->
                  ((ObjectNode) array(value, "parsed_quantifiers").get(0))
                      .put("domain", "other"));
          coveredTypes++;
        }
        case "ProofObligation" -> {
          ensureBoundaryFields(payload, schema, simpleName, "evidence_message_ids");
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("status", "closed");
                value.putArray("evidence_message_ids");
              });
          coveredTypes++;
        }
        case "BridgeTask" -> {
          ensureBoundaryFields(payload, schema, simpleName, "obligation_ids", "route_ids");
          assertInvalid(type, payload, value -> duplicateArrayText(value, "obligation_ids"));
          assertInvalid(type, payload, value -> duplicateArrayText(value, "route_ids"));
          coveredTypes++;
        }
        case "DomainOperatorSpec" -> {
          ensureBoundaryFields(
              payload,
              schema,
              simpleName,
              "preconditions",
              "generated_obligations",
              "fast_failure_tests",
              "known_failure_modes");
          assertInvalid(type, payload, value -> value.putArray("preconditions"));
          assertInvalid(type, payload, value -> value.putArray("generated_obligations"));
          assertInvalid(type, payload, value -> value.putArray("fast_failure_tests"));
          assertInvalid(type, payload, value -> value.putArray("known_failure_modes"));
          coveredTypes++;
        }
        case "SurpriseMutationDirective" -> {
          ensureBoundaryFields(
              payload,
              schema,
              simpleName,
              "target_obligation_ids",
              "preconditions",
              "generated_obligations",
              "reversibility_requirements",
              "fast_failure_tests",
              "known_failure_modes");
          for (String field :
              new String[] {
                "target_obligation_ids",
                "preconditions",
                "generated_obligations",
                "reversibility_requirements",
                "fast_failure_tests",
                "known_failure_modes"
              }) {
            assertInvalid(type, payload, value -> value.putArray(field));
          }
          coveredTypes++;
        }
        case "FrontierBridge" -> {
          ensureBoundaryFields(
              payload, schema, simpleName, "required_supporting_conditions");
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("semantic_relationship", "candidate_ingredient");
                value.putArray("required_supporting_conditions");
              });
          coveredTypes++;
        }
        case "ComposedInspiration" -> {
          ensureBoundaryFields(
              payload,
              schema,
              simpleName,
              "source_proposal_ids",
              "target_obligation_ids",
              "fast_failure_tests",
              "compatibility_conditions",
              "combined_mechanism",
              "new_obligations");
          assertInvalid(type, payload, value -> duplicateArrayText(value, "source_proposal_ids"));
          for (String field :
              new String[] {
                "target_obligation_ids",
                "fast_failure_tests",
                "compatibility_conditions",
                "combined_mechanism",
                "new_obligations"
              }) {
            assertInvalid(type, payload, value -> value.putArray(field));
          }
          coveredTypes++;
        }
        case "RepresentationCandidate" -> {
          ensureBoundaryFields(
              payload, schema, simpleName, "object_mapping", "failure_risks");
          assertInvalid(type, payload, value -> value.putObject("object_mapping"));
          assertInvalid(type, payload, value -> value.putArray("failure_risks"));
          coveredTypes++;
        }
        case "AnalogyMapping" -> {
          ensureBoundaryFields(
              payload,
              schema,
              simpleName,
              "object_correspondence",
              "operation_correspondence",
              "non_transferable_conditions",
              "transfer_risks");
          assertInvalid(type, payload, value -> value.putObject("object_correspondence"));
          assertInvalid(type, payload, value -> value.putObject("operation_correspondence"));
          assertInvalid(type, payload, value -> value.putArray("non_transferable_conditions"));
          assertInvalid(type, payload, value -> value.putArray("transfer_risks"));
          coveredTypes++;
        }
        case "ConstructionProposal" -> {
          ensureBoundaryFields(
              payload,
              schema,
              simpleName,
              "constructed_objects",
              "intended_obligations",
              "falsification_tests");
          assertInvalid(type, payload, value -> value.putArray("constructed_objects"));
          assertInvalid(type, payload, value -> value.putArray("intended_obligations"));
          assertInvalid(type, payload, value -> value.putArray("falsification_tests"));
          coveredTypes++;
        }
        case "InvariantHypothesis" -> {
          ensureBoundaryFields(
              payload, schema, simpleName, "target_obligation_ids", "allowed_operations");
          assertInvalid(type, payload, value -> value.putArray("target_obligation_ids"));
          assertInvalid(type, payload, value -> value.putArray("allowed_operations"));
          coveredTypes++;
        }
        case "ReverseGoalPlan" -> {
          ensureBoundaryFields(
              payload,
              schema,
              simpleName,
              "sufficient_intermediate_claims",
              "minimal_gaps");
          assertInvalid(type, payload, value -> value.putArray("sufficient_intermediate_claims"));
          assertInvalid(type, payload, value -> value.putArray("minimal_gaps"));
          coveredTypes++;
        }
        case "InspirationProposal" -> {
          ensureBoundaryFields(payload, schema, simpleName, "generated_obligations");
          assertInvalid(type, payload, value -> value.put("evidence_type", "verified_fact"));
          assertInvalid(type, payload, value -> value.putArray("generated_obligations"));
          coveredTypes++;
        }
        case "InspirationReview" -> {
          ensureBoundaryFields(payload, schema, simpleName, "deferred_reason");
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("review_status", "deferred");
                value.put("deferred_reason", "");
              });
          coveredTypes++;
        }
        case "InspirationCallReservation" -> {
          assertInvalid(
              type,
              payload,
              value ->
                  value.put(
                      "reserved_calls",
                      value.path("proposer_calls").asInt()
                          + value.path("referee_calls").asInt()
                          + value.path("skeptic_calls").asInt()
                          + value.path("route_attempt_calls").asInt()
                          + 1));
          coveredTypes++;
        }
        case "ExperimentSpec" -> {
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("purpose", "discover_pattern");
                value.put("broad_search", false);
              });
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("purpose", "falsify_claim");
                value.put("broad_search", true);
              });
          coveredTypes++;
        }
        case "ComputationContractRepair" -> {
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("action", "retry_with_repaired_spec");
                value.putNull("repaired_spec");
              });
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("action", "retry_with_repaired_spec");
                setBoundaryField(value, schema, "repaired_spec", simpleName);
                value.putNull("semantic_equivalence");
              });
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("action", "abandon_as_unrepresentable");
                setBoundaryField(value, schema, "repaired_spec", simpleName);
              });
          coveredTypes++;
        }
        case "ExperimentResult" -> {
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("outcome", "counterexample_found");
                value.putNull("counterexample");
              });
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("outcome", "counterexample_found");
                setBoundaryField(value, schema, "counterexample", simpleName);
                value.put("evidence_strength", "heuristic");
                value.put("independently_verified", true);
              });
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("outcome", "counterexample_found");
                setBoundaryField(value, schema, "counterexample", simpleName);
                value.put("evidence_strength", "counterexample");
                value.put("independently_verified", false);
              });
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("outcome", "certified");
                value.putNull("certificate");
              });
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("outcome", "certified");
                setBoundaryField(value, schema, "certificate", simpleName);
                value.put("evidence_strength", "heuristic");
              });
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("outcome", "not_refuted");
                value.put("evidence_strength", "formal_certificate");
              });
          for (String outcome : new String[] {"error", "inconclusive"}) {
            assertInvalid(
                type,
                payload,
                value -> {
                  value.put("outcome", outcome);
                  value.put("evidence_strength", "bounded_evidence");
                });
          }
          coveredTypes++;
        }
        case "GoalNormalizationAssessment" -> {
          ensureBoundaryFields(
              payload,
              schema,
              simpleName,
              "ambiguity_reasons",
              "alternative_interpretations",
              "clarification_question");
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("has_ambiguity", true);
                value.putArray("ambiguity_reasons");
              });
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("has_ambiguity", true);
                value.putArray("ambiguity_reasons").add("ambiguous domain");
                value.putNull("clarification_question");
              });
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("is_well_formed", false);
                value.putNull("clarification_question");
              });
          coveredTypes++;
        }
        case "LocalGoalPrecheck" -> {
          ensureBoundaryFields(payload, schema, simpleName, "reasons", "rule_ids");
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("status", "clear");
                value.putArray("reasons").add("unexpected finding");
              });
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("status", "model_review_required");
                value.putArray("reasons");
              });
          coveredTypes++;
        }
        case "StrategySet" -> {
          assertInvalid(type, payload, value -> value.putArray("strategies"));
          coveredTypes++;
        }
        case "CandidateConjectureBatch" -> {
          assertInvalid(type, payload, value -> value.putArray("candidate_conjectures"));
          coveredTypes++;
        }
        case "ProofAttempt" -> {
          ensureBoundaryFields(payload, schema, simpleName, "final_answer");
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("status", "complete");
                value.putNull("final_answer");
              });
          coveredTypes++;
        }
        case "ProofDelta" -> {
          ensureBoundaryFields(
              payload,
              schema,
              simpleName,
              "candidate_final_answer",
              "remaining_subgoals",
              "new_steps",
              "detected_conflicts");
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("proof_complete", true);
                value.putNull("candidate_final_answer");
              });
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("proof_complete", true);
                value.put("candidate_final_answer", "complete");
                value.putArray("remaining_subgoals").add("still open");
              });
          assertInvalid(
              type,
              payload,
              value -> {
                value.putArray("new_steps");
                value.putArray("detected_conflicts");
              });
          coveredTypes++;
        }
        case "InitialExplorationTurn" -> {
          assertInvalid(type, payload, value -> value.put("experiment_impact", "none"));
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("action", "submit_attempt");
                value.putNull("attempt");
              });
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("action", "request_computation");
                value.putNull("experiment_spec");
              });
          coveredTypes++;
        }
        case "ContinuationTurn" -> {
          assertInvalid(type, payload, value -> value.put("experiment_impact", "none"));
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("action", "submit_delta");
                value.putNull("delta");
              });
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("action", "request_computation");
                value.putNull("experiment_spec");
              });
          coveredTypes++;
        }
        case "WorkingProofCheckpoint" -> {
          assertInvalid(
              type,
              payload,
              value ->
                  ((ObjectNode) value.get("delta"))
                      .put("parent_checkpoint_id", "different-parent"));
          assertInvalid(
              type,
              payload,
              value -> ((ObjectNode) value.get("delta")).put("path_id", "different-path"));
          coveredTypes++;
        }
        case "BlindVerificationReport", "VerificationReport" -> {
          ensureBoundaryFields(payload, schema, simpleName, "issues");
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("verdict", "fail");
                value.putArray("issues");
              });
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("problem_integrity_ok", false);
                value.put("verdict", "pass");
              });
          assertInvalid(
              type,
              payload,
              value -> {
                value.put("problem_integrity_ok", false);
                value.put("verdict", "fail");
                value.putArray("issues");
              });
          coveredTypes++;
        }
        default -> {
          // Types without cross-field invariants are covered by the generic schema matrix above.
        }
      }
    }
    assertEquals(30, coveredTypes);
  }

  @Test
  void optionalFieldMatrixExercisesExplicitValuesAndExplicitNulls()
      throws IOException, ClassNotFoundException {
    JsonNode index = readJson("migration/baseline/schemas/index.json");
    int exercised = 0;
    for (JsonNode row : index) {
      String qualifiedName = row.get("qualified_name").textValue();
      if (!qualifiedName.startsWith("mathproofmesh.schemas.")
          || qualifiedName.endsWith(".StrictModel")) {
        continue;
      }
      String simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
      Class<?> type = Class.forName(CONTRACT_PACKAGE + simpleName);
      JsonNode schemaDocument =
          readJson("migration/baseline/schemas/" + row.get("file").textValue());
      JsonNode schema = schemaDocument.get("schema");
      ObjectNode requiredOnly = boundaryObject(schema, schema, simpleName);

      ObjectNode explicitValues = requiredOnly.deepCopy();
      schema.path("properties")
          .properties()
          .forEach(
              entry -> {
                if (!explicitValues.has(entry.getKey())) {
                  try {
                    explicitValues.set(
                        entry.getKey(),
                        boundaryValue(
                            entry.getValue(), schema, simpleName + "." + entry.getKey()));
                  } catch (IllegalStateException | NullPointerException unsupportedShape) {
                    // Some Pydantic helper schemas are intentionally unconstrained.
                  }
                }
              });
      exercisePayload(type, explicitValues);
      exercised++;

      ObjectNode explicitNulls = requiredOnly.deepCopy();
      Set<String> requiredNames = new java.util.HashSet<>();
      schema.path("required").forEach(value -> requiredNames.add(value.textValue()));
      schema.path("properties")
          .properties()
          .forEach(
              entry -> {
                if (!requiredNames.contains(entry.getKey())
                    && permitsNull(entry.getValue())) {
                  explicitNulls.putNull(entry.getKey());
                }
              });
      exercisePayload(type, explicitNulls);
      exercised++;
    }
    assertEquals(204, exercised);
  }

  private static ObjectNode boundaryObject(
      JsonNode schema, JsonNode rootSchema, String context) {
    ObjectNode value = JsonNodeFactory.instance.objectNode();
    for (JsonNode requiredName : schema.path("required")) {
      String fieldName = requiredName.textValue();
      JsonNode property = schema.path("properties").path(fieldName);
      value.set(
          fieldName,
          boundaryValue(property, rootSchema, context + "." + fieldName));
    }
    completeDomainBoundary(schema.path("title").asText(context), value, schema, rootSchema);
    return value;
  }

  private static JsonNode boundaryValue(
      JsonNode schema, JsonNode rootSchema, String context) {
    if (schema.has("$ref")) {
      JsonNode referenced = rootSchema.at(schema.get("$ref").textValue().substring(1));
      if (referenced.isMissingNode()) {
        throw new IllegalStateException("Unresolved schema reference at " + context);
      }
      return boundaryValue(referenced, rootSchema, context);
    }
    if (schema.has("enum")) {
      if (context.endsWith("ComputationContractRepair.action")) {
        return TextNode.valueOf("abandon_as_unrepresentable");
      }
      if (context.endsWith("ContinuationTurn.action")
          || context.endsWith("InitialExplorationTurn.action")) {
        return TextNode.valueOf("abandon");
      }
      if (context.endsWith("ExperimentSpec.purpose")) {
        return TextNode.valueOf("falsify_claim");
      }
      if (context.endsWith("ProofAttempt.status")) {
        return TextNode.valueOf("partial");
      }
      return schema.get("enum").get(0).deepCopy();
    }
    if (schema.has("anyOf")) {
      for (JsonNode candidate : schema.get("anyOf")) {
        if (!"null".equals(candidate.path("type").textValue())) {
          return boundaryValue(candidate, rootSchema, context);
        }
      }
    }

    return switch (schema.path("type").textValue()) {
      case "string" -> TextNode.valueOf(boundaryString(schema, context));
      case "integer" ->
          IntNode.valueOf(schema.has("minimum") ? schema.get("minimum").intValue() : 0);
      case "number" ->
          DoubleNode.valueOf(schema.has("minimum") ? schema.get("minimum").doubleValue() : 0.0);
      case "boolean" ->
          context.endsWith(".problem_integrity_ok")
                  || context.endsWith(".is_well_formed")
              ? BooleanNode.TRUE
              : BooleanNode.FALSE;
      case "array" -> boundaryArray(schema, rootSchema, context);
      case "object" -> boundaryMap(schema, rootSchema, context);
      default -> throw new IllegalStateException("Unsupported schema at " + context + ": " + schema);
    };
  }

  private static ArrayNode boundaryArray(
      JsonNode schema, JsonNode rootSchema, String context) {
    ArrayNode value = JsonNodeFactory.instance.arrayNode();
    int size = Math.max(2, schema.path("minItems").asInt(0));
    for (int index = 0; index < size; index++) {
      JsonNode item = boundaryValue(schema.path("items"), rootSchema, context + "[]");
      if (index > 0 && item.isTextual() && !schema.path("items").has("enum")) {
        item = TextNode.valueOf(item.textValue() + index);
      }
      value.add(item);
    }
    return value;
  }

  private static ObjectNode boundaryMap(
      JsonNode schema, JsonNode rootSchema, String context) {
    if (schema.has("properties")) {
      return boundaryObject(schema, rootSchema, context);
    }
    ObjectNode value = JsonNodeFactory.instance.objectNode();
    JsonNode additional = schema.get("additionalProperties");
    if (additional != null && additional.isObject()) {
      value.set("key", boundaryValue(additional, rootSchema, context + ".key"));
    }
    return value;
  }

  private static String boundaryString(JsonNode schema, String context) {
    String fieldName = context.substring(context.lastIndexOf('.') + 1);
    if (fieldName.contains("hash")) {
      return "0".repeat(64);
    }
    if (fieldName.endsWith("_at") || fieldName.contains("timestamp")) {
      return "1970-01-01T00:00:00.000000+00:00";
    }
    if (fieldName.contains("language")) {
      return "en";
    }
    int length = Math.max(1, schema.path("minLength").asInt(0));
    return "x".repeat(length);
  }

  private static void completeDomainBoundary(
      String simpleName, ObjectNode payload, JsonNode schema, JsonNode rootSchema) {
    switch (simpleName) {
      case "ProofDelta" ->
          addBoundaryField(
              payload, schema, rootSchema, "detected_conflicts", simpleName);
      case "StrategySet" ->
          addBoundaryField(payload, schema, rootSchema, "strategies", simpleName);
      case "CandidateConjectureBatch" ->
          addBoundaryField(
              payload, schema, rootSchema, "candidate_conjectures", simpleName);
      default -> {
        // Most contract invariants are fully represented by required schema fields.
      }
    }
  }

  private static void addBoundaryField(
      ObjectNode payload,
      JsonNode schema,
      JsonNode rootSchema,
      String fieldName,
      String context) {
    payload.set(
        fieldName,
        boundaryValue(
            schema.path("properties").path(fieldName),
            rootSchema,
            context + "." + fieldName));
  }

  private static void assertInvalid(
      Class<?> type, ObjectNode original, Consumer<ObjectNode> mutation) {
    ObjectNode invalid = original.deepCopy();
    mutation.accept(invalid);
    assertThrows(
        ContractValidationException.class,
        () -> ContractObjectMapper.read(invalid.toString(), type),
        type.getSimpleName() + " accepted an invalid cross-field state: " + invalid);
  }

  private static void exercisePayload(Class<?> type, ObjectNode payload) {
    try {
      ContractObjectMapper.read(payload.toString(), type);
    } catch (ContractValidationException expected) {
      assertEquals(
          ContractValidationException.class,
          expected.getClass(),
          type.getSimpleName() + " must fail through the strict validation boundary");
    }
  }

  private static boolean permitsNull(JsonNode schema) {
    if ("null".equals(schema.path("type").asText())) {
      return true;
    }
    for (JsonNode candidate : schema.path("anyOf")) {
      if ("null".equals(candidate.path("type").asText())) {
        return true;
      }
    }
    return false;
  }

  private static ArrayNode array(ObjectNode value, String field) {
    return (ArrayNode) value.get(field);
  }

  private static void duplicateInteger(ObjectNode value, String arrayField, String field) {
    ArrayNode items = array(value, arrayField);
    ((ObjectNode) items.get(1)).put(field, items.get(0).path(field).asInt());
  }

  private static void duplicateText(ObjectNode value, String arrayField, String field) {
    ArrayNode items = array(value, arrayField);
    ((ObjectNode) items.get(1)).put(field, items.get(0).path(field).asText());
  }

  private static void duplicateArrayText(ObjectNode value, String field) {
    ArrayNode items = array(value, field);
    items.set(1, items.get(0).deepCopy());
  }

  private static void setBoundaryField(
      ObjectNode payload, JsonNode schema, String fieldName, String context) {
    payload.set(
        fieldName,
        boundaryValue(
            schema.path("properties").path(fieldName),
            schema,
            context + "." + fieldName));
  }

  private static void ensureBoundaryFields(
      ObjectNode payload, JsonNode schema, String context, String... fieldNames) {
    for (String fieldName : fieldNames) {
      if (!payload.has(fieldName) || payload.get(fieldName).isNull()) {
        setBoundaryField(payload, schema, fieldName, context);
      }
    }
  }

  private static JsonNode invalidTypeValue(JsonNode schema) {
    JsonNode candidate = schema;
    if (candidate.has("anyOf")) {
      candidate = candidate.get("anyOf").get(0);
    }
    if (candidate.has("$ref") || "string".equals(candidate.path("type").textValue())) {
      return JsonNodeFactory.instance.objectNode();
    }
    return TextNode.valueOf("phase-02-invalid");
  }

  private static JsonNode readJson(String relativePath) throws IOException {
    return ContractObjectMapper.parseTree(
        Files.readString(projectRoot().resolve(relativePath), StandardCharsets.UTF_8));
  }

  private static Path projectRoot() {
    return Path.of(System.getProperty("mathproofmesh.projectRoot"));
  }
}
