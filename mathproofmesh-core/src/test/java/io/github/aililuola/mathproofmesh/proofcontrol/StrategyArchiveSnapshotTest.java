package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class StrategyArchiveSnapshotTest {
  @Test
  void restoresOriginalAndRevisionLineageForCheckpointContinuation() {
    StrategyArchive source = new StrategyArchive();
    ProofControlModels.Strategy original = strategy("strategy-root", "route-1");
    ProofControlModels.Strategy revision = strategy("strategy-revision", "route-1");
    source.archive(original, "strategy://strategy-root", 0);
    source.registerChild(
        revision, original.id(), StrategyArchive.RevisionReason.BRIDGE_INSERTION);

    StrategyArchive restored = new StrategyArchive();
    restored.restore(source.snapshot());

    assertTrue(restored.originalRetained(original.id()));
    assertEquals(original.id(), restored.lineage().get(revision.id()).parentStrategyId());
    ProofControlModels.Strategy secondRevision = strategy("strategy-revision-2", "route-1");
    assertEquals(
        2,
        restored
            .registerChild(
                secondRevision,
                revision.id(),
                StrategyArchive.RevisionReason.PLAN_FAILURE)
            .revision());
  }

  @Test
  void snapshotNormalizesNullCollections() {
    StrategyArchive.Snapshot snapshot = new StrategyArchive.Snapshot(null, null);

    assertTrue(snapshot.originals().isEmpty());
    assertTrue(snapshot.lineage().isEmpty());
  }

  @Test
  void rejectsRevisionsThatEraseTheOriginalMechanismOrDomainObjects() {
    StrategyArchive archive = new StrategyArchive();
    ProofControlModels.Strategy original = strategy("strategy-root", "route-1");
    archive.archive(original, "strategy://strategy-root", 0);

    for (String generic :
        List.of("generic fallback", "try something else", "direct proof")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              archive.registerChild(
                  strategy("generic-" + generic.replace(' ', '-'), "route-1", generic,
                      original.domainObjects()),
                  original.id(),
                  StrategyArchive.RevisionReason.ADMISSION_REWRITE));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            archive.registerChild(
                strategy("empty-domain", "route-1", original.mechanism(), List.of()),
                original.id(),
                StrategyArchive.RevisionReason.ADMISSION_REWRITE));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            archive.registerChild(
                strategy(
                    "missing-domain-object",
                    "route-1",
                    original.mechanism(),
                    List.of("sequence", "state")),
                original.id(),
                StrategyArchive.RevisionReason.ADMISSION_REWRITE));
  }

  @Test
  void originalRetentionTracksRejectionAndUnknownStrategies() {
    StrategyArchive archive = new StrategyArchive();
    ProofControlModels.Strategy original = strategy("strategy-root", "route-1");
    archive.archive(original, "strategy://strategy-root", 0);

    assertFalse(archive.originalRetained("unknown"));
    archive.rejectChild(original.id(), "evidence://rejection");
    assertFalse(archive.originalRetained(original.id()));
  }

  @Test
  void restoreRejectsMalformedRootLineage() {
    StrategyArchive source = new StrategyArchive();
    ProofControlModels.Strategy original = strategy("strategy-root", "route-1");
    source.archive(original, "strategy://strategy-root", 0);
    StrategyArchive.Snapshot valid = source.snapshot();
    StrategyArchive.Entry entry = valid.originals().get(original.id());
    StrategyArchive.Lineage root = valid.lineage().get(original.id());

    assertInvalidSnapshot(
        new StrategyArchive.Snapshot(Map.of("wrong-id", entry), Map.of()));
    assertInvalidSnapshot(
        new StrategyArchive.Snapshot(Map.of(original.id(), entry), Map.of()));
    assertInvalidSnapshot(
        new StrategyArchive.Snapshot(
            Map.of(original.id(), entry),
            Map.of(original.id(), lineage(root, original.id(), original.id(), 0))));
    assertInvalidSnapshot(
        new StrategyArchive.Snapshot(
            Map.of(original.id(), entry),
            Map.of(original.id(), lineage(root, null, "different-root", 0))));
    assertInvalidSnapshot(
        new StrategyArchive.Snapshot(
            Map.of(original.id(), entry),
            Map.of(original.id(), lineage(root, null, original.id(), 1))));
  }

  @Test
  void restoreRejectsMalformedChildLineage() {
    StrategyArchive source = new StrategyArchive();
    ProofControlModels.Strategy original = strategy("strategy-root", "route-1");
    ProofControlModels.Strategy child = strategy("strategy-child", "route-1");
    source.archive(original, "strategy://strategy-root", 0);
    source.registerChild(child, original.id(), StrategyArchive.RevisionReason.PLAN_FAILURE);
    StrategyArchive.Snapshot valid = source.snapshot();
    StrategyArchive.Entry rootEntry = valid.originals().get(original.id());
    StrategyArchive.Lineage root = valid.lineage().get(original.id());
    StrategyArchive.Lineage revision = valid.lineage().get(child.id());

    assertInvalidSnapshot(
        new StrategyArchive.Snapshot(
            Map.of(original.id(), rootEntry),
            Map.of(original.id(), root, "wrong-child-id", revision)));
    assertInvalidSnapshot(
        new StrategyArchive.Snapshot(
            Map.of(original.id(), rootEntry),
            Map.of(
                original.id(), root,
                child.id(), lineage(revision, original.id(), "unknown-root", 1))));
    assertInvalidSnapshot(
        new StrategyArchive.Snapshot(
            Map.of(original.id(), rootEntry),
            Map.of(
                original.id(), root,
                child.id(), lineage(revision, "unknown-parent", original.id(), 1))));
    assertInvalidSnapshot(
        new StrategyArchive.Snapshot(
            Map.of(original.id(), rootEntry),
            Map.of(
                original.id(), root,
                child.id(), lineage(revision, original.id(), original.id(), 3))));
  }

  private static void assertInvalidSnapshot(StrategyArchive.Snapshot snapshot) {
    assertThrows(IllegalArgumentException.class, () -> new StrategyArchive().restore(snapshot));
  }

  private static StrategyArchive.Lineage lineage(
      StrategyArchive.Lineage source, String parentId, String rootId, int revision) {
    return new StrategyArchive.Lineage(
        source.strategyId(),
        parentId,
        rootId,
        revision,
        source.reason(),
        source.preservedMechanismTags(),
        source.preservedDomainObjects(),
        source.status());
  }

  private static ProofControlModels.Strategy strategy(String id, String routeId) {
    return strategy(
        id,
        routeId,
        "finite-state transition",
        List.of("sequence", "state", "transition"));
  }

  private static ProofControlModels.Strategy strategy(
      String id, String routeId, String mechanism, List<String> domainObjects) {
    return new ProofControlModels.Strategy(
        id,
        "Finite-state route",
        mechanism,
        List.of("bounded gap lemma"),
        List.of("the transition is determined by finite state"),
        List.of("finite state implies eventual translation periodicity"),
        List.of("seek two equal states with unequal successors"),
        domainObjects,
        routeId);
  }
}
