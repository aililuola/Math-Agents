package io.github.aililuola.mathproofmesh.agent;

import io.github.aililuola.mathproofmesh.contract.ActionKind;
import io.github.aililuola.mathproofmesh.contract.FailureLevel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic guards that are authoritative over model recommendations. */
public final class AgentGuardPolicies {
  private AgentGuardPolicies() {}

  public static GuardResult applyProblemIntegrity(
      String expectedProblemHash,
      String attemptProblemHash,
      boolean modelPassed) {
    boolean matches =
        requireText(expectedProblemHash, "expectedProblemHash")
            .equals(requireText(attemptProblemHash, "attemptProblemHash"));
    if (matches) {
      return new GuardResult(modelPassed, true, null, List.of());
    }
    return new GuardResult(
        false,
        false,
        "problem_integrity",
        List.of("attempt problem hash does not match the immutable problem"));
  }

  public static GuardResult applyDeterministicCounterexamples(
      boolean modelPassed, List<DeterministicToolEvidence> evidence) {
    boolean counterexample =
        Objects.requireNonNull(evidence, "evidence").stream()
            .anyMatch(
                item -> item.ok() && item.counterexampleFound());
    if (!counterexample) {
      return new GuardResult(modelPassed, true, null, List.of());
    }
    return new GuardResult(
        false,
        true,
        "deterministic_tool_check",
        List.of("deterministic counterexample overrides model verdict"));
  }

  public static List<ClaimContextItem> selectClaimContext(
      List<ClaimContextItem> claims, String query, int maximumCharacters) {
    if (maximumCharacters < 1) {
      throw new IllegalArgumentException(
          "maximumCharacters must be positive");
    }
    List<ClaimContextItem> ordered =
        List.copyOf(Objects.requireNonNull(claims, "claims"));
    Map<String, ClaimContextItem> byId = new LinkedHashMap<>();
    for (ClaimContextItem claim : ordered) {
      if (byId.putIfAbsent(claim.claimId(), claim) != null) {
        throw new IllegalArgumentException(
            "duplicate claim id: " + claim.claimId());
      }
    }
    Set<String> selected = new LinkedHashSet<>();
    List<String> terms =
        java.util.Arrays.stream(
                requireText(query, "query")
                    .toLowerCase(Locale.ROOT)
                    .split("[^a-z0-9]+"))
            .filter(term -> term.length() > 2)
            .toList();
    for (ClaimContextItem claim : ordered) {
      String text = claim.statement().toLowerCase(Locale.ROOT);
      if (claim.verified()
          && terms.stream().anyMatch(text::contains)) {
        addDependencies(claim.claimId(), byId, selected, new LinkedHashSet<>());
      }
    }
    List<ClaimContextItem> result = new ArrayList<>();
    int characters = 0;
    for (String id : selected) {
      ClaimContextItem item = byId.get(id);
      int next = item.statement().length();
      if (!result.isEmpty() && characters + next > maximumCharacters) {
        break;
      }
      result.add(item);
      characters += next;
    }
    return List.copyOf(result);
  }

  public static boolean mayEnterFinalAuditedRepair(
      FailureLevel failureLevel,
      boolean metaCanSynthesize,
      ActionKind recommendedAction,
      String firstErrorStep,
      List<String> issuePhases) {
    Objects.requireNonNull(failureLevel, "failureLevel");
    Objects.requireNonNull(recommendedAction, "recommendedAction");
    Objects.requireNonNull(issuePhases, "issuePhases");
    if (failureLevel != FailureLevel.EXECUTION
        || !metaCanSynthesize
        || recommendedAction != ActionKind.SYNTHESIZE) {
      return false;
    }
    if (firstErrorStep != null && !firstErrorStep.isBlank()) {
      return false;
    }
    return issuePhases.stream()
        .map(value -> value.toLowerCase(Locale.ROOT))
        .allMatch(
            phase ->
                phase.equals("completeness")
                    || phase.equals("presentation")
                    || phase.equals("finalization"));
  }

  private static void addDependencies(
      String id,
      Map<String, ClaimContextItem> byId,
      Set<String> selected,
      Set<String> visiting) {
    ClaimContextItem item = byId.get(id);
    if (item == null || selected.contains(id)) {
      return;
    }
    if (!visiting.add(id)) {
      throw new IllegalArgumentException("claim dependency cycle at " + id);
    }
    for (String dependency : item.dependencies()) {
      ClaimContextItem dependencyItem = byId.get(dependency);
      if (dependencyItem != null && dependencyItem.verified()) {
        addDependencies(
            dependency, byId, selected, visiting);
      }
    }
    visiting.remove(id);
    selected.add(id);
  }

  private static String requireText(String value, String label) {
    Objects.requireNonNull(value, label);
    String normalized = value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return normalized;
  }

  public record GuardResult(
      boolean passed,
      boolean problemIntegrityOk,
      String firstErrorStep,
      List<String> issues) {
    public GuardResult {
      issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
    }
  }

  public record DeterministicToolEvidence(
      boolean ok, boolean counterexampleFound) {}

  public record ClaimContextItem(
      String claimId,
      String statement,
      List<String> dependencies,
      boolean verified) {
    public ClaimContextItem {
      claimId = requireText(claimId, "claimId");
      statement = requireText(statement, "statement");
      dependencies =
          List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
    }
  }
}
