package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class StructuredPayloadNormalizer {
  private static final Set<String> SERVER_OWNED_HASHES =
      Set.of(
          "content_hash",
          "normalized_hash",
          "request_hash",
          "code_hash",
          "result_hash",
          "semantic_hash");
  private static final Set<String> TOOL_REQUEST_KINDS =
      Set.of(
          "sympy_simplify",
          "sympy_equivalent",
          "numeric_counterexample",
          "polynomial_factor",
          "modular_exhaustive",
          "bounded_integer_search",
          "graph_certificate",
          "recurrence_check",
          "bounded_greedy_sequence",
          "candidate_period_check",
          "exact_geometry",
          "lean_check");
  private static final Set<String> TURN_ENVELOPE_METADATA =
      Set.of("agent_id", "path_id", "problem_hash", "strategy_id", "usage");

  private StructuredPayloadNormalizer() {}

  public static void stripServerOwnedHashes(JsonNode value) {
    if (value instanceof ObjectNode object) {
      object.remove("kind_migration");
      SERVER_OWNED_HASHES.forEach(
          field -> {
            if (object.has(field)) {
              object.put(field, "");
            }
          });
      object.properties().forEach(entry -> stripServerOwnedHashes(entry.getValue()));
    } else if (value instanceof ArrayNode array) {
      array.forEach(StructuredPayloadNormalizer::stripServerOwnedHashes);
    }
  }

  public static List<String> normalize(ObjectNode payload, Class<?> responseModel) {
    String modelName = responseModel.getSimpleName();
    List<String> actions = normalizeDependencyRefKinds(payload);
    normalizeQuantifierKinds(payload, "$", actions);
    normalizeTurnEnvelopeMetadata(payload, modelName, actions);
    normalizeAttemptClaimContextBindings(payload, "$", actions);
    normalizeOptionalCandidateConjectures(payload, modelName, actions);
    if ("StrategySet".equals(modelName)) {
      normalizeOptionalStrategyCalculationChecks(payload, actions);
    }
    if ("ToolAuditReport".equals(modelName)) {
      normalizeToolAuditVerdict(payload, actions);
    }
    if ("MetaStrategyDecision".equals(modelName)) {
      String selected = textOrNull(payload.get("selected_mechanism"));
      String action = textOrNull(payload.get("action"));
      String canonical =
          switch (selected == null ? "" : selected) {
            case "invent_auxiliary_construction" -> "auxiliary_construction";
            case "search_analogy" -> "structural_analogy";
            case "switch_representation" -> "representation_switch";
            case "rewrite_plan" -> "meta_replan";
            default -> null;
          };
      if (canonical != null && selected.equals(action)) {
        payload.put("selected_mechanism", canonical);
        actions.add(
            "canonicalized selected_mechanism " + selected + " to " + canonical);
      }
      return List.copyOf(actions);
    }

    boolean computationTurn =
        ("ContinuationTurn".equals(modelName) || "InitialExplorationTurn".equals(modelName))
            && "request_computation".equals(textOrNull(payload.get("action")));
    if (computationTurn) {
      JsonNode impact = payload.get("experiment_impact");
      if (impact != null && !impact.isNull()) {
        payload.putNull("experiment_impact");
        actions.add("cleared premature experiment_impact for request_computation");
      }
      JsonNode rawSpec = payload.get("experiment_spec");
      if (rawSpec instanceof ObjectNode spec) {
        if ("discover_pattern".equals(textOrNull(spec.get("purpose")))
            && !spec.path("broad_search").asBoolean(false)) {
          spec.put("broad_search", true);
          actions.add("marked discover_pattern computation as broad_search");
        }
        normalizeComputationCaseBound(spec, actions);
      }
    }

    if (!"ContinuationTurn".equals(modelName) && !"ProofDelta".equals(modelName)) {
      return List.copyOf(actions);
    }
    ObjectNode delta =
        "ContinuationTurn".equals(modelName)
                && payload.get("delta") instanceof ObjectNode nestedDelta
            ? nestedDelta
            : payload;
    normalizeStepReferences(delta, actions);
    downgradeMissingFinalAnswer(payload, delta, "ContinuationTurn".equals(modelName), actions);
    return List.copyOf(actions);
  }

  private static void normalizeQuantifierKinds(
      JsonNode value, String path, List<String> actions) {
    if (value instanceof ObjectNode object) {
      normalizeRedundantLocalLetDeclarations(object, path, actions);
      JsonNode rawQuantifiers = object.get("quantifiers");
      if (rawQuantifiers instanceof ArrayNode quantifiers) {
        for (int index = 0; index < quantifiers.size(); index++) {
          if (!(quantifiers.get(index) instanceof ObjectNode quantifier)) {
            continue;
          }
          String kind = textOrNull(quantifier.get("kind"));
          String canonical =
              switch (kind == null ? "" : kind) {
                case "universal" -> "forall";
                case "existential" -> "exists";
                case "unique" -> "exists_unique";
                default -> null;
              };
          if (canonical != null) {
            quantifier.put("kind", canonical);
            actions.add(
                "canonicalized "
                    + path
                    + ".quantifiers["
                    + index
                    + "] quantifier kind "
                    + kind
                    + " to "
                    + canonical);
          }
        }
      }
      object.properties()
          .forEach(
              entry ->
                  normalizeQuantifierKinds(
                      entry.getValue(), path + "." + entry.getKey(), actions));
    } else if (value instanceof ArrayNode array) {
      for (int index = 0; index < array.size(); index++) {
        normalizeQuantifierKinds(array.get(index), path + "[" + index + "]", actions);
      }
    }
  }

  private static void normalizeRedundantLocalLetDeclarations(
      ObjectNode object, String path, List<String> actions) {
    if (textOrNull(object.get("claim_id")) == null
        || !(object.get("quantifiers") instanceof ArrayNode quantifiers)
        || !(object.get("variable_bindings") instanceof ArrayNode bindings)
        || !(object.get("local_assumptions") instanceof ArrayNode assumptions)) {
      return;
    }
    Set<String> existingAssumptions = new HashSet<>();
    assumptions.forEach(
        assumption -> {
          String text = textOrNull(assumption);
          if (text != null) {
            existingAssumptions.add(text.strip());
          }
        });
    for (int index = quantifiers.size() - 1; index >= 0; index--) {
      if (!(quantifiers.get(index) instanceof ObjectNode quantifier)
          || !"let".equals(textOrNull(quantifier.get("kind")))
          || !hasUniqueMatchingLocalBinding(quantifier, bindings)
          || !(quantifier.get("restrictions") instanceof ArrayNode restrictions)
          || restrictions.isEmpty()
          || !allNonBlankText(restrictions)) {
        continue;
      }
      for (JsonNode restriction : restrictions) {
        String text = restriction.textValue().strip();
        if (existingAssumptions.add(text)) {
          assumptions.add(text);
        }
      }
      String variableId = textOrNull(quantifier.get("variable_id"));
      quantifiers.remove(index);
      actions.add(
          "removed redundant "
              + path
              + ".quantifiers["
              + index
              + "] local let declaration for "
              + variableId);
    }
  }

  private static boolean hasUniqueMatchingLocalBinding(
      ObjectNode quantifier, ArrayNode bindings) {
    String variableId = textOrNull(quantifier.get("variable_id"));
    String displayName = textOrNull(quantifier.get("display_name"));
    String domain = textOrNull(quantifier.get("domain"));
    if (variableId == null || displayName == null || domain == null) {
      return false;
    }
    int matches = 0;
    for (JsonNode rawBinding : bindings) {
      if (!(rawBinding instanceof ObjectNode binding)
          || !sameText(variableId, textOrNull(binding.get("variable_id")))) {
        continue;
      }
      if (!"claim_local".equals(textOrNull(binding.get("owner_scope")))
          || !sameText(displayName, textOrNull(binding.get("display_name")))
          || !sameText(domain, textOrNull(binding.get("domain")))) {
        return false;
      }
      matches++;
    }
    return matches == 1;
  }

  private static boolean allNonBlankText(ArrayNode values) {
    for (JsonNode value : values) {
      if (!value.isTextual() || value.textValue().isBlank()) {
        return false;
      }
    }
    return true;
  }

  private static boolean sameText(String left, String right) {
    return left != null && right != null && left.strip().equals(right.strip());
  }

  private static void normalizeTurnEnvelopeMetadata(
      ObjectNode payload, String modelName, List<String> actions) {
    if (!"InitialExplorationTurn".equals(modelName)
        && !"ContinuationTurn".equals(modelName)) {
      return;
    }
    for (String field : TURN_ENVELOPE_METADATA) {
      if (payload.remove(field) != null) {
        actions.add("removed turn envelope metadata field " + field);
      }
    }
  }

  private static void normalizeAttemptClaimContextBindings(
      JsonNode value, String path, List<String> actions) {
    if (value instanceof ObjectNode object) {
      JsonNode rawBindings = object.get("claim_semantic_context_bindings");
      if (rawBindings instanceof ArrayNode bindings) {
        Set<String> proposedClaimIds = new HashSet<>();
        JsonNode rawProposed = object.get("proposed_lemmas");
        if (rawProposed instanceof ArrayNode proposed) {
          for (JsonNode claim : proposed) {
            if (claim instanceof ObjectNode candidate) {
              String claimId = textOrNull(candidate.get("claim_id"));
              if (claimId != null) {
                proposedClaimIds.add(claimId);
              }
            }
          }
        }
        for (int index = bindings.size() - 1; index >= 0; index--) {
          if (!(bindings.get(index) instanceof ObjectNode binding)) {
            continue;
          }
          String claimId = textOrNull(binding.get("claim_id"));
          if (claimId == null || proposedClaimIds.contains(claimId)) {
            continue;
          }
          bindings.remove(index);
          actions.add(
              "dropped "
                  + path
                  + ".claim_semantic_context_bindings["
                  + index
                  + "] for "
                  + claimId
                  + " because it is not an attempt-local proposed lemma");
        }
      }
      object.properties()
          .forEach(
              entry ->
                  normalizeAttemptClaimContextBindings(
                      entry.getValue(), path + "." + entry.getKey(), actions));
    } else if (value instanceof ArrayNode array) {
      for (int index = 0; index < array.size(); index++) {
        normalizeAttemptClaimContextBindings(
            array.get(index), path + "[" + index + "]", actions);
      }
    }
  }

  private static void normalizeComputationCaseBound(
      ObjectNode spec, List<String> actions) {
    JsonNode rawMaximum = spec.get("max_cases");
    if (rawMaximum == null || !rawMaximum.isIntegralNumber()) {
      return;
    }
    long maximum = rawMaximum.longValue();
    if (maximum < 1L) {
      spec.put("max_cases", 1);
      actions.add("raised computation max_cases to the minimum bound of 1");
    } else if (maximum > 100_000_000L) {
      spec.put("max_cases", 100_000_000);
      actions.add("lowered computation max_cases to the maximum bound of 100000000");
    }
  }

  private static void normalizeToolAuditVerdict(
      ObjectNode payload, List<String> actions) {
    String verdict = textOrNull(payload.get("verdict"));
    String canonical =
        switch (verdict == null ? "" : verdict) {
          case "reject", "rejected" -> "fail";
          case "accept", "accepted", "deterministic_replay_success" -> "pass";
          case "unverifiable" -> "inconclusive";
          default -> null;
        };
    if (canonical != null) {
      payload.put("verdict", canonical);
      actions.add("canonicalized tool-audit verdict " + verdict + " to " + canonical);
    }
  }

  private static void normalizeOptionalCandidateConjectures(
      ObjectNode payload, String modelName, List<String> actions) {
    JsonNode rawCandidates =
        switch (modelName) {
          case "InitialExplorationTurn" -> payload.path("attempt").path("candidate_conjectures");
          case "ContinuationTurn" -> payload.path("delta").path("candidate_conjectures");
          case "ProofDelta" -> payload.path("candidate_conjectures");
          default -> null;
        };
    if (!(rawCandidates instanceof ArrayNode candidates)) {
      return;
    }
    for (int index = candidates.size() - 1; index >= 0; index--) {
      if (!(candidates.get(index) instanceof ObjectNode candidate)
          || !nonEmptyArray(candidate.get("supporting_experiment_ids"))
          || !nonEmptyArray(candidate.get("scope_limitations"))
          || !nonEmptyArray(candidate.get("proof_obligations"))) {
        candidates.remove(index);
        actions.add(
            "dropped unsupported candidate_conjectures["
                + index
                + "] without auditable evidence and a separate proof obligation");
        continue;
      }
      String status = textOrNull(candidate.get("status"));
      if (status != null && !"candidate".equals(status)) {
        candidate.put("status", "candidate");
        actions.add(
            "downgraded candidate_conjectures[" + index + "].status to candidate");
      }
    }
  }

  private static void normalizeOptionalStrategyCalculationChecks(
      ObjectNode payload, List<String> actions) {
    if (!(payload.get("strategies") instanceof ArrayNode strategies)) {
      return;
    }
    for (int strategyIndex = 0; strategyIndex < strategies.size(); strategyIndex++) {
      if (!(strategies.get(strategyIndex) instanceof ObjectNode strategy)
          || !(strategy.get("calculation_checks") instanceof ArrayNode checks)) {
        continue;
      }
      for (int checkIndex = checks.size() - 1; checkIndex >= 0; checkIndex--) {
        JsonNode rawCheck = checks.get(checkIndex);
        String kind =
            rawCheck instanceof ObjectNode check ? textOrNull(check.get("kind")) : null;
        if (kind != null && TOOL_REQUEST_KINDS.contains(kind) && validToolRequest(rawCheck)) {
          continue;
        }
        checks.remove(checkIndex);
        actions.add(
            "dropped strategies["
                + strategyIndex
                + "].calculation_checks["
                + checkIndex
                + "] because it is not a valid typed ToolRequest");
      }
    }
  }

  private static boolean validToolRequest(JsonNode value) {
    if (!(value instanceof ObjectNode)) {
      return false;
    }
    try {
      ContractObjectMapper.read(value, ToolRequest.class);
      return true;
    } catch (ContractValidationException ignored) {
      return false;
    }
  }

  private static boolean nonEmptyArray(JsonNode value) {
    return value instanceof ArrayNode array && !array.isEmpty();
  }

  private static List<String> normalizeDependencyRefKinds(ObjectNode payload) {
    Set<String> localStepIds = new HashSet<>();
    Set<String> localClaimIds = new HashSet<>();
    collectIds(payload, localStepIds, localClaimIds);
    List<String> actions = new ArrayList<>();
    normalizeRefs(payload, "$", localStepIds, localClaimIds, actions);
    return actions;
  }

  private static void collectIds(
      JsonNode value, Set<String> localStepIds, Set<String> localClaimIds) {
    if (value instanceof ObjectNode object) {
      addText(object.get("step_id"), localStepIds);
      addText(object.get("claim_id"), localClaimIds);
      object.properties()
          .forEach(entry -> collectIds(entry.getValue(), localStepIds, localClaimIds));
    } else if (value instanceof ArrayNode array) {
      array.forEach(item -> collectIds(item, localStepIds, localClaimIds));
    }
  }

  private static void normalizeRefs(
      JsonNode value,
      String path,
      Set<String> localStepIds,
      Set<String> localClaimIds,
      List<String> actions) {
    if (value instanceof ObjectNode object) {
      JsonNode refs = object.get("dependency_refs");
      if (refs instanceof ArrayNode array) {
        for (int index = 0; index < array.size(); index++) {
          if (!(array.get(index) instanceof ObjectNode ref)
              || !"external".equals(textOrNull(ref.get("kind")))) {
            continue;
          }
          String target = textOrNull(ref.get("target_id"));
          boolean step = target != null && localStepIds.contains(target);
          boolean claim = target != null && localClaimIds.contains(target);
          String canonical = step && !claim ? "local_step" : claim && !step ? "local_claim" : "external_result";
          String migration = "legacy_external_to_" + canonical;
          ref.put("kind", canonical);
          ref.put("kind_migration", migration);
          actions.add(
              "canonicalized "
                  + path
                  + ".dependency_refs["
                  + index
                  + "].kind external to "
                  + canonical);
        }
      }
      object.properties()
          .forEach(
              entry ->
                  normalizeRefs(
                      entry.getValue(),
                      path + "." + entry.getKey(),
                      localStepIds,
                      localClaimIds,
                      actions));
    } else if (value instanceof ArrayNode array) {
      for (int index = 0; index < array.size(); index++) {
        normalizeRefs(
            array.get(index),
            path + "[" + index + "]",
            localStepIds,
            localClaimIds,
            actions);
      }
    }
  }

  private static void normalizeStepReferences(ObjectNode delta, List<String> actions) {
    ObjectNode stepsById = delta.objectNode();
    JsonNode rawSteps = delta.get("new_steps");
    if (rawSteps instanceof ArrayNode steps) {
      for (JsonNode step : steps) {
        if (step instanceof ObjectNode object) {
          String id = textOrNull(object.get("step_id"));
          if (id != null) {
            stepsById.set(id, object);
          }
        }
      }
    }
    JsonNode rawClaims = delta.get("new_claims");
    if (!(rawClaims instanceof ArrayNode claims)) {
      return;
    }
    for (int claimIndex = 0; claimIndex < claims.size(); claimIndex++) {
      if (!(claims.get(claimIndex) instanceof ObjectNode claim)
          || !(claim.get("proof_steps") instanceof ArrayNode proofSteps)) {
        continue;
      }
      ArrayNode normalized = claim.arrayNode();
      for (JsonNode proofStep : proofSteps) {
        if (proofStep.isTextual() && stepsById.has(proofStep.textValue())) {
          normalized.add(stepsById.get(proofStep.textValue()).deepCopy());
          actions.add(
              "expanded new_claims["
                  + claimIndex
                  + "].proof_steps reference '"
                  + proofStep.textValue()
                  + "'");
        } else {
          normalized.add(proofStep);
        }
      }
      claim.set("proof_steps", normalized);
    }
  }

  private static void downgradeMissingFinalAnswer(
      ObjectNode payload, ObjectNode delta, boolean turn, List<String> actions) {
    String answer = textOrNull(delta.get("candidate_final_answer"));
    if (!delta.path("proof_complete").asBoolean(false)
        || (answer != null && !answer.isBlank())) {
      return;
    }
    delta.put("proof_complete", false);
    if (turn && "complete".equals(textOrNull(payload.get("action")))) {
      payload.put("action", "submit_delta");
    }
    String recoveryGoal =
        "Assemble a self-contained candidate final answer from the independently reviewed proof prefix.";
    JsonNode remaining = delta.get("remaining_subgoals");
    if (remaining == null || remaining.isNull()) {
      delta.putArray("remaining_subgoals").add(recoveryGoal);
    } else if (remaining instanceof ArrayNode array && array.isEmpty()) {
      array.add(recoveryGoal);
    }
    if (textOrNull(delta.get("current_goal")) == null) {
      delta.put("current_goal", recoveryGoal);
    }
    if (delta.path("new_steps").size() > 0 || delta.path("detected_conflicts").size() > 0) {
      delta.put("ready_for_verification", true);
    }
    ArrayNode notes =
        delta.get("normalization_notes") instanceof ArrayNode existing
            ? existing
            : delta.putArray("normalization_notes");
    boolean present = false;
    for (JsonNode note : notes) {
      present |= "missing_final_answer_downgraded_to_partial".equals(textOrNull(note));
    }
    if (!present) {
      notes.add("missing_final_answer_downgraded_to_partial");
    }
    actions.add("missing_final_answer_downgraded_to_partial");
  }

  private static void addText(JsonNode value, Set<String> output) {
    String text = textOrNull(value);
    if (text != null && !text.isEmpty()) {
      output.add(text);
    }
  }

  private static String textOrNull(JsonNode value) {
    return value == null || value.isNull() || !value.isTextual() ? null : value.textValue();
  }
}
