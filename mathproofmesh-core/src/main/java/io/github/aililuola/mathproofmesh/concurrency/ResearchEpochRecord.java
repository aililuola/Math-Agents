package io.github.aililuola.mathproofmesh.concurrency;

import java.util.List;
import java.util.Objects;

public record ResearchEpochRecord(
    String epochId,
    String snapshotHash,
    ResearchEpochStatus status,
    List<String> workItemIds,
    List<String> durableResultIds,
    String mergePlanHash,
    ResearchAuthorityAnchor authority,
    ResearchAuthorityCommitProtocol authorityCommitProtocol,
    String authorityHashAfterCommit,
    long version) {
  public ResearchEpochRecord {
    epochId = text(epochId, "epochId");
    snapshotHash = text(snapshotHash, "snapshotHash");
    status = Objects.requireNonNull(status, "status");
    workItemIds = workItemIds == null ? List.of() : workItemIds.stream().distinct().sorted().toList();
    durableResultIds =
        durableResultIds == null ? List.of() : durableResultIds.stream().distinct().sorted().toList();
    mergePlanHash = mergePlanHash == null ? "" : mergePlanHash.strip();
    authorityHashAfterCommit =
        authorityHashAfterCommit == null ? "" : authorityHashAfterCommit.strip();
    if (version < 1L) {
      throw new IllegalArgumentException("version must be positive");
    }
  }

  /** Source-compatible constructor for callers without the frozen authority body. */
  public ResearchEpochRecord(
      String epochId,
      String snapshotHash,
      ResearchEpochStatus status,
      List<String> workItemIds,
      List<String> durableResultIds,
      String mergePlanHash,
      long version) {
    this(
        epochId,
        snapshotHash,
        status,
        workItemIds,
        durableResultIds,
        mergePlanHash,
        null,
        ResearchAuthorityCommitProtocol.RECEIPT_V1,
        "",
        version);
  }

  /** Source-compatible constructor for callers created before commit protocols were durable. */
  public ResearchEpochRecord(
      String epochId,
      String snapshotHash,
      ResearchEpochStatus status,
      List<String> workItemIds,
      List<String> durableResultIds,
      String mergePlanHash,
      ResearchAuthorityAnchor authority,
      long version) {
    this(
        epochId,
        snapshotHash,
        status,
        workItemIds,
        durableResultIds,
        mergePlanHash,
        authority,
        ResearchAuthorityCommitProtocol.RECEIPT_V1,
        "",
        version);
  }

  public ResearchEpochRecord transition(
      ResearchEpochStatus next, List<String> resultIds, String nextMergePlanHash) {
    Objects.requireNonNull(next, "next");
    if (!allowed(status, next)) {
      throw new IllegalStateException("invalid epoch transition " + status + " -> " + next);
    }
    return new ResearchEpochRecord(
        epochId,
        snapshotHash,
        next,
        workItemIds,
        resultIds == null ? durableResultIds : resultIds,
        nextMergePlanHash == null ? mergePlanHash : nextMergePlanHash,
        authority,
        authorityCommitProtocol,
        authorityHashAfterCommit,
        version + 1L);
  }

  public ResearchEpochRecord withAuthorityCommitProtocol(
      ResearchAuthorityCommitProtocol protocol) {
    return new ResearchEpochRecord(
        epochId,
        snapshotHash,
        status,
        workItemIds,
        durableResultIds,
        mergePlanHash,
        authority,
        Objects.requireNonNull(protocol, "protocol"),
        authorityHashAfterCommit,
        version);
  }

  public ResearchEpochRecord withAuthorityHashAfterCommit(String authorityHash) {
    if (status != ResearchEpochStatus.COMMITTED) {
      throw new IllegalStateException("only a committed epoch can bind authorityHashAfterCommit");
    }
    return new ResearchEpochRecord(
        epochId,
        snapshotHash,
        status,
        workItemIds,
        durableResultIds,
        mergePlanHash,
        authority,
        authorityCommitProtocol,
        text(authorityHash, "authorityHashAfterCommit"),
        version);
  }

  @Override
  public List<String> workItemIds() {
    return List.copyOf(workItemIds);
  }

  @Override
  public List<String> durableResultIds() {
    return List.copyOf(durableResultIds);
  }

  private static boolean allowed(ResearchEpochStatus prior, ResearchEpochStatus next) {
    if (prior == next) {
      return true;
    }
    if (next == ResearchEpochStatus.ABORTED
        || next == ResearchEpochStatus.QUARANTINED
        || next == ResearchEpochStatus.STALE_SNAPSHOT) {
      return prior != ResearchEpochStatus.COMMITTED;
    }
    return switch (prior) {
      case PLANNED -> next == ResearchEpochStatus.DISPATCHING;
      case DISPATCHING -> next == ResearchEpochStatus.ALL_SETTLED;
      case ALL_SETTLED -> next == ResearchEpochStatus.MERGE_PREPARED;
      case MERGE_PREPARED -> next == ResearchEpochStatus.COMMITTED;
      default -> false;
    };
  }

  private static String text(String value, String label) {
    Objects.requireNonNull(value, label);
    String normalized = value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return normalized;
  }
}
