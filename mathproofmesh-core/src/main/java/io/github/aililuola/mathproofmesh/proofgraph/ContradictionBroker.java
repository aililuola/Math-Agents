package io.github.aililuola.mathproofmesh.proofgraph;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ContradictionBroker {
  private final ContradictionPolicy policy;
  private final ProofGraphStore graph;
  private final List<ContradictionRecord> records = new ArrayList<>();
  private final Set<List<String>> seenPairs = new LinkedHashSet<>();

  public ContradictionBroker(
      ContradictionPolicy policy, ProofGraphStore graph) {
    this.policy = java.util.Objects.requireNonNull(policy, "policy");
    this.graph = java.util.Objects.requireNonNull(graph, "graph");
  }

  public synchronized List<ContradictionRecord> detect(
      Collection<MessageEnvelope> messages) {
    if (!policy.enabled() || policy.maxTasksPerRound() == 0) {
      return List.of();
    }
    List<MessageEnvelope> candidates = List.copyOf(messages);
    List<ContradictionRecord> created = new ArrayList<>();
    for (int leftIndex = 0; leftIndex < candidates.size(); leftIndex++) {
      MessageEnvelope left = candidates.get(leftIndex);
      for (int rightIndex = leftIndex + 1; rightIndex < candidates.size(); rightIndex++) {
        MessageEnvelope right = candidates.get(rightIndex);
        List<String> pair =
            java.util.stream.Stream.of(left.messageId(), right.messageId())
                .sorted()
                .toList();
        if (seenPairs.contains(pair)) {
          continue;
        }
        String reason = conflictReason(left, right);
        if (reason == null) {
          continue;
        }
        seenPairs.add(pair);
        MessageEnvelope counterexample =
            java.util.stream.Stream.of(left, right)
                .filter(item -> item.evidenceType() == EvidenceType.COUNTEREXAMPLE)
                .findFirst()
                .orElse(null);
        Set<String> routes = new LinkedHashSet<>();
        routes.add(left.sourceRouteId());
        routes.add(right.sourceRouteId());
        routes.addAll(left.targetRouteIds());
        routes.addAll(right.targetRouteIds());
        double centrality =
            graph.obligations().stream()
                .filter(
                    item ->
                        item.normalizedStatement().equals(left.normalizedStatement()))
                .mapToDouble(ProofObligation::centrality)
                .max()
                .orElse(0.0);
        String contradictionId =
            "conflict-"
                + CanonicalJson.stableHash(
                        Map.of(
                            "messages", pair,
                            "statement", left.normalizedStatement()))
                    .substring(0, 24);
        ContradictionRecord record =
            new ContradictionRecord(
                contradictionId,
                pair,
                routes.stream().sorted().toList(),
                left.normalizedStatement(),
                reason,
                counterexample == null ? "open" : "resolved",
                counterexample == null ? null : counterexample.messageId(),
                centrality);
        records.add(record);
        created.add(record);
        graph.recordExternal(
            "contradiction_detected",
            contradictionId,
            Map.of("message_ids", String.join(",", pair), "reason", reason));
        if ("open".equals(record.status())) {
          blockRelated(record);
        }
        if (created.size() >= policy.maxTasksPerRound()) {
          return List.copyOf(created);
        }
      }
    }
    return List.copyOf(created);
  }

  public synchronized ContradictionRecord resolve(
      String contradictionId,
      MessageEnvelope resolution,
      String reviewerAgentId) {
    ContradictionRecord record =
        records.stream()
            .filter(item -> item.contradictionId().equals(contradictionId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("unknown contradiction"));
    if (resolution.messageType() != MessageType.CONTRADICTION_NOTICE
        || resolution.verificationStatus() != ClaimStatus.VERIFIED) {
      throw new IllegalArgumentException(
          "contradictions require an independently reviewed resolution");
    }
    if (reviewerAgentId == null
        || reviewerAgentId.isBlank()
        || reviewerAgentId.equals(resolution.sourceAgentId())) {
      throw new IllegalArgumentException(
          "contradiction resolution reviewer must be independent");
    }
    graph.addClaimNode(resolution);
    ContradictionRecord resolved =
        new ContradictionRecord(
            record.contradictionId(),
            record.messageIds(),
            record.routeIds(),
            record.normalizedStatement(),
            record.reason(),
            "resolved",
            resolution.messageId(),
            record.centrality());
    records.set(records.indexOf(record), resolved);
    graph.reopenBlockedByStatement(record.normalizedStatement());
    graph.recordExternal(
        "contradiction_resolved",
        contradictionId,
        Map.of("resolution_message_id", resolution.messageId()));
    return resolved;
  }

  public synchronized List<ContradictionRecord> unresolved() {
    return records.stream().filter(item -> "open".equals(item.status())).toList();
  }

  public synchronized List<ContradictionRecord> records() {
    return List.copyOf(records);
  }

  private void blockRelated(ContradictionRecord record) {
    for (ProofObligation obligation : graph.obligations()) {
      if (obligation.normalizedStatement().equals(record.normalizedStatement())
          && !"refuted".equals(obligation.status())) {
        graph.markBlocked(obligation.obligationId(), "contradiction_open");
      }
    }
    graph.addObligation(
        new ProofObligation(
            List.of(),
            record.centrality(),
            "",
            List.of(),
            List.of(),
            List.of(),
            null,
            ObligationKind.CONTRADICTION,
            "resolve:" + record.normalizedStatement(),
            "conflict-obligation-" + record.contradictionId(),
            Math.max(0.8, record.centrality()),
            graph.problemHash(),
            List.of(),
            record.routeIds(),
            "Resolve contradiction: " + record.normalizedStatement(),
            "open"));
  }

  private static String conflictReason(
      MessageEnvelope left, MessageEnvelope right) {
    if (!left.assumptions().equals(right.assumptions())
        || !left.quantifiers().equals(right.quantifiers())
        || !left.scopeLimitations().equals(right.scopeLimitations())) {
      return null;
    }
    boolean sameStatement =
        left.normalizedStatement().equals(right.normalizedStatement());
    Set<ClaimStatus> statuses = new LinkedHashSet<>();
    statuses.add(left.verificationStatus());
    statuses.add(right.verificationStatus());
    if (sameStatement
        && statuses.equals(Set.of(ClaimStatus.VERIFIED, ClaimStatus.REJECTED))) {
      return "the same scoped statement is both verified and rejected";
    }
    if (sameStatement
        && (left.evidenceType() == EvidenceType.COUNTEREXAMPLE
            || right.evidenceType() == EvidenceType.COUNTEREXAMPLE)) {
      return "an exact counterexample refutes the scoped statement";
    }
    if (left.conclusion().equals("not (" + right.conclusion() + ")")
        || right.conclusion().equals("not (" + left.conclusion() + ")")) {
      return "mutually exclusive conclusions under identical assumptions";
    }
    return null;
  }
}
