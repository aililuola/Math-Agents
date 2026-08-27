package io.github.aililuola.mathproofmesh.proofgraph;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification =
        "The facade deliberately shares one graph authority with its cooperating services.")
public record ProofGraphServices(
    ProofGraphStore store,
    BridgeBroker bridges,
    ContradictionBroker contradictions,
    DuplicateRouteDetector duplicateRoutes) {

  public ProofGraphServices {
    java.util.Objects.requireNonNull(store, "store");
    java.util.Objects.requireNonNull(bridges, "bridges");
    java.util.Objects.requireNonNull(contradictions, "contradictions");
    java.util.Objects.requireNonNull(duplicateRoutes, "duplicateRoutes");
  }

  public static ProofGraphServices defaults(String problemHash) {
    ProofGraphStore store = new ProofGraphStore(problemHash);
    return new ProofGraphServices(
        store,
        new BridgeBroker(BridgePolicy.defaults(), store),
        new ContradictionBroker(ContradictionPolicy.defaults(), store),
        new DuplicateRouteDetector(0.8));
  }
}
