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
  private final Graph<String, DefaultEdge> structuralProjection =
      new DirectedMultigraph<>(DefaultEdge.class);
  private String problemHash;
  private boolean frozen;
  private NegativeAwareProofGraphWriter negativeAwareWriter;

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
    ProofObligation existing = obligations.get(resolve(obligation.obligationId()));
    if (existing != null) {
      if (!existing.contentHash().equals(obligation.contentHash())) {
        throw new IllegalArgumentException("obligation ID collision");
      }
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

    Set<String> routes = Set.copyOf(obligation.routeIds());
    Optional<ProofObligation> duplicate =
        contentIndex.getOrDefault(obligation.contentHash(), List.of()).stream()
            .map(obligations::get)
            .filter(java.util.Objects::nonNull)
            .filter(item -> item.routeIds().stream().anyMatch(routes::contains))
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
      return merged;
    }
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
    } catch (RuntimeException exception) {
      removeObligationInternal(obligation.obligationId());
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
    List<ProofObligation> open =
        obligations.values().stream()
            .filter(item -> Set.of("open", "tentative", "blocked").contains(item.status()))
            .toList();
    Set<String> consumed = new LinkedHashSet<>();
    List<List<ProofObligation>> result = new ArrayList<>();
    for (ProofObligation candidate : open) {
      if (consumed.contains(candidate.obligationId())) {
        continue;
      }
      List<ProofObligation> group = new ArrayList<>();
      group.add(candidate);
      open.stream()
          .filter(item -> !item.obligationId().equals(candidate.obligationId()))
          .filter(item -> item.problemHash().equals(candidate.problemHash()))
          .filter(item -> item.assumptions().equals(candidate.assumptions()))
          .filter(
              item ->
                  MathTextSimilarity.statementSimilarity(
                          item.normalizedStatement(), candidate.normalizedStatement())
                      >= policy.bridgeSimilarityThreshold())
          .forEach(group::add);
      Set<String> routes = new LinkedHashSet<>();
      group.forEach(item -> routes.addAll(item.routeIds()));
      if (routes.size() >= minRoutes) {
        result.add(List.copyOf(group));
        group.stream().map(ProofObligation::obligationId).forEach(consumed::add);
      }
    }
    return List.copyOf(result);
  }

  public synchronized double proofDebt(String routeId) {
    double debt = 0.0;
    for (ProofObligation obligation : obligations.values()) {
      if (!obligation.routeIds().contains(routeId)
          || "closed".equals(obligation.status())) {
        continue;
      }
      double weight = policy.obligationBaseWeight();
      if (obligation.kind() == ObligationKind.MAIN_GOAL) {
        weight += policy.obligationMainGoalWeight();
      }
      weight += obligation.centrality() * policy.obligationCentralityWeight();
      weight +=
          dependentsByTarget
                  .getOrDefault(obligation.obligationId(), Set.of())
                  .size()
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
      debt += weight * Math.max(0.01, obligation.priority());
    }
    return debt;
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
        audit);
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
        audit);
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
