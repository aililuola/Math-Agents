package io.github.aililuola.mathproofmesh.inspiration;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.NoveltySignature;
import io.github.aililuola.mathproofmesh.contract.ReverseGoalPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Meets backward requests with forward Facts only through explicit compatible bridges. */
public final class ReverseGoalAnalyzer {
  public ReverseGoalPlan analyze(
      String goal,
      String targetObligationId,
      List<ForwardFact> facts,
      List<BackwardRequest> requests) {
    List<String> supported = new ArrayList<>();
    List<String> gaps = new ArrayList<>();
    List<String> bridges = new ArrayList<>();
    List<String> sufficient = new ArrayList<>();
    for (BackwardRequest request : requests == null ? List.<BackwardRequest>of() : requests) {
      ForwardFact match =
          (facts == null ? List.<ForwardFact>of() : facts).stream()
              .filter(ForwardFact::factGated)
              .filter(item -> item.scope().equals(request.scope()))
              .filter(item -> explicitBridge(item.statement(), request.statement()))
              .findFirst()
              .orElse(null);
      if (match == null) {
        gaps.add(request.requestId());
        bridges.add(
            "prove explicitly that a forward Fact entails backward request "
                + request.requestId());
      } else {
        supported.add(match.factId());
        sufficient.add(request.statement());
      }
    }
    NoveltySignature signature =
        new NoveltySignature(
            List.of("goal"),
            List.of(),
            List.of("goal_to_sufficient_condition"),
            List.of("reverse_goal_analysis"),
            null,
            null,
            null,
            List.of("backward_chaining"),
            Map.of(),
            List.of(),
            List.of(targetObligationId));
    String planId =
        "reverse_goal_"
            + CanonicalJson.stableHash(
                    List.of(goal, targetObligationId, supported, gaps, bridges))
                .substring(0, 16);
    return new ReverseGoalPlan(
        List.of(),
        bridges,
        supported,
        List.of(),
        List.of(),
        goal,
        gaps,
        signature,
        planId,
        sufficient,
        targetObligationId);
  }

  private static boolean explicitBridge(String fact, String request) {
    String left = normalize(fact);
    String right = normalize(request);
    return left.equals(right)
        || left.equals("fact:" + right)
        || left.equals("verified:" + right);
  }

  private static String normalize(String value) {
    return value == null
        ? ""
        : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
  }

  public record ForwardFact(String factId, String statement, String scope, boolean factGated) {
    public ForwardFact {
      factId = required(factId, "factId");
      statement = required(statement, "statement");
      scope = required(scope, "scope");
    }
  }

  public record BackwardRequest(String requestId, String statement, String scope) {
    public BackwardRequest {
      requestId = required(requestId, "requestId");
      statement = required(statement, "statement");
      scope = required(scope, "scope");
    }
  }

  private static String required(String value, String field) {
    String result = value == null ? "" : value.strip();
    if (result.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return result;
  }
}
