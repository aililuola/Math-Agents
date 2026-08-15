package io.github.aililuola.mathproofmesh.communication;

import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessagePriority;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class MessageAdmissionPolicy {
  private static final Set<EvidenceType> FACT_EVIDENCE =
      EnumSet.of(
          EvidenceType.NATURAL_PROOF_AUDITED,
          EvidenceType.EXACT_SYMBOLIC_IDENTITY,
          EvidenceType.COMPLETE_FINITE_ENUMERATION,
          EvidenceType.SAT_SMT_CERTIFICATE,
          EvidenceType.FORMAL_KERNEL_CERTIFICATE);
  private static final Pattern DRIVE_PATH = Pattern.compile("^[a-zA-Z]:[/\\\\].*");

  private final MessageBrokerPolicy policy;
  private final RouteRegistry routes;
  private final ArtifactCatalog artifacts;
  private final DependencyCatalog dependencies;

  public MessageAdmissionPolicy(
      MessageBrokerPolicy policy,
      RouteRegistry routes,
      ArtifactCatalog artifacts,
      DependencyCatalog dependencies) {
    this.policy = java.util.Objects.requireNonNull(policy, "policy");
    this.routes = java.util.Objects.requireNonNull(routes, "routes");
    this.artifacts = java.util.Objects.requireNonNull(artifacts, "artifacts");
    this.dependencies = java.util.Objects.requireNonNull(dependencies, "dependencies");
  }

  public AdmissionResult evaluate(
      MessageEnvelope message, String reviewerAgentId, int currentRound) {
    AdmissionResult gate = validateSchemaAndLength(message);
    if (gate != null) {
      return gate;
    }
    if (!message.problemHash().equals(routes.problemHash())) {
      return AdmissionResult.reject(AdmissionRejection.PROBLEM_HASH, "problem_hash mismatch");
    }
    if (!routes.ownsAgent(
        message.sourceRouteId(), message.sourceAgentId(), message.sourceRole())) {
      return AdmissionResult.reject(
          AdmissionRejection.SOURCE_OWNERSHIP,
          "source agent/role does not belong to route");
    }
    TargetSelection selection = selectTargets(message);
    if (currentRound - message.roundCreated() > message.ttlRounds()) {
      return AdmissionResult.reject(AdmissionRejection.TTL, "message TTL expired");
    }
    gate = validateArtifactReferences(message);
    if (gate != null) {
      return gate;
    }
    gate = validateScope(message);
    if (gate != null) {
      return gate;
    }
    gate = validateDependencies(message);
    if (gate != null) {
      return gate;
    }
    gate = validateEvidenceTier(message);
    if (gate != null) {
      return gate;
    }
    gate = validateReviewIndependence(message, reviewerAgentId);
    if (gate != null) {
      return gate;
    }
    if (!sameHash(message.contentHash(), message.expectedContentHash())) {
      return AdmissionResult.reject(AdmissionRejection.CONTENT_HASH, "content_hash mismatch");
    }
    return AdmissionResult.accept(
        selection.selected(), selection.rejected(), priority(message));
  }

  private AdmissionResult validateSchemaAndLength(MessageEnvelope message) {
    if (message == null) {
      return AdmissionResult.reject(
          AdmissionRejection.SCHEMA_OR_LENGTH, "message schema is required");
    }
    if (!policy.schemaVersion().equals(message.schemaVersion())) {
      return AdmissionResult.reject(
          AdmissionRejection.SCHEMA_OR_LENGTH, "unsupported message schema version");
    }
    if (message.canonicalJson().length() > policy.maxMessageChars()) {
      return AdmissionResult.reject(
          AdmissionRejection.SCHEMA_OR_LENGTH, "message exceeds max_message_chars");
    }
    if (message.assumptions().size() > policy.maxAssumptions()) {
      return AdmissionResult.reject(
          AdmissionRejection.SCHEMA_OR_LENGTH, "message exceeds max_assumptions");
    }
    if (message.dependencies().size() > policy.maxDependencies()) {
      return AdmissionResult.reject(
          AdmissionRejection.SCHEMA_OR_LENGTH, "message exceeds max_dependencies");
    }
    return null;
  }

  private TargetSelection selectTargets(MessageEnvelope message) {
    List<String> requested =
        message.targetRouteIds().isEmpty()
            ? routes.neighbors(message.sourceRouteId())
            : List.copyOf(new LinkedHashSet<>(message.targetRouteIds()));
    Set<String> sourceNeighbors = Set.copyOf(routes.neighbors(message.sourceRouteId()));
    List<String> selected = new ArrayList<>();
    Map<String, String> rejected = new LinkedHashMap<>();
    boolean canShare = crossRouteShareAllowed(message);
    for (String target : requested) {
      if (target.equals(message.sourceRouteId())) {
        continue;
      }
      if (!routes.exists(target)) {
        rejected.put(target, "unknown target route");
      } else if (!policy.crossRouteEnabled() || !canShare) {
        rejected.put(target, "cross-route sharing disabled for this message");
      } else if (message.evidenceType() != EvidenceType.COUNTEREXAMPLE
          && !sourceNeighbors.contains(target)) {
        rejected.put(target, "target is not a sparse neighbor");
      } else if (selected.size() >= policy.maxNeighborsPerRoute()) {
        rejected.put(target, "neighbor cap reached");
      } else {
        selected.add(target);
      }
    }
    return new TargetSelection(List.copyOf(selected), Map.copyOf(rejected));
  }

  private AdmissionResult validateArtifactReferences(MessageEnvelope message) {
    List<String> references = new ArrayList<>(message.artifactRefs());
    if (message.rawSourceRef() != null && !message.rawSourceRef().isBlank()) {
      references.add(message.rawSourceRef());
    }
    for (String reference : references) {
      if (!reference.startsWith("artifact://")) {
        return AdmissionResult.reject(
            AdmissionRejection.ARTIFACT_REFERENCE,
            "artifact references must be run-scoped");
      }
      String relative = reference.substring("artifact://".length());
      if (relative.isBlank()
          || relative.startsWith("/")
          || relative.startsWith("\\")
          || DRIVE_PATH.matcher(relative).matches()
          || hasParentSegment(relative)) {
        return AdmissionResult.reject(
            AdmissionRejection.ARTIFACT_REFERENCE,
            "artifact reference escapes the run root");
      }
      if (!artifacts.exists(reference)) {
        return AdmissionResult.reject(
            AdmissionRejection.ARTIFACT_REFERENCE,
            "artifact reference does not exist in this run");
      }
    }
    return null;
  }

  private AdmissionResult validateScope(MessageEnvelope message) {
    Set<Integer> orders = new LinkedHashSet<>();
    Map<String, VariableBinding> bindings = new LinkedHashMap<>();
    for (VariableBinding binding : message.variableBindings()) {
      if (bindings.putIfAbsent(binding.variableId(), binding) != null) {
        return AdmissionResult.reject(
            AdmissionRejection.QUANTIFIER_SCOPE,
            "variable binding IDs must be unique");
      }
    }
    for (QuantifierSpec quantifier : message.quantifiers()) {
      if (!orders.add(quantifier.order())) {
        return AdmissionResult.reject(
            AdmissionRejection.QUANTIFIER_SCOPE,
            "quantifier orders must be unique");
      }
      VariableBinding binding = bindings.get(quantifier.variableId());
      if (binding == null) {
        return AdmissionResult.reject(
            AdmissionRejection.QUANTIFIER_SCOPE,
            "quantified variable " + quantifier.variableId() + " has no binding");
      }
      if (!binding.domain().equals(quantifier.domain())) {
        return AdmissionResult.reject(
            AdmissionRejection.QUANTIFIER_SCOPE,
            "quantifier and binding domains must agree");
      }
    }
    for (int expected = 0; expected < orders.size(); expected++) {
      if (!orders.contains(expected)) {
        return AdmissionResult.reject(
            AdmissionRejection.QUANTIFIER_SCOPE,
            "quantifier orders must be contiguous and start at zero");
      }
    }
    String normalized = message.normalizedStatement() + " " + message.conclusion();
    boolean hasQuantifierMarker =
        containsAsciiIgnoreCase(normalized, "for all")
            || containsAsciiIgnoreCase(normalized, "for every")
            || containsAsciiIgnoreCase(normalized, "there exists")
            || containsAsciiIgnoreCase(normalized, "forall")
            || containsAsciiIgnoreCase(normalized, "exists")
            || normalized.indexOf('∀') >= 0
            || normalized.indexOf('∃') >= 0;
    if (message.memoryTier() == MemoryTier.FACT
        && hasQuantifierMarker
        && message.quantifiers().isEmpty()) {
      return AdmissionResult.reject(
          AdmissionRejection.QUANTIFIER_SCOPE,
          "global fact has an unparsed quantifier scope");
    }
    return null;
  }

  private AdmissionResult validateDependencies(MessageEnvelope message) {
    for (String dependency : message.dependencies()) {
      if (dependencies.invalidated(dependency)) {
        return AdmissionResult.reject(
            AdmissionRejection.DEPENDENCY, "dependency is invalidated");
      }
      if (!dependencies.exists(dependency)) {
        return AdmissionResult.reject(
            AdmissionRejection.DEPENDENCY, "fact dependencies are unresolved");
      }
    }
    if (dependencies.wouldCreateCycle(message.messageId(), message.dependencies())) {
      return AdmissionResult.reject(
          AdmissionRejection.DEPENDENCY, "fact dependency cycle detected");
    }
    return null;
  }

  private AdmissionResult validateEvidenceTier(MessageEnvelope message) {
    if (message.evidenceType() == EvidenceType.COUNTEREXAMPLE) {
      if (message.memoryTier() != MemoryTier.NEGATIVE) {
        return AdmissionResult.reject(
            AdmissionRejection.EVIDENCE_TIER,
            "counterexamples must enter NegativeMemory");
      }
      if (message.verificationStatus() != ClaimStatus.REJECTED) {
        return AdmissionResult.reject(
            AdmissionRejection.EVIDENCE_TIER,
            "counterexamples must reject their target claim");
      }
      return null;
    }
    if (message.memoryTier() == MemoryTier.NEGATIVE) {
      if (message.verificationStatus() != ClaimStatus.REJECTED) {
        return AdmissionResult.reject(
            AdmissionRejection.EVIDENCE_TIER,
            "negative messages require rejected status");
      }
      return null;
    }
    if (message.memoryTier() == MemoryTier.INSIGHT) {
      return null;
    }
    if (message.verificationStatus() != ClaimStatus.VERIFIED) {
      return AdmissionResult.reject(
          AdmissionRejection.EVIDENCE_TIER, "FactMemory requires verified status");
    }
    if (message.verificationConfidence() < policy.factPassThreshold()) {
      return AdmissionResult.reject(
          AdmissionRejection.EVIDENCE_TIER,
          "verification confidence is below the fact gate");
    }
    if (!FACT_EVIDENCE.contains(message.evidenceType())) {
      return AdmissionResult.reject(
          AdmissionRejection.EVIDENCE_TIER,
          "evidence type cannot establish a reusable fact");
    }
    if (message.normalizationConfidence() < policy.factPassThreshold()) {
      return AdmissionResult.reject(
          AdmissionRejection.EVIDENCE_TIER,
          "quantifier/scope normalization is incomplete");
    }
    return null;
  }

  private static AdmissionResult validateReviewIndependence(
      MessageEnvelope message, String reviewerAgentId) {
    if (message.memoryTier() != MemoryTier.FACT
        && message.evidenceType() != EvidenceType.COUNTEREXAMPLE) {
      return null;
    }
    if (reviewerAgentId == null || reviewerAgentId.isBlank()) {
      return AdmissionResult.reject(
          AdmissionRejection.REVIEW_INDEPENDENCE,
          message.evidenceType() == EvidenceType.COUNTEREXAMPLE
              ? "counterexamples require independent replay"
              : "FactMemory requires an independent route referee");
    }
    if (reviewerAgentId.equals(message.sourceAgentId())) {
      return AdmissionResult.reject(
          AdmissionRejection.REVIEW_INDEPENDENCE,
          message.evidenceType() == EvidenceType.COUNTEREXAMPLE
              ? "counterexamples require independent replay"
              : "the author cannot referee its own fact");
    }
    return null;
  }

  private boolean crossRouteShareAllowed(MessageEnvelope message) {
    if (message.evidenceType() == EvidenceType.COUNTEREXAMPLE) {
      return policy.shareCounterexamples();
    }
    if (message.messageType() == MessageType.PROOF_OBLIGATION) {
      return policy.shareOpenObligations();
    }
    if (message.messageType() == MessageType.FAILURE_RECORD) {
      return policy.shareFailureRecords();
    }
    if (message.memoryTier() == MemoryTier.FACT) {
      return policy.shareVerifiedFacts();
    }
    if (message.memoryTier() == MemoryTier.INSIGHT) {
      return policy.shareUnverifiedInsights();
    }
    return false;
  }

  public static MessagePriority priority(MessageEnvelope message) {
    if (message.evidenceType() == EvidenceType.COUNTEREXAMPLE
        || message.messageType() == MessageType.COUNTEREXAMPLE
        || message.messageType() == MessageType.CONTRADICTION_NOTICE) {
      return MessagePriority.CRITICAL;
    }
    if (message.memoryTier() == MemoryTier.FACT
        && message.verificationStatus() == ClaimStatus.VERIFIED) {
      return MessagePriority.HIGH;
    }
    if (Set.of(
            MessageType.PROOF_OBLIGATION,
            MessageType.REPAIR_REQUEST,
            MessageType.BRIDGE_LEMMA_REQUEST,
            MessageType.STRATEGY_REWRITE_REQUEST)
        .contains(message.messageType())) {
      return MessagePriority.NORMAL;
    }
    return MessagePriority.LOW;
  }

  private static boolean sameHash(String supplied, String expected) {
    return supplied != null
        && MessageDigest.isEqual(
            supplied.getBytes(StandardCharsets.UTF_8),
            expected.getBytes(StandardCharsets.UTF_8));
  }

  private static boolean hasParentSegment(String path) {
    int segmentStart = 0;
    for (int index = 0; index <= path.length(); index++) {
      if (index == path.length() || path.charAt(index) == '/' || path.charAt(index) == '\\') {
        if (index - segmentStart == 2
            && path.charAt(segmentStart) == '.'
            && path.charAt(segmentStart + 1) == '.') {
          return true;
        }
        segmentStart = index + 1;
      }
    }
    return false;
  }

  private static boolean containsAsciiIgnoreCase(String text, String needle) {
    for (int offset = 0; offset + needle.length() <= text.length(); offset++) {
      boolean matches = true;
      for (int index = 0; index < needle.length(); index++) {
        char candidate = text.charAt(offset + index);
        if (candidate >= 'A' && candidate <= 'Z') {
          candidate = (char) (candidate + ('a' - 'A'));
        }
        if (candidate != needle.charAt(index)) {
          matches = false;
          break;
        }
      }
      if (matches) {
        return true;
      }
    }
    return false;
  }

  private record TargetSelection(
      List<String> selected, Map<String, String> rejected) {}
}
