package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/** Rolls back all Claim Court-owned ledgers when a projection fails before commit. */
public final class ClaimCourtAtomicProjection {
  private final ClaimCourtLedger court;
  private final ClaimProofRevisionLedger revisions;
  private final ClaimCourtStageExecutionLedger executions;

  public ClaimCourtAtomicProjection(
      ClaimCourtLedger court,
      ClaimProofRevisionLedger revisions,
      ClaimCourtStageExecutionLedger executions) {
    this.court = java.util.Objects.requireNonNull(court, "court");
    this.revisions = java.util.Objects.requireNonNull(revisions, "revisions");
    this.executions = java.util.Objects.requireNonNull(executions, "executions");
  }

  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification = "The original projection failure must be rethrown after all ledgers roll back.")
  public void run(Runnable projection) {
    ClaimCourtSnapshot courtBefore = court.snapshot();
    ClaimProofRevisionSnapshot revisionsBefore = revisions.snapshot();
    ClaimCourtStageExecutionSnapshot executionsBefore = executions.snapshot();
    try {
      projection.run();
    } catch (RuntimeException exception) {
      court.restore(courtBefore);
      revisions.restore(revisionsBefore);
      executions.restore(executionsBefore);
      throw exception;
    }
  }
}
