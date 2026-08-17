package io.github.aililuola.mathproofmesh.runstate;

public record RunStateAnchor(
    long authoritySequence, String authorityHash, String executionAttemptId) {
  public RunStateAnchor {
    authorityHash = RunStateHashes.optional(authorityHash);
    executionAttemptId = RunStateHashes.optional(executionAttemptId);
    if (authoritySequence < 0L) {
      throw new IllegalArgumentException("authoritySequence must not be negative");
    }
  }

  public static RunStateAnchor empty() {
    return new RunStateAnchor(0L, "", "");
  }
}
