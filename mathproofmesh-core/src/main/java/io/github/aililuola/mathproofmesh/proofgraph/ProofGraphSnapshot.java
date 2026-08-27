package io.github.aililuola.mathproofmesh.proofgraph;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.ProofGraphEdge;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification =
        "The compact constructor converts every collection to an immutable defensive copy.")
public record ProofGraphSnapshot(
    String problemHash,
    boolean frozen,
    Map<String, ProofObligation> obligations,
    Map<String, MessageEnvelope> claimNodes,
    Map<String, ProofGraphEdge> edges,
    Map<String, String> aliases,
    Set<String> needsReverify,
    Map<String, Long> versions,
    List<ProofGraphAuditEvent> audit,
    ObligationCanonicalizationSnapshot canonicalization) {

  public ProofGraphSnapshot {
    problemHash = problemHash == null ? "" : problemHash;
    obligations = copy(obligations);
    claimNodes = copy(claimNodes);
    edges = copy(edges);
    aliases = copy(aliases);
    needsReverify = needsReverify == null ? Set.of() : Set.copyOf(needsReverify);
    versions = copy(versions);
    audit = audit == null ? List.of() : List.copyOf(audit);
    canonicalization =
        canonicalization == null
            ? ObligationCanonicalizationSnapshot.empty()
            : canonicalization;
  }

  public ProofGraphSnapshot(
      String problemHash,
      boolean frozen,
      Map<String, ProofObligation> obligations,
      Map<String, MessageEnvelope> claimNodes,
      Map<String, ProofGraphEdge> edges,
      Map<String, String> aliases,
      Set<String> needsReverify,
      Map<String, Long> versions,
      List<ProofGraphAuditEvent> audit) {
    this(
        problemHash,
        frozen,
        obligations,
        claimNodes,
        edges,
        aliases,
        needsReverify,
        versions,
        audit,
        ObligationCanonicalizationSnapshot.empty());
  }

  private static <K, V> Map<K, V> copy(Map<K, V> value) {
    return value == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(value));
  }
}
