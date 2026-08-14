package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.contract.ProblemContract;
import io.github.aililuola.mathproofmesh.strategydiversity.DomainStrategySeedProvider;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategySeed;
import java.util.List;
import java.util.Locale;

/** Optional legacy domain guidance, isolated from the issue-007 production core. */
final class GreedyGcdDomainStrategySeedProvider implements DomainStrategySeedProvider {
  @Override
  public boolean supports(ProblemContract problem) {
    if (problem == null) {
      return false;
    }
    String statement = problem.exactStatement().toLowerCase(Locale.ROOT);
    return statement.contains("gcd")
        && statement.contains("smallest integer greater")
        && (statement.contains("a_{n+1}") || statement.contains("a_n"));
  }

  @Override
  public List<StrategySeed> seeds(ProblemContract problem) {
    if (!supports(problem)) {
      return List.of();
    }
    return List.of(
        new StrategySeed(
            "domain-bounded-gap",
            "Establish a bounded-gap lemma while keeping its stabilization bridge explicit.",
            List.of("Every successor remains within a fixed admissible bound."),
            "Search finite prefixes for a violation of the bound."),
        new StrategySeed(
            "domain-finite-state-bridge",
            "Prove an exact finite-state successor invariant before deriving any translation law.",
            List.of("The chosen state determines the next admissible value."),
            "Find equal proposed states with different successors."));
  }
}
