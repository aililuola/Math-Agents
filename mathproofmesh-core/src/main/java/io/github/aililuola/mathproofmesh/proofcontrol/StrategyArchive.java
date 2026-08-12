package io.github.aililuola.mathproofmesh.proofcontrol;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Preserves original domain strategies and auditable rewrite lineage. */
@SuppressFBWarnings(
    value = "IMPROPER_UNICODE",
    justification =
        "Mechanism allowlist comparison uses normalized text and Locale.ROOT deterministically")
public final class StrategyArchive {
  public enum RevisionReason {
    ADMISSION_REWRITE,
    PLAN_FAILURE,
    SCOPE_REPAIR,
    BRIDGE_INSERTION,
    COUNTERMODEL_FEEDBACK,
    USER_INTERVENTION
  }

  public record Entry(
      ProofControlModels.Strategy strategy,
      String rawArtifactRef,
      List<String> mechanismTags,
      List<String> domainObjects,
      int firstSeenRound) {
    public Entry {
      mechanismTags = List.copyOf(mechanismTags);
      domainObjects = List.copyOf(domainObjects);
    }
  }

  public record Lineage(
      String strategyId,
      String parentStrategyId,
      String rootStrategyId,
      int revision,
      RevisionReason reason,
      List<String> preservedMechanismTags,
      List<String> preservedDomainObjects,
      String status) {
    public Lineage {
      preservedMechanismTags = List.copyOf(preservedMechanismTags);
      preservedDomainObjects = List.copyOf(preservedDomainObjects);
    }
  }

  public record Snapshot(Map<String, Entry> originals, Map<String, Lineage> lineage) {
    public Snapshot {
      originals = originals == null ? Map.of() : Map.copyOf(originals);
      lineage = lineage == null ? Map.of() : Map.copyOf(lineage);
    }
  }

  private final Map<String, Entry> originals = new LinkedHashMap<>();
  private final Map<String, Lineage> lineage = new LinkedHashMap<>();

  public Entry archive(
      ProofControlModels.Strategy strategy, String rawArtifactRef, int round) {
    return originals.computeIfAbsent(
        strategy.id(),
        ignored -> {
          List<String> tags =
              ProofIdentity.canonicalStrings(
                  List.of(strategy.mechanism(), strategy.title()));
          Entry entry =
              new Entry(
                  strategy,
                  ProofControlModels.required(rawArtifactRef, "rawArtifactRef"),
                  tags,
                  strategy.domainObjects(),
                  round);
          lineage.put(
              strategy.id(),
              new Lineage(
                  strategy.id(),
                  null,
                  strategy.id(),
                  0,
                  null,
                  tags,
                  strategy.domainObjects(),
                  "original"));
          return entry;
        });
  }

  public Lineage registerChild(
      ProofControlModels.Strategy child,
      String parentId,
      RevisionReason reason) {
    Lineage parent =
        java.util.Objects.requireNonNull(lineage.get(parentId), "unknown parent strategy");
    Entry root =
        java.util.Objects.requireNonNull(
            originals.get(parent.rootStrategyId()), "missing original strategy");
    ensurePreserved(child, root);
    Lineage record =
        new Lineage(
            child.id(),
            parentId,
            parent.rootStrategyId(),
            parent.revision() + 1,
            java.util.Objects.requireNonNull(reason, "reason"),
            root.mechanismTags(),
            root.domainObjects(),
            "active");
    lineage.putIfAbsent(child.id(), record);
    return lineage.get(child.id());
  }

  public Lineage rejectChild(String childId, String evidenceId) {
    ProofControlModels.required(evidenceId, "evidenceId");
    Lineage current =
        java.util.Objects.requireNonNull(lineage.get(childId), "unknown child strategy");
    Lineage rejected =
        new Lineage(
            current.strategyId(),
            current.parentStrategyId(),
            current.rootStrategyId(),
            current.revision(),
            current.reason(),
            current.preservedMechanismTags(),
            current.preservedDomainObjects(),
            "rejected_with_evidence");
    lineage.put(childId, rejected);
    return rejected;
  }

  public boolean originalRetained(String strategyId) {
    return originals.containsKey(strategyId)
        && "original".equals(lineage.get(strategyId).status());
  }

  public Map<String, Lineage> lineage() {
    return Map.copyOf(lineage);
  }

  public Snapshot snapshot() {
    return new Snapshot(originals, lineage);
  }

  public void restore(Snapshot snapshot) {
    java.util.Objects.requireNonNull(snapshot, "snapshot");
    snapshot.originals().forEach(
        (id, entry) -> {
          if (!id.equals(entry.strategy().id())) {
            throw new IllegalArgumentException("strategy archive original id mismatch");
          }
          Lineage root = snapshot.lineage().get(id);
          if (root == null
              || root.parentStrategyId() != null
              || !id.equals(root.rootStrategyId())
              || root.revision() != 0) {
            throw new IllegalArgumentException("strategy archive root lineage is invalid");
          }
        });
    snapshot.lineage().forEach(
        (id, record) -> {
          if (!id.equals(record.strategyId())
              || !snapshot.originals().containsKey(record.rootStrategyId())) {
            throw new IllegalArgumentException("strategy archive lineage id is invalid");
          }
          if (record.parentStrategyId() != null) {
            Lineage parent = snapshot.lineage().get(record.parentStrategyId());
            if (parent == null
                || !record.rootStrategyId().equals(parent.rootStrategyId())
                || record.revision() != parent.revision() + 1) {
              throw new IllegalArgumentException("strategy archive parent lineage is invalid");
            }
          }
        });
    originals.clear();
    originals.putAll(snapshot.originals());
    lineage.clear();
    lineage.putAll(snapshot.lineage());
  }

  private static void ensurePreserved(
      ProofControlModels.Strategy child, Entry root) {
    String mechanism =
        ProofIdentity.normalizeText(child.mechanism()).toLowerCase(java.util.Locale.ROOT);
    boolean generic =
        mechanism.equals("generic fallback")
            || mechanism.equals("try something else")
            || mechanism.equals("direct proof");
    if (generic || child.domainObjects().isEmpty()
        || !new LinkedHashSet<>(child.domainObjects()).containsAll(root.domainObjects())) {
      throw new IllegalArgumentException(
          "rewrite must preserve the original domain mechanism and objects");
    }
    String fingerprint =
        CanonicalJson.stableHash(
            Map.of(
                "mechanism", child.mechanism(),
                "objects", child.domainObjects()));
    if (fingerprint.isBlank()) {
      throw new IllegalStateException("unreachable empty strategy fingerprint");
    }
  }
}
