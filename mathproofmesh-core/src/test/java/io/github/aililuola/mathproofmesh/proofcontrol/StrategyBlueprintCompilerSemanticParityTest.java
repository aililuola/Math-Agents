package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

final class StrategyBlueprintCompilerSemanticParityTest {
  @Test
  void archiveDomainMetadataDoesNotCauseFalseBlueprintRejection() {
    ProofControlModels.Strategy strategy =
        new ProofControlModels.Strategy(
            "strategy-domain-metadata",
            "Finite prime support",
            "Use finite prime support and residue classes.",
            List.of("elementary number theory"),
            List.of("Only finitely many prime factors occur."),
            List.of("Eventually every term uses a prime from a fixed finite set."),
            List.of("search for a new prime factor"),
            List.of("proving finiteness requires a delicate minimal-choice estimate"),
            "route-domain-metadata");
    ProofControlModels.Obligation goal =
        new ProofControlModels.Obligation(
            "main-goal",
            "There exist positive integers T and L such that a_(n+T) = a_n + L.",
            ProofControlModels.ObligationKind.MAIN_GOAL,
            ProofControlModels.ObligationStatus.OPEN,
            List.of(),
            List.of("run"),
            1.0d,
            1.0d);

    StrategyBlueprintCompiler.Compilation compilation =
        new StrategyBlueprintCompiler().compile("problem-hash", strategy, goal);

    assertEquals("accepted", compilation.blueprint().status());
    assertFalse(compilation.reviewReasons().contains("domain objects are not preserved"));
  }
}
