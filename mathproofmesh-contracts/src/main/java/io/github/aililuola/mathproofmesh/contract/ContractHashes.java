package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

final class ContractHashes {
  private ContractHashes() {}

  static String messageContentHash(
      String problemHash,
      String sourceRouteId,
      MessageType messageType,
      String normalizedStatement,
      List<String> assumptions,
      String conclusion,
      List<QuantifierSpec> quantifiers,
      List<String> dependencies,
      EvidenceType evidenceType,
      MemoryTier memoryTier) {
    ObjectNode payload = object();
    payload.put("problem_hash", problemHash);
    payload.put("source_route_id", sourceRouteId);
    payload.put("message_type", messageType.value());
    payload.put("normalized_statement", normalizedStatement);
    payload.set("assumptions", tree(assumptions));
    payload.put("conclusion", conclusion);
    payload.set("quantifiers", tree(quantifiers));
    payload.set("dependencies", tree(dependencies));
    payload.put("evidence_type", evidenceType.value());
    payload.put("memory_tier", memoryTier.value());
    return CanonicalJson.stableHash(payload);
  }

  static String claimBoundMessageContentHash(
      String problemHash,
      String sourceRouteId,
      MessageType messageType,
      String normalizedStatement,
      List<String> assumptions,
      String conclusion,
      List<QuantifierSpec> quantifiers,
      List<String> dependencies,
      EvidenceType evidenceType,
      MemoryTier memoryTier,
      List<VariableBinding> variableBindings,
      List<String> scopeLimitations,
      String claimStatementHash,
      String claimSemanticHash,
      String polarity) {
    ObjectNode payload = object();
    payload.put("problem_hash", problemHash);
    payload.put("source_route_id", sourceRouteId);
    payload.put("message_type", messageType.value());
    payload.put("normalized_statement", normalizedStatement);
    payload.set("assumptions", tree(assumptions));
    payload.put("conclusion", conclusion);
    payload.set("quantifiers", tree(quantifiers));
    payload.set("dependencies", tree(dependencies));
    payload.put("evidence_type", evidenceType.value());
    payload.put("memory_tier", memoryTier.value());
    payload.set("variable_bindings", tree(variableBindings));
    payload.set("scope_limitations", tree(scopeLimitations));
    payload.put("claim_statement_hash", claimStatementHash);
    payload.put("claim_semantic_hash", claimSemanticHash);
    payload.put("polarity", polarity);
    return CanonicalJson.stableHash(payload);
  }

  static String messageSemanticHash(
      List<String> assumptions,
      String conclusion,
      List<QuantifierSpec> quantifiers,
      List<VariableBinding> variableBindings) {
    ObjectNode payload = object();
    payload.set("assumptions", tree(assumptions));
    payload.put("conclusion", conclusion);
    payload.set("quantifiers", tree(quantifiers));
    payload.set("variable_bindings", tree(variableBindings));
    return CanonicalJson.stableHash(payload);
  }

  static String claimHash(
      String statement, List<String> assumptions, String conclusion, List<String> dependencies) {
    ObjectNode payload = object();
    payload.put("statement", statement);
    payload.set("assumptions", tree(assumptions));
    payload.put("conclusion", conclusion);
    payload.set("dependencies", tree(dependencies));
    return CanonicalJson.stableHash(payload);
  }

  static String candidateConjectureHash(
      String statement,
      List<String> supportingExperimentIds,
      List<String> scopeLimitations,
      List<String> proofObligations) {
    ObjectNode payload = object();
    payload.put("statement", statement);
    payload.set("supporting_experiment_ids", tree(supportingExperimentIds));
    payload.set("scope_limitations", tree(scopeLimitations));
    payload.set("proof_obligations", tree(proofObligations));
    return CanonicalJson.stableHash(payload);
  }

  static String checkpointHash(
      String parentCheckpointId,
      String problemHash,
      String pathId,
      String strategyId,
      Integer segmentIndex,
      List<ProofStep> verifiedSteps,
      List<String> verifiedClaimIds,
      List<String> activeAssumptions,
      List<String> remainingSubgoals,
      String currentGoal,
      List<String> knownRisks,
      String finalAnswer,
      Boolean proofComplete) {
    ObjectNode payload = object();
    putNullable(payload, "parent_checkpoint_id", parentCheckpointId);
    payload.put("problem_hash", problemHash);
    payload.put("path_id", pathId);
    payload.put("strategy_id", strategyId);
    payload.put("segment_index", segmentIndex);
    payload.set(
        "verified_steps",
        tree(verifiedSteps.stream().map(ContractHashes::proofStepCheckpointPayload).toList()));
    payload.set("verified_claim_ids", tree(verifiedClaimIds));
    payload.set("active_assumptions", tree(activeAssumptions));
    payload.set("remaining_subgoals", tree(remainingSubgoals));
    putNullable(payload, "current_goal", currentGoal);
    payload.set("known_risks", tree(knownRisks));
    putNullable(payload, "final_answer", finalAnswer);
    payload.put("proof_complete", proofComplete);
    return CanonicalJson.stableHash(payload);
  }

  static ObjectNode proofStepCheckpointPayload(ProofStep step) {
    ObjectNode payload = (ObjectNode) ContractObjectMapper.toTree(step);
    payload.remove("dependency_refs");
    if (step.calculationChecks().isEmpty()) {
      payload.remove("calculation_checks");
    }
    if (step.calculationEvidenceRefs().isEmpty()) {
      payload.remove("calculation_evidence_refs");
    }
    return payload;
  }

  static String proofObligationHash(
      String problemHash,
      String normalizedStatement,
      List<String> assumptions,
      List<QuantifierSpec> quantifiers,
      ObligationKind kind) {
    ObjectNode payload = object();
    payload.put("problem_hash", problemHash);
    payload.put("normalized_statement", normalizedStatement);
    payload.set("assumptions", tree(assumptions));
    payload.set("quantifiers", tree(quantifiers));
    payload.put("kind", kind.value());
    return CanonicalJson.stableHash(payload);
  }

  static ObjectNode noveltyPayload(
      List<String> representationTags,
      List<String> mechanismTags,
      List<String> coreObjects,
      List<String> keyTransformations,
      List<String> proofPrinciples,
      List<String> targetedObligationIds,
      List<String> extensionTags) {
    ObjectNode payload = object();
    payload.set("representation_tags", tree(sortedUnique(representationTags)));
    payload.set("mechanism_tags", tree(sortedUnique(mechanismTags)));
    payload.set("core_objects", tree(sortedUnique(coreObjects)));
    payload.set("key_transformations", tree(sortedUnique(keyTransformations)));
    payload.set("proof_principles", tree(sortedUnique(proofPrinciples)));
    payload.set("targeted_obligation_ids", tree(sortedUnique(targetedObligationIds)));
    if (!extensionTags.isEmpty()) {
      payload.set("extension_tags", tree(sortedUnique(extensionTags)));
    }
    return payload;
  }

  static ObjectNode mechanismChainPayload(
      List<String> representation,
      List<String> transformations,
      List<String> bridgePattern,
      List<String> terminalArgument) {
    ObjectNode payload = object();
    payload.set("representation", tree(normalizedMechanismValues(representation)));
    payload.set("transformations", tree(normalizedMechanismValues(transformations)));
    payload.set("bridge_pattern", tree(normalizedMechanismValues(bridgePattern)));
    payload.set("terminal_argument", tree(normalizedMechanismValues(terminalArgument)));
    return payload;
  }

  static ObjectNode experimentExecutionPayload(
      ComputationMethod method,
      ObjectNode domains,
      ObjectNode suppliedArguments,
      Boolean exactArithmetic,
      ObjectNode runtimeFingerprint,
      Integer seed) {
    ObjectNode arguments = suppliedArguments.deepCopy();
    if (method == ComputationMethod.BOUNDED_GREEDY_SEQUENCE) {
      putDefault(arguments, "candidate_min", 0);
      putDefault(arguments, "candidate_max", 1_000_000);
      if (!arguments.has("strictly_increasing")) {
        arguments.put("strictly_increasing", true);
      }
    } else if (method == ComputationMethod.CANDIDATE_PERIOD_CHECK) {
      putDefault(arguments, "start_index", 0);
    } else if (method == ComputationMethod.RECURRENCE_CHECK) {
      putDefault(arguments, "start_n", 0);
      putDefault(arguments, "inhomogeneous", 0);
    } else if (method == ComputationMethod.NUMERIC_COUNTEREXAMPLE
        && !arguments.has("relation")) {
      arguments.put("relation", "eq");
    }

    ObjectNode runtime = runtimeFingerprint.deepCopy();
    runtime.remove("seed");
    ObjectNode payload = object();
    payload.put("method", method.value());
    payload.set("domains", domains.deepCopy());
    payload.set("arguments", arguments);
    payload.put("exact_arithmetic", exactArithmetic);
    payload.set("runtime_fingerprint", runtime);
    if (method == ComputationMethod.NUMERIC_COUNTEREXAMPLE
        || method == ComputationMethod.SANDBOXED_PYTHON) {
      payload.put("seed", seed);
    }
    return payload;
  }

  static ObjectNode experimentRequestPayload(
      ComputationPurpose purpose,
      String targetClaim,
      List<String> assumptions,
      String reasoningBasis,
      String whyComputationIsNeeded,
      String decisionIfConfirmed,
      String decisionIfRefuted,
      String noncomputationalAlternative,
      ComputationMethod method,
      ObjectNode domains,
      ObjectNode arguments,
      Boolean exactArithmetic,
      Boolean broadSearch,
      String typedToolGap,
      Integer maxCases,
      Integer seed,
      ObjectNode runtimeFingerprint) {
    ObjectNode payload = object();
    payload.put("purpose", purpose.value());
    payload.put("target_claim", targetClaim);
    payload.set("assumptions", tree(assumptions));
    payload.put("reasoning_basis", reasoningBasis);
    payload.put("why_computation_is_needed", whyComputationIsNeeded);
    payload.put("decision_if_confirmed", decisionIfConfirmed);
    payload.put("decision_if_refuted", decisionIfRefuted);
    payload.put("noncomputational_alternative", noncomputationalAlternative);
    payload.put("method", method.value());
    payload.set("domains", domains.deepCopy());
    payload.set("arguments", arguments.deepCopy());
    payload.put("exact_arithmetic", exactArithmetic);
    payload.put("broad_search", broadSearch);
    putNullable(payload, "typed_tool_gap", typedToolGap);
    payload.put("max_cases", maxCases);
    payload.put("seed", seed);
    payload.set("runtime_fingerprint", runtimeFingerprint.deepCopy());
    return payload;
  }

  static String experimentResultHash(
      String requestHash,
      String targetClaim,
      ComputationMethod method,
      ExperimentOutcome outcome,
      EvidenceStrength evidenceStrength,
      ObjectNode scope,
      ObjectNode counterexample,
      ObjectNode certificate,
      Boolean exactArithmetic,
      Integer casesChecked,
      String toolName,
      String toolVersion,
      String programHash,
      Boolean independentlyVerified,
      List<String> verificationNotes,
      String error) {
    return CanonicalJson.stableHash(
        experimentResultPayload(
            requestHash,
            targetClaim,
            method,
            outcome,
            evidenceStrength,
            scope,
            counterexample,
            certificate,
            exactArithmetic,
            casesChecked,
            toolName,
            toolVersion,
            programHash,
            independentlyVerified,
            verificationNotes,
            error));
  }

  static ObjectNode experimentResultPayload(
      String requestHash,
      String targetClaim,
      ComputationMethod method,
      ExperimentOutcome outcome,
      EvidenceStrength evidenceStrength,
      ObjectNode scope,
      ObjectNode counterexample,
      ObjectNode certificate,
      Boolean exactArithmetic,
      Integer casesChecked,
      String toolName,
      String toolVersion,
      String programHash,
      Boolean independentlyVerified,
      List<String> verificationNotes,
      String error) {
    ObjectNode payload = object();
    payload.put("request_hash", requestHash);
    payload.put("target_claim", targetClaim);
    payload.put("method", method.value());
    payload.put("outcome", outcome.value());
    payload.put("evidence_strength", evidenceStrength.value());
    payload.set("scope", scope.deepCopy());
    putNullable(payload, "counterexample", counterexample);
    putNullable(payload, "certificate", certificate);
    payload.put("exact_arithmetic", exactArithmetic);
    payload.put("cases_checked", casesChecked);
    payload.put("tool_name", toolName);
    payload.put("tool_version", toolVersion);
    putNullable(payload, "program_hash", programHash);
    payload.put("independently_verified", independentlyVerified);
    payload.set("verification_notes", tree(verificationNotes));
    putNullable(payload, "error", error);
    return payload;
  }

  static String criticalClaimId(String title, String bottleneck) {
    ObjectNode payload = object();
    payload.put("title", title);
    payload.put("bottleneck", bottleneck);
    return "critical_" + CanonicalJson.stableHash(payload).substring(0, 16);
  }

  static String checked(String field, String supplied, String expected) {
    if (supplied != null && !supplied.isEmpty() && !sameHash(supplied, expected)) {
      throw new ContractValidationException(field + " mismatch");
    }
    return expected;
  }

  static boolean sameHash(String supplied, String expected) {
    return supplied != null
        && MessageDigest.isEqual(
            supplied.getBytes(StandardCharsets.UTF_8),
            expected.getBytes(StandardCharsets.UTF_8));
  }

  private static ObjectNode object() {
    return JsonNodeFactory.instance.objectNode();
  }

  private static JsonNode tree(Object value) {
    return ContractObjectMapper.toTree(value);
  }

  private static void putNullable(ObjectNode target, String name, String value) {
    if (value == null) {
      target.putNull(name);
    } else {
      target.put(name, value);
    }
  }

  private static void putNullable(ObjectNode target, String name, JsonNode value) {
    if (value == null) {
      target.putNull(name);
    } else {
      target.set(name, value.deepCopy());
    }
  }

  private static List<String> sortedUnique(List<String> values) {
    Set<String> unique = new TreeSet<>(CanonicalJson.unicodeCodePointOrder());
    unique.addAll(values);
    return List.copyOf(unique);
  }

  private static List<String> normalizedMechanismValues(List<String> values) {
    Set<String> normalized = new LinkedHashSet<>();
    for (String value : values) {
      if (value == null || value.isBlank()) {
        continue;
      }
      StringBuilder collapsed = new StringBuilder();
      boolean pendingSpace = false;
      for (int offset = 0; offset < value.length(); ) {
        int codePoint = value.codePointAt(offset);
        offset += Character.charCount(codePoint);
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
          pendingSpace = collapsed.length() > 0;
        } else {
          if (pendingSpace) {
            collapsed.append(' ');
            pendingSpace = false;
          }
          collapsed.appendCodePoint(codePoint);
        }
      }
      if (!collapsed.isEmpty()) {
        normalized.add(collapsed.toString().toLowerCase(Locale.ROOT));
      }
    }
    return List.copyOf(new ArrayList<>(normalized));
  }

  private static void putDefault(ObjectNode arguments, String name, int value) {
    if (!arguments.has(name)) {
      arguments.put(name, value);
    }
  }
}
