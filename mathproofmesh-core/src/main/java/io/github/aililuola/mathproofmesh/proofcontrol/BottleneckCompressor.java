package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Compresses equivalent open obligations while preserving every source node. */
public final class BottleneckCompressor {
  public record Cluster(
      String id,
      List<String> memberIds,
      String canonicalObligationId,
      String canonicalStatement,
      List<String> sharedAssumptions,
      List<String> routeIds,
      double centrality,
      double proofDebt,
      Map<String, String> aliasMap,
      Map<String, ProofControlModels.ObligationStatus> memberStatuses,
      String status) {
    public Cluster {
      memberIds = List.copyOf(memberIds);
      sharedAssumptions = List.copyOf(sharedAssumptions);
      routeIds = List.copyOf(routeIds);
      aliasMap = Map.copyOf(aliasMap);
      memberStatuses = Map.copyOf(memberStatuses);
    }

    public boolean preservesAll(Set<String> originalIds) {
      return new LinkedHashSet<>(memberIds).equals(originalIds);
    }
  }

  public List<Cluster> compress(
      List<ProofControlModels.Obligation> obligations,
      Map<String, ProofControlModels.ScopeSignature> scopes,
      Map<String, DomainClassifier.ObligationClassification> domains,
      Map<String, SemanticQualityGate.Assessment> semanticQuality) {
    Map<String, List<ProofControlModels.Obligation>> groups = new LinkedHashMap<>();
    for (ProofControlModels.Obligation obligation : obligations) {
      if (!(obligation.status() == ProofControlModels.ObligationStatus.OPEN
          || obligation.status() == ProofControlModels.ObligationStatus.IN_PROGRESS)) {
        continue;
      }
      DomainClassifier.ObligationClassification domain = domains.get(obligation.id());
      if (domain != null
          && domain.domain() != ProofControlModels.ObligationDomain.MATHEMATICAL) {
        continue;
      }
      SemanticQualityGate.Assessment quality = semanticQuality.get(obligation.id());
      if (quality != null && !quality.eligibleForBottleneck()) {
        continue;
      }
      String key =
          ProofIdentity.obligationIdentityText(obligation.statement())
              + "|"
              + ProofIdentity.canonicalStrings(obligation.assumptions())
              + "|"
              + scopeKey(scopes.get(obligation.id()));
      groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(obligation);
    }
    List<Cluster> clusters = new ArrayList<>();
    for (List<ProofControlModels.Obligation> group : groups.values()) {
      if (group.size() < 2) {
        continue;
      }
      group.sort(
          Comparator.comparingDouble(ProofControlModels.Obligation::centrality)
              .reversed()
              .thenComparing(ProofControlModels.Obligation::id));
      ProofControlModels.Obligation canonical = group.getFirst();
      List<String> members =
          group.stream().map(ProofControlModels.Obligation::id).sorted().toList();
      Set<String> shared = new LinkedHashSet<>(group.getFirst().assumptions());
      for (ProofControlModels.Obligation obligation : group) {
        shared.retainAll(obligation.assumptions());
      }
      List<String> routes =
          group.stream().flatMap(value -> value.routeIds().stream()).distinct().sorted().toList();
      Map<String, String> aliases = new LinkedHashMap<>();
      Map<String, ProofControlModels.ObligationStatus> statuses = new LinkedHashMap<>();
      for (ProofControlModels.Obligation obligation : group) {
        aliases.put(obligation.id(), canonical.id());
        statuses.put(obligation.id(), obligation.status());
      }
      String id =
          "bottleneck_cluster_"
              + CanonicalJson.stableHash(
                      Map.of(
                          "statement",
                              ProofIdentity.obligationIdentityText(canonical.statement()),
                          "members", members,
                          "scope", scopeKey(scopes.get(canonical.id()))))
                  .substring(0, 20);
      clusters.add(
          new Cluster(
              id,
              members,
              canonical.id(),
              canonical.statement(),
              shared.stream().sorted().toList(),
              routes,
              Math.min(
                  1.0d,
                  group.stream()
                      .mapToDouble(ProofControlModels.Obligation::centrality)
                      .average()
                      .orElse(0.0d)),
              group.stream()
                  .mapToDouble(
                      value -> value.priority() * (1.0d + value.centrality()))
                  .sum(),
              aliases,
              statuses,
              "active"));
    }
    return clusters.stream().sorted(Comparator.comparing(Cluster::id)).toList();
  }

  public Cluster refresh(
      Cluster cluster, Map<String, ProofControlModels.ObligationStatus> currentStatuses) {
    Map<String, ProofControlModels.ObligationStatus> statuses = new LinkedHashMap<>();
    for (String id : cluster.memberIds()) {
      statuses.put(id, currentStatuses.getOrDefault(id, cluster.memberStatuses().get(id)));
    }
    boolean resolved =
        statuses.values().stream()
            .allMatch(
                value ->
                    value == ProofControlModels.ObligationStatus.CLOSED
                        || value == ProofControlModels.ObligationStatus.REFUTED);
    return new Cluster(
        cluster.id(),
        cluster.memberIds(),
        cluster.canonicalObligationId(),
        cluster.canonicalStatement(),
        cluster.sharedAssumptions(),
        cluster.routeIds(),
        cluster.centrality(),
        cluster.proofDebt(),
        cluster.aliasMap(),
        statuses,
        resolved ? "resolved" : "active");
  }

  private static String scopeKey(ProofControlModels.ScopeSignature scope) {
    return scope == null
        ? "unknown"
        : String.join(
            ":",
            scope.indexScope().name().toLowerCase(Locale.ROOT),
            scope.uniformity().name().toLowerCase(Locale.ROOT),
            scope.objectScope().name().toLowerCase(Locale.ROOT),
            scope.quantifiers().toString());
  }
}
