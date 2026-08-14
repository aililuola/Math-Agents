package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds ID-insensitive structural signatures from admitted production strategy state. */
public final class PivotStructuralSignatureFactory {
  public PivotStructuralSignature create(
      StrategyCard strategy,
      Set<String> activeObjectIds,
      Set<String> activeCanonicalTargetIds,
      Set<String> activeAssumptions,
      Set<String> retainedVerifiedClaimIds,
      Set<String> proposedClaims,
      Set<String> activeObligations,
      String directionSignature,
      StrategyBlueprintCompiler.Compilation compilation) {
    java.util.Objects.requireNonNull(strategy, "strategy");
    return new PivotStructuralSignature(
        strategy.strategyId(),
        normalizeSet(activeObjectIds),
        normalizeSet(activeCanonicalTargetIds),
        hashSet(activeAssumptions),
        normalizeSet(retainedVerifiedClaimIds),
        hashSet(proposedClaims),
        hashSet(activeObligations),
        directionSignature,
        blueprintHash(compilation));
  }

  public String blueprintHash(StrategyBlueprintCompiler.Compilation compilation) {
    if (compilation == null) {
      return CanonicalJson.stableHash(Map.of("nodes", List.of(), "edges", List.of()));
    }
    StrategyBlueprintCompiler.Blueprint blueprint = compilation.blueprint();
    Map<String, String> statements = new LinkedHashMap<>();
    blueprint.nodes().forEach(
        node -> statements.put(node.id(), ProofIdentity.normalizeText(node.statement())));
    List<Map<String, Object>> nodes =
        blueprint.nodes().stream()
            .map(
                node ->
                    Map.<String, Object>of(
                        "kind", node.kind().name(),
                        "statement", ProofIdentity.normalizeText(node.statement()),
                        "source", node.sourceField()))
            .sorted(java.util.Comparator.comparing(CanonicalJson::canonicalize))
            .toList();
    List<Map<String, Object>> edges =
        blueprint.edges().stream()
            .map(
                edge ->
                    Map.<String, Object>of(
                        "source", statements.getOrDefault(edge.sourceId(), edge.sourceId()),
                        "target", statements.getOrDefault(edge.targetId(), edge.targetId()),
                        "relation", edge.relation(),
                        "outline", ProofIdentity.canonicalStrings(edge.implicationOutline())))
            .sorted(java.util.Comparator.comparing(CanonicalJson::canonicalize))
            .toList();
    return CanonicalJson.stableHash(Map.of("nodes", nodes, "edges", edges));
  }

  private static Set<String> normalizeSet(Set<String> values) {
    if (values == null) {
      return Set.of();
    }
    LinkedHashSet<String> result = new LinkedHashSet<>();
    values.stream()
        .map(ProofIdentity::normalizeText)
        .filter(value -> !value.isBlank())
        .sorted()
        .forEach(result::add);
    return Set.copyOf(result);
  }

  private static Set<String> hashSet(Set<String> values) {
    if (values == null) {
      return Set.of();
    }
    return values.stream()
        .map(ProofIdentity::normalizeText)
        .filter(value -> !value.isBlank())
        .map(CanonicalJson::stableHash)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }
}
