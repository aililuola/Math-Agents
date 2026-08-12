package io.github.aililuola.mathproofmesh.orchestration;

/** Auditable completion record for a deep-exploration lease. */
public record ExplorationAttemptRecord(
    String leaseId,
    ExplorationSignature signature,
    ExplorationModel tier,
    ExplorationOutcome outcome,
    int strikeCount,
    boolean completed) {}
