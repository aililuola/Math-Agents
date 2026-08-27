package io.github.aililuola.mathproofmesh.agent;

import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.SemanticPivotProposal;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotEvidenceAuthority;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotObstructionRef;
import java.util.List;
import java.util.Map;

final class SemanticPivotServerTestFixtures {
  static final String PROBLEM = "problem-hash";
  static final String ROOT = "root-hash";
  static final String ROUTE = "route-1";
  static final String SOURCE = "strategy-source";
  static final String OBSTRUCTION = "counterexample-1";

  private SemanticPivotServerTestFixtures() {}

  static SemanticPivotProposal proposal() {
    return new SemanticPivotProposal(
        "proposal-1",
        "proposer",
        PROBLEM,
        ROOT,
        ROUTE,
        SOURCE,
        List.of("OBJECT_REPLACEMENT", "TARGET_REFORMULATION"),
        List.of(OBSTRUCTION),
        List.of(
            new SemanticPivotProposal.ObjectChangeDraft(
                "prefix-family",
                "prefix minimal hitting sets",
                "REPLACE",
                "global-family",
                "global inclusion-minimal hitting sets",
                "Retain the hitting-set formulation and drop prefix monotonicity.",
                List.of(OBSTRUCTION))),
        List.of(),
        List.of(),
        List.of(),
        List.of(
            new SemanticPivotProposal.ObligationChangeDraft(
                "obligation-large-prime",
                null,
                "ADD_NEW_OBLIGATION",
                "Reduce each prime p>a1 from a global minimal hitting set.",
                ObligationKind.SUBGOAL.value(),
                List.of("p>a1"),
                List.of(),
                "Load-bearing global reduction.")),
        strategy(),
        "The exact counterexample requires a new global object and target.",
        null,
        null);
  }

  static Map<String, PivotObstructionRef> obstructions() {
    return Map.of(
        OBSTRUCTION,
        new PivotObstructionRef(
            OBSTRUCTION,
            PivotEvidenceAuthority.VERIFIED_COUNTEREXAMPLE,
            "attempt-artifact://counterexample-1",
            ROUTE,
            SOURCE,
            "canonical-old",
            "statement-hash"));
  }

  private static StrategyCard strategy() {
    return new StrategyCard(
        null,
        "Prove the global large-prime reduction",
        List.of(),
        List.of(),
        List.of(),
        "Study global inclusion-minimal hitting sets",
        List.of(),
        0.4d,
        0.7d,
        List.of("Large-prime support reduction"),
        "Search for a global minimal hitting set containing a forbidden prime.",
        "Global family replaces refuted prefix monotonicity",
        null,
        null,
        List.of(SOURCE),
        List.of("positive integer sequence"),
        "strategy-pivot",
        List.of("hitting-set"),
        "Global hitting-set pivot");
  }
}
