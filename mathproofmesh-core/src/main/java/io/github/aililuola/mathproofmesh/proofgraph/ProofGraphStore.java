package io.github.aililuola.mathproofmesh.proofgraph;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.GraphEdgeType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProofGraphEdge;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeAdmissionGate;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntSupplier;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedMultigraph;
import org.jgrapht.traverse.TopologicalOrderIterator;

@SuppressFBWarnings(
    value = {
      "USO_UNSAFE_METHOD_SYNCHRONIZATION",
      "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION"
    },
    justification =
        "The public monitor serializes the projection; dependency insertion rolls back and"
            + " deliberately preserves the original validation exception.")
public final class ProofGraphStore {
  private static final Set<GraphEdgeType> ACYCLIC_RELATIONS =
      Set.of(GraphEdgeType.DEPENDS_ON, GraphEdgeType.IMPLIES, GraphEdgeType.CLOSES);

  private final ProofGraphPolicy policy;
  private final Map<String, ProofObligation> obligations = new LinkedHashMap<>();
  private final Map<String, MessageEnvelope> claimNodes = new LinkedHashMap<>();
  private final Map<String, ProofGraphEdge> edges = new LinkedHashMap<>();
  private final Map<EdgeIdentity, String> edgeIdsByIdentity = new LinkedHashMap<>();
  private final Map<String, String> aliases = new LinkedHashMap<>();
  private final Map<String, List<String>> contentIndex = new LinkedHashMap<>();
  private final Map<String, Set<String>> dependenciesBySource = new LinkedHashMap<>();
  private final Map<String, Set<String>> dependentsByTarget = new LinkedHashMap<>();
  private final Set<String> needsReverify = new LinkedHashSet<>();
  private final Map<String, Long> versions = new LinkedHashMap<>();
  private final List<ProofGraphAuditEvent> audit = new ArrayList<>();
  private final ObligationCanonicalizationRegistry canonicalization =
      new ObligationCanonicalizationRegistry();
  private final Graph<String, DefaultEdge> structuralProjection =
      new DirectedMultigraph<>(DefaultEdge.class);
  private String problemHash;
  private boolean frozen;
  private NegativeAwareProofGraphWriter negativeAwareWriter;
  private ObligationCreationContext pendingCreationContext;
  private CanonicalizedObligationWriteResult lastCanonicalizedWriteResult;

  public ProofGraphStore(String problemHash) {
    this(problemHash, ProofGraphPolicy.defaults());
  }

  public ProofGraphStore(String problemHash, ProofGraphPolicy policy) {
    this.problemHash = problemHash == null ? "" : problemHash;
    this.policy = java.util.Objects.requireNonNull(policy, "policy");
    configureNegativeKnowledge(new NegativeKnowledgeRegistry(), () -> 0, List.of());
  }

  public synchronized ProofObligation addObligation(ProofObligation obligation) {
    return negativeAwareWriter.addObligation(obligation);
  }

  @SuppressFBWarnings(
      value = "RCN_REDUNDANT_NULLCHECK_OF_NULL_VALUE",
      justification =
          "The negative-aware writer sets the result through the guarded unchecked callback;"
              + " SpotBugs does not follow that indirect call.")
  public synchronized CanonicalizedObligationWriteResult addObligationCanonicalized(
      ProofObligation obligation, ObligationCreationContext context) {
    java.util.Objects.requireNonNull(obligation, "obligation");
    java.util.Objects.requireNonNull(context, "context");
    if (pendingCreationContext != null) {
      throw new IllegalStateException("nested proof obligation creation is not permitted");
    }
    pendingCreationContext = context;
    lastCanonicalizedWriteResult = null;
    try {
      negativeAwareWriter.addObligation(obligation);
      if (lastCanonicalizedWriteResult == null) {
        throw new IllegalStateException("canonical proof obligation write produced no result");
      }
      return lastCanonicalizedWriteResult;
    } finally {
      pendingCreationContext = null;
      lastCanonicalizedWriteResult = null;
    }
  }

  public synchronized ProofObligation addRootGoalObligation(ProofObligation obligation) {
    return negativeAwareWriter.addRootGoalObligation(
        obligation, NegativeAwareProofGraphWriter.IMMUTABLE_ROOT_GOAL);
  }

  public synchronized ProofObligation addFalsificationObligation(ProofObligation obligation) {
    return negativeAwareWriter.addFalsificationObligation(obligation);
  }

  synchronized ProofObligation addObligationUnchecked(ProofObligation obligation) {
    ensureMutable();
    validateProblemHash(obligation.problemHash(), "obligation");
    ObligationCreationContext context =
        pendingCreationContext == null
            ? ObligationCreationContext.defaultFor(obligation)
            : pendingCreationContext;
    if (!context.problemHash().equals(obligation.problemHash())) {
      throw new IllegalArgumentException("obligation creation context problemHash mismatch");
    }
    ProofObligation existing = obligations.get(resolve(obligation.obligationId()));
    if (existing != null) {
      if (!existing.contentHash().equals(obligation.contentHash())) {
        throw new IllegalArgumentException("obligation ID collision");
      }
      lastCanonicalizedWriteResult = canonicalization.register(obligation, context);
      return existing;
    }
    for (String rawDependency : obligation.dependencyIds()) {
      String dependency = resolve(rawDependency);
      if (!obligations.containsKey(dependency) && !claimNodes.containsKey(dependency)) {
        throw new IllegalArgumentException("missing proof dependency: " + rawDependency);
      }
      if (dependency.equals(obligation.obligationId())) {
        throw new IllegalArgumentException("proof graph dependency cycle detected");
      }
    }

    String dependencyPlanSignature =
        ObligationCanonicalizationRegistry.dependencyPlanSignature(obligation, context);
    Set<String> routes = Set.copyOf(obligation.routeIds());
    Optional<ProofObligation> duplicate =
        contentIndex.getOrDefault(obligation.contentHash(), List.of()).stream()
            .map(obligations::get)
            .filter(java.util.Objects::nonNull)
            .filter(item -> item.routeIds().stream().anyMatch(routes::contains))
            .filter(
                item ->
                    dependencyPlanSignature.equals(
                        canonicalization
                            .occurrenceForObligation(item.obligationId())
                            .map(ObligationOccurrenceRecord::dependencyPlanSignature)
                            .orElseGet(
                                () ->
                                    ObligationCanonicalizationRegistry.dependencyPlanSignature(
                                        item, ObligationCreationContext.defaultFor(item)))))
            .findFirst();
    if (duplicate.isPresent()) {
      ProofObligation canonical = duplicate.orElseThrow();
      aliases.put(obligation.obligationId(), canonical.obligationId());
      ProofObligation merged =
          copyObligation(
              canonical,
              strongestStatus(canonical.status(), obligation.status()),
              union(canonical.routeIds(), obligation.routeIds()),
              union(canonical.dependencyIds(), obligation.dependencyIds()),
              union(canonical.evidenceMessageIds(), obligation.evidenceMessageIds()),
              Math.max(canonical.priority(), obligation.priority()),
              Math.max(canonical.centrality(), obligation.centrality()));
      obligations.put(canonical.obligationId(), merged);
      increment(canonical.obligationId());
      record(
          "obligation_duplicate_collapsed",
          canonical.obligationId(),
          Map.of("duplicate_obligation_id", obligation.obligationId()));
      lastCanonicalizedWriteResult = canonicalization.register(obligation, context);
      return merged;
    }
    ObligationCanonicalizationSnapshot beforeCanonicalization = canonicalization.snapshot();
    requireNodeCapacity();
    obligations.put(obligation.obligationId(), obligation);
    contentIndex
        .computeIfAbsent(obligation.contentHash(), ignored -> new ArrayList<>())
        .add(obligation.obligationId());
    structuralProjection.addVertex(obligation.obligationId());
    versions.put(obligation.obligationId(), 0L);
    record("obligation_opened", obligation.obligationId(), Map.of());
    try {
      for (String dependency : obligation.dependencyIds()) {
        addEdge(
            new ProofGraphEdge(
                null,
                GraphEdgeType.DEPENDS_ON,
                null,
                null,
                obligation.obligationId(),
                resolve(dependency)));
      }
      lastCanonicalizedWriteResult = canonicalization.register(obligation, context);
    } catch (RuntimeException exception) {
      removeObligationInternal(obligation.obligationId());
      canonicalization.load(beforeCanonicalization);
      lastCanonicalizedWriteResult = null;
      throw exception;
    }
    return obligations.get(obligation.obligationId());
  }

  public synchronized MessageEnvelope addClaimNode(MessageEnvelope message) {
    return negativeAwareWriter.addClaimNode(message);
  }

  public synchronized MessageEnvelope addFalsificationClaimNode(MessageEnvelope message) {
    return negativeAwareWriter.addFalsificationClaimNode(message);
  }

  synchronized MessageEnvelope addClaimNodeUnchecked(MessageEnvelope message) {
    ensureMutable();
    validateProblemHash(message.problemHash(), "claim");
    MessageEnvelope existing = claimNodes.get(message.messageId());
    if (existing != null) {
      if (!existing.contentHash().equals(message.contentHash())) {
        throw new IllegalArgumentException("claim node ID collision");
      }
      return existing;
    }
    requireNodeCapacity();
    claimNodes.put(message.messageId(), message);
    structuralProjection.addVertex(message.messageId());
    versions.put(message.messageId(), 0L);
    record("proof_claim_node_added", message.messageId(), Map.of());
    return message;
  }

  public synchronized ProofGraphEdge addEdge(ProofGraphEdge edge) {
    ensureMutable();
    String source = resolve(edge.sourceId());
    String target = resolve(edge.targetId());
    if (source.equals(target)) {
      throw new IllegalArgumentException("self edges are not permitted");
    }
    if (!containsNode(source) || !containsNode(target)) {
      throw new IllegalArgumentException("proof graph edge references an unknown node");
    }
    EdgeIdentity identity =
        new EdgeIdentity(source, target, edge.edgeType(), edge.evidenceMessageId());
    String duplicateId = edgeIdsByIdentity.get(identity);
    if (duplicateId != null) {
      return edges.get(duplicateId);
    }
    if (edges.size() >= policy.maxEdges()) {
      throw new IllegalStateException("proof graph edge limit reached");
    }
    if (ACYCLIC_RELATIONS.contains(edge.edgeType())
        && !structuralProjection.incomingEdgesOf(source).isEmpty()
        && pathExists(target, source)) {
      throw new IllegalArgumentException("proof graph dependency cycle detected");
    }
    ProofGraphEdge normalized =
        source.equals(edge.sourceId()) && target.equals(edge.targetId())
            ? edge
            : new ProofGraphEdge(
                edge.edgeId(),
                edge.edgeType(),
                edge.evidenceMessageId(),
                edge.routeId(),
                source,
                target);
    edges.put(normalized.edgeId(), normalized);
    edgeIdsByIdentity.put(identity, normalized.edgeId());
    if (ACYCLIC_RELATIONS.contains(normalized.edgeType())) {
      structuralProjection.addEdge(source, target);
    }
    if (normalized.edgeType() == GraphEdgeType.DEPENDS_ON) {
      dependenciesBySource
          .computeIfAbsent(source, ignored -> new LinkedHashSet<>())
          .add(target);
      dependentsByTarget
          .computeIfAbsent(target, ignored -> new LinkedHashSet<>())
          .add(source);
    }
    record("proof_graph_edge_added", normalized.edgeId(), Map.of());
    return normalized;
  }

  public synchronized ProofObligation closeObligation(
      String obligationId,
      String evidenceMessageId,
      double confidence) {
    return closeObligation(
        obligationId, evidenceMessageId, confidence, version(resolve(obligationId)));
  }

  public synchronized ProofObligation closeObligation(
      String obligationId,
      String evidenceMessageId,
      double confidence,
      long expectedVersion) {
    ensureMutable();
    String resolvedId = resolve(obligationId);
    assertVersion(resolvedId, expectedVersion);
    ProofObligation obligation = requireObligation(resolvedId);
    if (confidence < policy.closeObligationThreshold()) {
      return setStatus(
          obligation,
          "tentative",
          obligation.evidenceMessageIds(),
          false,
          "obligation_tentative");
    }
    MessageEnvelope evidence = claimNodes.get(evidenceMessageId);
    if (evidence == null) {
      throw new IllegalArgumentException(
          "closed obligation requires a graph evidence message");
    }
    if (evidence.memoryTier() != MemoryTier.FACT
        || evidence.verificationStatus() != ClaimStatus.VERIFIED) {
      throw new IllegalArgumentException("only a verified fact can close an obligation");
    }
    ProofObligation closed =
        setStatus(
            obligation,
            "closed",
            union(obligation.evidenceMessageIds(), List.of(evidenceMessageId)),
            false,
            "obligation_closed");
    addEdge(
        new ProofGraphEdge(
            null,
            GraphEdgeType.CLOSES,
            evidenceMessageId,
            null,
            evidenceMessageId,
            resolvedId));
    return closed;
  }

  public synchronized ProofObligation refuteObligation(
      String obligationId, String evidenceMessageId) {
    ensureMutable();
    String resolvedId = resolve(obligationId);
    ProofObligation obligation = requireObligation(resolvedId);
    List<String> evidence =
        evidenceMessageId == null || evidenceMessageId.isBlank()
            ? obligation.evidenceMessageIds()
            : union(obligation.evidenceMessageIds(), List.of(evidenceMessageId));
    ProofObligation refuted =
        setStatus(obligation, "refuted", evidence, false, "obligation_refuted");
    reopenDependents(resolvedId, "dependency_refuted");
    return refuted;
  }

  public synchronized ProofObligation reopenObligation(String obligationId) {
    return reopenObligation(obligationId, version(resolve(obligationId)));
  }

  public synchronized ProofObligation reopenObligation(
      String obligationId, long expectedVersion) {
    ensureMutable();
    String resolvedId = resolve(obligationId);
    assertVersion(resolvedId, expectedVersion);
    return setStatus(
        requireObligation(resolvedId),
        "open",
        List.of(),
        true,
        "obligation_reopened");
  }

  public synchronized List<String> invalidateEvidenceMessages(
      Collection<String> messageIds, String reason) {
    ensureMutable();
    Set<String> invalidated = Set.copyOf(messageIds);
    if (invalidated.isEmpty()) {
      return List.of();
    }
    invalidated.forEach(claimNodes::remove);
    edges.entrySet().removeIf(
        entry -> {
          ProofGraphEdge edge = entry.getValue();
          return invalidated.contains(edge.sourceId())
              || invalidated.contains(edge.targetId())
              || (edge.evidenceMessageId() != null
                  && invalidated.contains(edge.evidenceMessageId()));
        });
    List<String> reopened = new ArrayList<>();
    for (ProofObligation obligation : List.copyOf(obligations.values())) {
      List<String> evidence =
          obligation.evidenceMessageIds().stream()
              .filter(id -> !invalidated.contains(id))
              .toList();
      if (("closed".equals(obligation.status()) || "refuted".equals(obligation.status()))
          && evidence.isEmpty()) {
        setStatus(obligation, "open", List.of(), true, "proof_evidence_invalidated");
        reopened.add(obligation.obligationId());
      } else if (evidence.size() != obligation.evidenceMessageIds().size()) {
        obligations.put(
            obligation.obligationId(),
            copyObligation(
                obligation,
                obligation.status(),
                obligation.routeIds(),
                obligation.dependencyIds(),
                evidence,
                obligation.priority(),
                obligation.centrality()));
        increment(obligation.obligationId());
      }
    }
    rebuildProjectionAndIndexes();
    record(
        "proof_evidence_invalidation_batch",
        "proof-graph",
        Map.of(
            "reason", requireText(reason, "reason"),
            "message_ids", String.join(",", invalidated)));
    return List.copyOf(reopened);
  }

  public synchronized List<String> applyCounterexample(MessageEnvelope message) {
    ensureMutable();
    if (message.evidenceType() != EvidenceType.COUNTEREXAMPLE) {
      return List.of();
    }
    addFalsificationClaimNode(message);
    List<String> affected = new ArrayList<>();
    for (ProofObligation obligation : List.copyOf(obligations.values())) {
      String conclusion = MathTextSimilarity.normalize(message.conclusion());
      String statement = MathTextSimilarity.normalize(obligation.normalizedStatement());
      if (obligation.normalizedStatement().equals(message.normalizedStatement())
          || (!conclusion.isEmpty() && statement.contains(conclusion))) {
        refuteObligation(obligation.obligationId(), message.messageId());
        affected.add(obligation.obligationId());
      }
    }
    return List.copyOf(affected);
  }

  public synchronized List<ProofObligation> findDependents(String nodeId) {
    String resolved = resolve(nodeId);
    return transitiveDependents(resolved).stream()
        .map(obligations::get)
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  public synchronized Set<String> dependencyClosure(
      Collection<String> obligationIds) {
    Set<String> closure = new LinkedHashSet<>();
    Deque<String> pending = new ArrayDeque<>();
    obligationIds.stream().map(this::resolve).forEach(pending::add);
    while (!pending.isEmpty()) {
      String current = pending.removeFirst();
      if (!obligations.containsKey(current) || !closure.add(current)) {
        continue;
      }
      pending.addAll(dependenciesBySource.getOrDefault(current, Set.of()));
    }
    return Set.copyOf(closure);
  }

  public synchronized Set<String> coreDependencyClosure() {
    List<String> mainGoals =
        obligations.values().stream()
            .filter(item -> item.kind() == ObligationKind.MAIN_GOAL)
            .map(ProofObligation::obligationId)
            .toList();
    return dependencyClosure(mainGoals);
  }

  public synchronized List<ProofObligation> coreOpenObligations() {
    Set<String> closure = coreDependencyClosure();
    return obligations.values().stream()
        .filter(item -> closure.contains(item.obligationId()))
        .filter(item -> Set.of("open", "tentative", "blocked").contains(item.status()))
        .sorted(
            Comparator.comparing(
                    (ProofObligation item) -> item.kind() != ObligationKind.MAIN_GOAL)
                .thenComparing(ProofObligation::centrality, Comparator.reverseOrder())
                .thenComparing(ProofObligation::priority, Comparator.reverseOrder())
                .thenComparing(ProofObligation::obligationId))
        .toList();
  }

  public synchronized List<ProofObligation> topologicalOrder() {
    TopologicalOrderIterator<String, DefaultEdge> iterator =
        new TopologicalOrderIterator<>(structuralProjection);
    List<ProofObligation> ordered = new ArrayList<>();
    while (iterator.hasNext()) {
      ProofObligation obligation = obligations.get(iterator.next());
      if (obligation != null) {
        ordered.add(obligation);
      }
    }
    return List.copyOf(ordered);
  }

  public synchronized List<List<ProofObligation>> findSharedBottlenecks(
      int minRoutes) {
    if (minRoutes < 1) {
      throw new IllegalArgumentException("minRoutes must be positive");
    }
    List<List<ProofObligation>> result = new ArrayList<>();
    Set<String> familyTargets = new LinkedHashSet<>();
    for (BottleneckFamilyRecord family : activeBottleneckFamilies()) {
      familyTargets.addAll(family.canonicalTargetIds());
      Set<String> routes = new LinkedHashSet<>();
      List<ProofObligation> members = new ArrayList<>();
      for (String canonicalTargetId : family.canonicalTargetIds()) {
        CanonicalObligationRecord target =
            allCanonicalTargets().stream()
                .filter(item -> item.canonicalTargetId().equals(canonicalTargetId))
                .findFirst()
                .orElse(null);
        if (target == null) {
          continue;
        }
        routes.addAll(target.routeIds());
        target.occurrenceIds().stream()
            .map(id -> canonicalization.snapshot().occurrences().get(id))
            .filter(java.util.Objects::nonNull)
            .map(ObligationOccurrenceRecord::obligationId)
            .map(this::resolve)
            .distinct()
            .map(obligations::get)
            .filter(java.util.Objects::nonNull)
            .filter(item -> Set.of("open", "tentative", "blocked").contains(item.status()))
            .forEach(members::add);
      }
      if (routes.size() >= minRoutes && !members.isEmpty()) {
        result.add(
            members.stream()
                .distinct()
                .sorted(Comparator.comparing(ProofObligation::obligationId))
                .toList());
      }
    }
    Map<String, ObligationOccurrenceRecord> occurrenceIndex =
        canonicalization.snapshot().occurrences();
    canonicalOpenTargets().stream()
        .filter(target -> !familyTargets.contains(target.canonicalTargetId()))
        .filter(target -> target.routeIds().size() >= minRoutes)
        .map(
            target ->
                target.occurrenceIds().stream()
                    .map(occurrenceIndex::get)
                    .filter(java.util.Objects::nonNull)
                    .map(ObligationOccurrenceRecord::obligationId)
                    .map(this::resolve)
                    .distinct()
                    .map(obligations::get)
                    .filter(java.util.Objects::nonNull)
                    .filter(
                        item ->
                            Set.of("open", "tentative", "blocked").contains(item.status()))
                    .sorted(Comparator.comparing(ProofObligation::obligationId))
                    .toList())
        .filter(group -> !group.isEmpty())
        .forEach(result::add);
    return List.copyOf(result);
  }

  public synchronized double proofDebt(String routeId) {
    return rawProofDebt(routeId);
  }

  public synchronized double rawProofDebt(String routeId) {
    double debt = 0.0;
    for (ProofObligation obligation : obligations.values()) {
      if (!obligation.routeIds().contains(routeId)
          || "closed".equals(obligation.status())) {
        continue;
      }
      debt += obligationDebtWeight(obligation);
    }
    return debt;
  }

  public synchronized double canonicalProofDebt(String routeId) {
    Map<String, Double> weights = canonicalDebtWeights(routeId, null);
    return weights.values().stream().mapToDouble(Double::doubleValue).sum();
  }

  public synchronized double globalCanonicalProofDebt() {
    return canonicalDebtWeights(null, null).values().stream()
        .mapToDouble(Double::doubleValue)
        .sum();
  }

  public synchronized double activeCanonicalProofDebt() {
    return canonicalDebtWeights(null, Boolean.TRUE).values().stream()
        .mapToDouble(Double::doubleValue)
        .sum();
  }

  public synchronized double deferredCanonicalProofDebt() {
    return canonicalDebtWeights(null, Boolean.FALSE).values().stream()
        .mapToDouble(Double::doubleValue)
        .sum();
  }

  public synchronized String coreBottleneck() {
    return obligations.values().stream()
        .filter(item -> !"closed".equals(item.status()))
        .max(
            Comparator.comparingDouble(
                    (ProofObligation item) ->
                        item.centrality()
                            + item.priority()
                            + dependentsByTarget
                                .getOrDefault(item.obligationId(), Set.of())
                                .size())
                .thenComparing(ProofObligation::obligationId))
        .map(ProofObligation::obligationId)
        .orElse("");
  }

  public synchronized ProofGraphSnapshot minimalSubgraph(
      Collection<String> obligationIds) {
    Set<String> selected = dependencyClosure(obligationIds);
    Map<String, ProofObligation> selectedObligations = new LinkedHashMap<>();
    selected.forEach(
        id -> {
          ProofObligation obligation = obligations.get(id);
          if (obligation != null) {
            selectedObligations.put(id, obligation);
          }
        });
    Map<String, ProofGraphEdge> selectedEdges = new LinkedHashMap<>();
    Set<String> selectedClaims = new LinkedHashSet<>();
    edges.forEach(
        (id, edge) -> {
          if (selected.contains(edge.sourceId()) && selected.contains(edge.targetId())) {
            selectedEdges.put(id, edge);
          } else if (selected.contains(edge.targetId())
              && claimNodes.containsKey(edge.sourceId())) {
            selectedEdges.put(id, edge);
            selectedClaims.add(edge.sourceId());
          }
        });
    Map<String, MessageEnvelope> claims = new LinkedHashMap<>();
    selectedClaims.forEach(id -> claims.put(id, claimNodes.get(id)));
    return new ProofGraphSnapshot(
        problemHash,
        true,
        selectedObligations,
        claims,
        selectedEdges,
        Map.of(),
        intersection(needsReverify, selected),
        filteredVersions(selected, selectedClaims),
        audit,
        canonicalization.snapshot());
  }

  public synchronized void freeze() {
    if (!frozen) {
      frozen = true;
      record(
          "proof_graph_frozen",
          "proof-graph",
          Map.of(
              "node_count", Integer.toString(obligations.size() + claimNodes.size()),
              "edge_count", Integer.toString(edges.size())));
    }
  }

  public synchronized boolean frozen() {
    return frozen;
  }

  public synchronized String problemHash() {
    return problemHash;
  }

  public synchronized List<ProofObligation> obligations() {
    return List.copyOf(obligations.values());
  }

  public synchronized List<ObligationOccurrenceRecord> rawObligationOccurrences() {
    return canonicalization.occurrences();
  }

  public synchronized List<CanonicalObligationRecord> allCanonicalTargets() {
    return canonicalization.canonicalTargets();
  }

  public synchronized List<CanonicalObligationRecord> canonicalOpenTargets() {
    return canonicalization.canonicalTargets().stream()
        .filter(this::isOperationallyOpen)
        .sorted(canonicalTargetOrder())
        .toList();
  }

  public synchronized List<CanonicalObligationRecord> canonicalOpenTargets(String routeId) {
    return canonicalOpenTargets().stream()
        .filter(target -> target.routeIds().contains(routeId))
        .toList();
  }

  public synchronized List<CanonicalObligationRecord> activeCanonicalOpenTargets() {
    return canonicalOpenTargets().stream()
        .filter(
            target ->
                target.schedulingState() == CanonicalObligationSchedulingState.ACTIVE)
        .toList();
  }

  public synchronized List<CanonicalObligationRecord> deferredCanonicalOpenTargets() {
    return canonicalOpenTargets().stream()
        .filter(
            target ->
                target.schedulingState() != CanonicalObligationSchedulingState.ACTIVE)
        .toList();
  }

  public synchronized List<BottleneckFamilyRecord> allBottleneckFamilies() {
    return canonicalization.bottleneckFamilies();
  }

  public synchronized List<BottleneckFamilyRecord> activeBottleneckFamilies() {
    return canonicalization.bottleneckFamilies().stream()
        .filter(family -> family.schedulingState() == BottleneckFamilySchedulingState.ACTIVE)
        .filter(
            family ->
                family.canonicalTargetIds().stream()
                    .map(
                        id ->
                            canonicalization.canonicalTargets().stream()
                                .filter(target -> target.canonicalTargetId().equals(id))
                                .findFirst()
                                .orElse(null))
                    .filter(java.util.Objects::nonNull)
                    .filter(
                        target ->
                            target.schedulingState()
                                == CanonicalObligationSchedulingState.ACTIVE)
                    .map(target -> canonicalStatus(target.canonicalTargetId()))
                    .anyMatch(
                        status ->
                            status == CanonicalObligationStatus.OPEN
                                || status == CanonicalObligationStatus.MIXED))
        .sorted(Comparator.comparing(BottleneckFamilyRecord::familyId))
        .toList();
  }

  public synchronized List<ProofGraphWorkItem> coreOpenWorkItems() {
    Set<String> core = coreDependencyClosure();
    boolean hasMainGoal =
        obligations.values().stream().anyMatch(item -> item.kind() == ObligationKind.MAIN_GOAL);
    Map<String, ObligationOccurrenceRecord> occurrenceIndex =
        canonicalization.snapshot().occurrences();
    List<CanonicalObligationRecord> targets =
        activeCanonicalOpenTargets().stream()
        .filter(
            target ->
                !hasMainGoal
                    || target.occurrenceIds().stream()
                        .map(occurrenceIndex::get)
                        .filter(java.util.Objects::nonNull)
                        .map(ObligationOccurrenceRecord::obligationId)
                        .map(this::resolve)
                        .anyMatch(core::contains))
        .toList();
    Map<String, CanonicalObligationRecord> byId =
        targets.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    CanonicalObligationRecord::canonicalTargetId,
                    java.util.function.Function.identity(),
                    (left, right) -> left,
                    LinkedHashMap::new));
    Set<String> consumed = new LinkedHashSet<>();
    List<ProofGraphWorkItem> workItems = new ArrayList<>();
    for (BottleneckFamilyRecord family : activeBottleneckFamilies()) {
      Set<String> members = new LinkedHashSet<>(family.canonicalTargetIds());
      members.retainAll(byId.keySet());
      if (members.isEmpty()) {
        continue;
      }
      Set<String> routes = new LinkedHashSet<>();
      members.stream().map(byId::get).forEach(target -> routes.addAll(target.routeIds()));
      String representative =
          members.contains(family.representativeCanonicalTargetId())
              ? family.representativeCanonicalTargetId()
              : members.stream().sorted().findFirst().orElseThrow();
      workItems.add(
          new ProofGraphWorkItem(
              ProofTaskScope.BOTTLENECK_FAMILY,
              family.familyId(),
              representative,
              members,
              routes));
      consumed.addAll(members);
    }
    targets.stream()
        .filter(target -> !consumed.contains(target.canonicalTargetId()))
        .forEach(
            target ->
                workItems.add(
                    new ProofGraphWorkItem(
                        ProofTaskScope.CANONICAL_TARGET,
                        target.canonicalTargetId(),
                        target.canonicalTargetId(),
                        Set.of(target.canonicalTargetId()),
                        target.routeIds())));
    return workItems.stream()
        .sorted(Comparator.comparing(ProofGraphWorkItem::workItemId))
        .toList();
  }

  public synchronized Optional<CanonicalObligationRecord> canonicalTargetForObligation(
      String obligationId) {
    return canonicalization.canonicalForObligation(obligationId);
  }

  public synchronized Optional<String> existingCanonicalTargetId(
      ProofObligation obligation, ObligationCreationContext context) {
    return canonicalization.exactCanonicalTargetId(obligation, context);
  }

  public synchronized boolean wouldCreateCanonicalTarget(
      ProofObligation obligation, ObligationCreationContext context) {
    return existingCanonicalTargetId(obligation, context).isEmpty();
  }

  public synchronized int activeCanonicalTargetCount() {
    return (int)
        canonicalOpenTargets().stream()
            .filter(target -> target.signature().kind() != ObligationKind.MAIN_GOAL)
            .filter(
                target ->
                    target.schedulingState() == CanonicalObligationSchedulingState.ACTIVE)
            .count();
  }

  public synchronized int activeCanonicalTargetCount(String routeId) {
    return (int)
        canonicalOpenTargets(routeId).stream()
            .filter(target -> target.signature().kind() != ObligationKind.MAIN_GOAL)
            .filter(
                target ->
                    target.schedulingState() == CanonicalObligationSchedulingState.ACTIVE)
            .count();
  }

  public synchronized double representativeCentrality(String canonicalTargetId) {
    return canonicalization.representativeCentrality(canonicalTargetId);
  }

  public synchronized double representativePriority(String canonicalTargetId) {
    return canonicalization.representativePriority(canonicalTargetId);
  }

  public synchronized String representativeStatement(String canonicalTargetId) {
    return canonicalization.representativeStatement(canonicalTargetId);
  }

  public synchronized Optional<BottleneckFamilyRecord> bottleneckFamilyForCanonical(
      String canonicalTargetId) {
    return canonicalization.familyForCanonicalTarget(canonicalTargetId);
  }

  public synchronized CanonicalObligationStatus canonicalStatus(String canonicalTargetId) {
    CanonicalObligationRecord target =
        canonicalization.canonicalTargets().stream()
            .filter(item -> item.canonicalTargetId().equals(canonicalTargetId))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "unknown canonical obligation target: " + canonicalTargetId));
    Map<String, ObligationOccurrenceRecord> occurrenceIndex =
        canonicalization.snapshot().occurrences();
    List<String> statuses =
        target.occurrenceIds().stream()
            .map(occurrenceIndex::get)
            .filter(java.util.Objects::nonNull)
            .map(ObligationOccurrenceRecord::obligationId)
            .map(this::resolve)
            .map(obligations::get)
            .filter(java.util.Objects::nonNull)
            .map(ProofObligation::status)
            .toList();
    if (statuses.isEmpty()) {
      return CanonicalObligationStatus.OPEN;
    }
    boolean allClosed = statuses.stream().allMatch("closed"::equals);
    if (allClosed) {
      return CanonicalObligationStatus.RESOLVED;
    }
    boolean allRefuted = statuses.stream().allMatch("refuted"::equals);
    if (allRefuted) {
      return CanonicalObligationStatus.REFUTED;
    }
    boolean allTerminal =
        statuses.stream().allMatch(status -> Set.of("closed", "refuted").contains(status));
    if (allTerminal) {
      return CanonicalObligationStatus.MIXED;
    }
    return CanonicalObligationStatus.OPEN;
  }

  public synchronized boolean acquireCanonicalTaskLease(
      ProofTaskScope scope, String scopeId, String actionKey) {
    return canonicalization.acquireTaskLease(scope, scopeId, actionKey);
  }

  public synchronized boolean hasCanonicalTaskLease(
      ProofTaskScope scope, String scopeId, String actionKey) {
    return canonicalization.hasTaskLease(scope, scopeId, actionKey);
  }

  public synchronized ObligationCanonicalizationSnapshot canonicalizationSnapshot() {
    return canonicalization.snapshot();
  }

  public synchronized String canonicalizationHash() {
    return canonicalization.stableHash();
  }

  public synchronized List<MessageEnvelope> claimNodes() {
    return List.copyOf(claimNodes.values());
  }

  public synchronized List<ProofGraphEdge> edges() {
    return List.copyOf(edges.values());
  }

  public synchronized ProofObligation getObligation(String obligationId) {
    return requireObligation(resolve(obligationId));
  }

  public synchronized long version(String obligationId) {
    return versions.getOrDefault(resolve(obligationId), 0L);
  }

  public synchronized boolean needsReverify(String obligationId) {
    return needsReverify.contains(resolve(obligationId));
  }

  public synchronized List<ProofGraphAuditEvent> audit() {
    return List.copyOf(audit);
  }

  public synchronized ProofGraphSnapshot snapshot() {
    return new ProofGraphSnapshot(
        problemHash,
        frozen,
        obligations,
        claimNodes,
        edges,
        aliases,
        needsReverify,
        versions,
        audit,
        canonicalization.snapshot());
  }

  public synchronized void configureNegativeKnowledge(
      NegativeKnowledgeRegistry registry,
      IntSupplier currentRound,
      List<String> defaultScope) {
    negativeAwareWriter =
        new NegativeAwareProofGraphWriter(
            this,
            new NegativeKnowledgeAdmissionGate(
                java.util.Objects.requireNonNull(registry, "registry")),
            currentRound,
            defaultScope);
  }

  public synchronized List<String> revalidateNegativeKnowledge() {
    return negativeAwareWriter.revalidateOpenObligations();
  }

  public synchronized List<String> blockRouteObligationsForNegativeKnowledge(String routeId) {
    if (routeId == null || routeId.isBlank()) {
      throw new IllegalArgumentException("routeId is required");
    }
    List<String> blocked = new ArrayList<>();
    for (ProofObligation obligation : List.copyOf(obligations.values())) {
      if (obligation.kind() == ObligationKind.MAIN_GOAL
          || !obligation.routeIds().contains(routeId)
          || (!"open".equals(obligation.status())
              && !"tentative".equals(obligation.status()))) {
        continue;
      }
      markBlocked(obligation.obligationId(), "negative_knowledge_route_revalidation");
      blocked.add(obligation.obligationId());
    }
    return List.copyOf(blocked);
  }

  synchronized void blockObligationUnchecked(String obligationId, String reason) {
    markBlocked(obligationId, reason);
  }

  public static ProofGraphStore restore(
      ProofGraphSnapshot snapshot, ProofGraphPolicy policy) {
    ProofGraphStore store = new ProofGraphStore(snapshot.problemHash(), policy);
    synchronized (store) {
      store.obligations.putAll(snapshot.obligations());
      store.claimNodes.putAll(snapshot.claimNodes());
      store.edges.putAll(snapshot.edges());
      store.aliases.putAll(snapshot.aliases());
      store.needsReverify.addAll(snapshot.needsReverify());
      store.versions.putAll(snapshot.versions());
      store.audit.addAll(snapshot.audit());
      store.frozen = snapshot.frozen();
      store.obligations.values().forEach(
          item ->
              store.contentIndex
                  .computeIfAbsent(item.contentHash(), ignored -> new ArrayList<>())
                  .add(item.obligationId()));
      store.rebuildProjectionAndIndexes();
      if (snapshot.canonicalization().emptyState()) {
        snapshot.obligations().values().stream()
            .sorted(Comparator.comparing(ProofObligation::obligationId))
            .forEach(
                obligation ->
                    store.canonicalization.register(
                        obligation, ObligationCreationContext.defaultFor(obligation)));
        store.record(
            "canonicalization_rebuilt_from_raw",
            "proof-graph",
            Map.of("raw_obligation_count", Integer.toString(snapshot.obligations().size())));
      } else {
        store.canonicalization.load(snapshot.canonicalization());
      }
    }
    return store;
  }

  synchronized void markBlocked(String obligationId, String reason) {
    ProofObligation obligation = requireObligation(resolve(obligationId));
    setStatus(obligation, "blocked", obligation.evidenceMessageIds(), false, reason);
  }

  synchronized void reopenBlockedByStatement(String normalizedStatement) {
    for (ProofObligation obligation : List.copyOf(obligations.values())) {
      if (obligation.normalizedStatement().equals(normalizedStatement)
          && "blocked".equals(obligation.status())) {
        setStatus(
            obligation,
            "open",
            obligation.evidenceMessageIds(),
            true,
            "contradiction_resolved");
      }
    }
  }

  synchronized void recordExternal(
      String eventType, String subjectId, Map<String, String> details) {
    record(eventType, subjectId, details);
  }

  private void reopenDependents(String targetId, String reason) {
    for (String dependentId : transitiveDependents(targetId)) {
      ProofObligation dependent = obligations.get(dependentId);
      if (dependent != null
          && ("closed".equals(dependent.status())
              || "blocked".equals(dependent.status())
              || "tentative".equals(dependent.status()))) {
        setStatus(dependent, "open", List.of(), true, reason);
      } else if (dependent != null) {
        needsReverify.add(dependentId);
      }
    }
  }

  private Set<String> transitiveDependents(String targetId) {
    Set<String> result = new LinkedHashSet<>();
    Deque<String> pending =
        new ArrayDeque<>(dependentsByTarget.getOrDefault(targetId, Set.of()));
    while (!pending.isEmpty()) {
      String current = pending.removeFirst();
      if (result.add(current)) {
        pending.addAll(dependentsByTarget.getOrDefault(current, Set.of()));
      }
    }
    return result;
  }

  private ProofObligation setStatus(
      ProofObligation source,
      String status,
      List<String> evidence,
      boolean reverify,
      String eventType) {
    ProofObligation replacement =
        copyObligation(
            source,
            status,
            source.routeIds(),
            source.dependencyIds(),
            evidence,
            source.priority(),
            source.centrality());
    obligations.put(source.obligationId(), replacement);
    if (reverify) {
      needsReverify.add(source.obligationId());
    } else if ("closed".equals(status)) {
      needsReverify.remove(source.obligationId());
    }
    increment(source.obligationId());
    record(eventType, source.obligationId(), Map.of("status", status));
    return replacement;
  }

  private void rebuildProjectionAndIndexes() {
    structuralProjection.removeAllVertices(Set.copyOf(structuralProjection.vertexSet()));
    obligations.keySet().forEach(structuralProjection::addVertex);
    claimNodes.keySet().forEach(structuralProjection::addVertex);
    dependenciesBySource.clear();
    dependentsByTarget.clear();
    edgeIdsByIdentity.clear();
    for (ProofGraphEdge edge : edges.values()) {
      if (!containsNode(edge.sourceId()) || !containsNode(edge.targetId())) {
        continue;
      }
      if (ACYCLIC_RELATIONS.contains(edge.edgeType())) {
        structuralProjection.addEdge(edge.sourceId(), edge.targetId());
      }
      if (edge.edgeType() == GraphEdgeType.DEPENDS_ON) {
        dependenciesBySource
            .computeIfAbsent(edge.sourceId(), ignored -> new LinkedHashSet<>())
            .add(edge.targetId());
        dependentsByTarget
            .computeIfAbsent(edge.targetId(), ignored -> new LinkedHashSet<>())
            .add(edge.sourceId());
      }
      edgeIdsByIdentity.put(
          new EdgeIdentity(
              edge.sourceId(), edge.targetId(), edge.edgeType(), edge.evidenceMessageId()),
          edge.edgeId());
    }
  }

  private boolean pathExists(String start, String target) {
    if (start.equals(target)) {
      return true;
    }
    Deque<String> pending = new ArrayDeque<>();
    pending.add(start);
    Set<String> seen = new LinkedHashSet<>();
    while (!pending.isEmpty()) {
      String current = pending.removeFirst();
      if (!seen.add(current)) {
        continue;
      }
      for (DefaultEdge edge : structuralProjection.outgoingEdgesOf(current)) {
        String next = structuralProjection.getEdgeTarget(edge);
        if (next.equals(target)) {
          return true;
        }
        pending.add(next);
      }
    }
    return false;
  }

  private void removeObligationInternal(String obligationId) {
    ProofObligation removed = obligations.remove(obligationId);
    if (removed == null) {
      return;
    }
    List<String> index = contentIndex.get(removed.contentHash());
    if (index != null) {
      index.remove(obligationId);
      if (index.isEmpty()) {
        contentIndex.remove(removed.contentHash());
      }
    }
    structuralProjection.removeVertex(obligationId);
    versions.remove(obligationId);
    edges.entrySet().removeIf(
        entry ->
            entry.getValue().sourceId().equals(obligationId)
                || entry.getValue().targetId().equals(obligationId));
    rebuildProjectionAndIndexes();
  }

  private Map<String, Double> canonicalDebtWeights(
      String routeId, Boolean activeOnly) {
    Map<String, Double> result = new LinkedHashMap<>();
    for (CanonicalObligationRecord target : canonicalization.canonicalTargets()) {
      CanonicalObligationStatus status = canonicalStatus(target.canonicalTargetId());
      if (status == CanonicalObligationStatus.RESOLVED
          || status == CanonicalObligationStatus.REFUTED) {
        continue;
      }
      boolean active =
          target.schedulingState() == CanonicalObligationSchedulingState.ACTIVE;
      if (activeOnly != null && activeOnly.booleanValue() != active) {
        continue;
      }
      double maximum = 0.0d;
      for (String occurrenceId : target.occurrenceIds()) {
        ObligationOccurrenceRecord occurrence =
            canonicalization.snapshot().occurrences().get(occurrenceId);
        if (occurrence == null) {
          continue;
        }
        ProofObligation obligation = obligations.get(resolve(occurrence.obligationId()));
        if (obligation == null
            || (routeId != null && !obligation.routeIds().contains(routeId))) {
          continue;
        }
        maximum = Math.max(maximum, obligationDebtWeight(obligation));
      }
      if (maximum > 0.0d) {
        result.put(target.canonicalTargetId(), maximum);
      }
    }
    return Map.copyOf(result);
  }

  private double obligationDebtWeight(ProofObligation obligation) {
    double weight = policy.obligationBaseWeight();
    if (obligation.kind() == ObligationKind.MAIN_GOAL) {
      weight += policy.obligationMainGoalWeight();
    }
    weight += obligation.centrality() * policy.obligationCentralityWeight();
    weight +=
        dependentsByTarget.getOrDefault(obligation.obligationId(), Set.of()).size()
            * policy.obligationDependencyWeight();
    weight +=
        Math.max(0, Set.copyOf(obligation.routeIds()).size() - 1)
            * policy.obligationSharedRouteWeight();
    if (obligation.firstErrorFingerprint() != null) {
      weight += policy.obligationFailureWeight();
    }
    if (obligation.kind() == ObligationKind.CONTRADICTION
        || "blocked".equals(obligation.status())) {
      weight += policy.obligationConflictWeight();
    }
    return weight * Math.max(0.01d, obligation.priority());
  }

  private boolean isOperationallyOpen(CanonicalObligationRecord target) {
    CanonicalObligationStatus status = canonicalStatus(target.canonicalTargetId());
    return status == CanonicalObligationStatus.OPEN
        || status == CanonicalObligationStatus.MIXED;
  }

  private Comparator<CanonicalObligationRecord> canonicalTargetOrder() {
    return Comparator.comparing(
            (CanonicalObligationRecord target) ->
                target.signature().kind() != ObligationKind.MAIN_GOAL)
        .thenComparing(
            (CanonicalObligationRecord target) -> target.routeIds().size(),
            Comparator.reverseOrder())
        .thenComparing(CanonicalObligationRecord::canonicalTargetId);
  }

  private void requireNodeCapacity() {
    if (obligations.size() + claimNodes.size() >= policy.maxNodes()) {
      throw new IllegalStateException("proof graph node limit reached");
    }
  }

  private boolean containsNode(String nodeId) {
    return obligations.containsKey(nodeId) || claimNodes.containsKey(nodeId);
  }

  private ProofObligation requireObligation(String obligationId) {
    ProofObligation obligation = obligations.get(obligationId);
    if (obligation == null) {
      throw new IllegalArgumentException("unknown proof obligation: " + obligationId);
    }
    return obligation;
  }

  private void validateProblemHash(String value, String kind) {
    if (!problemHash.isEmpty() && !problemHash.equals(value)) {
      throw new IllegalArgumentException(kind + " problem_hash mismatch");
    }
    if (problemHash.isEmpty()) {
      problemHash = value;
    }
  }

  private void ensureMutable() {
    if (frozen) {
      throw new IllegalStateException("proof graph is frozen");
    }
  }

  private String resolve(String id) {
    String current = id;
    Set<String> seen = new LinkedHashSet<>();
    while (aliases.containsKey(current) && seen.add(current)) {
      current = aliases.get(current);
    }
    return current;
  }

  private void assertVersion(String obligationId, long expectedVersion) {
    if (version(obligationId) != expectedVersion) {
      throw new ProofGraphConflictException(obligationId, expectedVersion);
    }
  }

  private void increment(String subjectId) {
    versions.compute(subjectId, (key, value) -> value == null ? 0L : value + 1L);
  }

  private void record(
      String eventType, String subjectId, Map<String, String> details) {
    audit.add(
        new ProofGraphAuditEvent(
            audit.size() + 1L,
            eventType,
            subjectId,
            versions.getOrDefault(subjectId, 0L),
            details));
  }

  private static ProofObligation copyObligation(
      ProofObligation source,
      String status,
      List<String> routeIds,
      List<String> dependencyIds,
      List<String> evidenceMessageIds,
      double priority,
      double centrality) {
    return new ProofObligation(
        source.assumptions(),
        centrality,
        source.contentHash(),
        dependencyIds,
        source.dependencyRefs(),
        evidenceMessageIds,
        source.firstErrorFingerprint(),
        source.kind(),
        source.normalizedStatement(),
        source.obligationId(),
        priority,
        source.problemHash(),
        source.quantifiers(),
        routeIds,
        source.statement(),
        status);
  }

  private static String strongestStatus(String left, String right) {
    List<String> order = List.of("open", "tentative", "blocked", "closed", "refuted");
    return order.indexOf(left) >= order.indexOf(right) ? left : right;
  }

  private static <T> List<T> union(List<T> left, List<T> right) {
    Set<T> values = new LinkedHashSet<>(left);
    values.addAll(right);
    return List.copyOf(values);
  }

  private static Set<String> intersection(Set<String> left, Set<String> right) {
    Set<String> result = new LinkedHashSet<>(left);
    result.retainAll(right);
    return Set.copyOf(result);
  }

  private Map<String, Long> filteredVersions(
      Set<String> obligationIds, Set<String> claimIds) {
    Map<String, Long> result = new LinkedHashMap<>();
    obligationIds.forEach(id -> result.put(id, versions.getOrDefault(id, 0L)));
    claimIds.forEach(id -> result.put(id, versions.getOrDefault(id, 0L)));
    return Map.copyOf(result);
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  private record EdgeIdentity(
      String sourceId,
      String targetId,
      GraphEdgeType edgeType,
      String evidenceMessageId) {}
}
