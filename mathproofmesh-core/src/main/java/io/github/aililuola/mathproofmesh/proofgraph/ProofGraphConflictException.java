package io.github.aililuola.mathproofmesh.proofgraph;

public final class ProofGraphConflictException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public ProofGraphConflictException(String obligationId, long expectedVersion) {
    super(
        "proof obligation '"
            + obligationId
            + "' was not at expected version "
            + expectedVersion);
  }
}
