package io.github.aililuola.mathproofmesh.verification;

import io.github.aililuola.mathproofmesh.contract.FormalStatementPacket;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import java.util.Comparator;
import java.util.List;

/** Allocates formal budget to shared, central, quantified, or main obligations. */
public final class FormalizationCandidateSelector {

  public List<ProofObligation> select(
      List<ProofObligation> obligations, int maxCandidates) {
    if (maxCandidates < 0) {
      throw new IllegalArgumentException("maxCandidates cannot be negative");
    }
    return obligations.stream()
        .filter(item -> !"closed".equals(item.status()))
        .map(item -> new Scored(item, score(item)))
        .filter(item -> item.score() >= 0.45)
        .sorted(
            Comparator.comparingDouble(Scored::score)
                .reversed()
                .thenComparing(item -> item.obligation().obligationId()))
        .limit(maxCandidates)
        .map(Scored::obligation)
        .toList();
  }

  public FormalStatementPacket packet(ProofObligation obligation) {
    return new FormalStatementPacket(
        obligation.assumptions(),
        obligation.obligationId(),
        null,
        obligation.problemHash(),
        obligation.quantifiers(),
        obligation.statement(),
        null);
  }

  private static double score(ProofObligation item) {
    double quantifierRisk = Math.min(1.0, item.quantifiers().size() / 3.0);
    double shared =
        Math.min(1.0, Math.max(0, SetSize.distinct(item.routeIds()) - 1) / 2.0);
    double main = item.kind() == ObligationKind.MAIN_GOAL ? 1.0 : 0.0;
    return 0.4 * item.centrality()
        + 0.25 * shared
        + 0.2 * quantifierRisk
        + 0.15 * main;
  }

  private record Scored(ProofObligation obligation, double score) {}

  private static final class SetSize {
    private SetSize() {}

    static int distinct(List<String> values) {
      return new java.util.LinkedHashSet<>(values).size();
    }
  }
}
