package io.github.aililuola.mathproofmesh.persistence;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphStore;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification =
        "This result deliberately exposes the mutable, request-scoped JGraphT projection.")
public record LoadedProofGraph(ProofGraphStore graph, int queryCount) {
  public LoadedProofGraph {
    java.util.Objects.requireNonNull(graph, "graph");
    if (queryCount < 0) {
      throw new IllegalArgumentException("queryCount must be non-negative");
    }
  }
}
