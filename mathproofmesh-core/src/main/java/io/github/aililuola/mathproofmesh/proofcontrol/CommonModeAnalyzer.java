package io.github.aililuola.mathproofmesh.proofcontrol;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Detects load-bearing shared assumptions across independent live routes. */
@SuppressFBWarnings(
    value = {"IMPROPER_UNICODE", "MODIFICATION_AFTER_VALIDATION"},
    justification =
        "NFKC normalization and locale-stable case folding are the validation itself; "
            + "semantic conflicts are checked after canonicalization")
public final class CommonModeAnalyzer {
  public enum ChallengeOutcome {
    VERIFIED,
    REFUTED,
    AVOIDED,
    INCONCLUSIVE,
    BLOCKED
  }

  public record ChallengerTask(
      String id,
      String familyId,
      String targetStatement,
      List<String> routeIds,
      List<String> requiredActions,
      boolean premiseEligible,
      String status) {
    public ChallengerTask {
      routeIds = List.copyOf(routeIds);
      requiredActions = List.copyOf(requiredActions);
    }
  }

  public record ChallengeReview(
      String taskId,
      String challengerAgentId,
      String reviewerAgentId,
      ChallengeOutcome outcome,
      boolean independenceConfirmed,
      List<String> evidenceRefs,
      String detail,
      boolean mayCloseGoal) {
    public ChallengeReview {
      evidenceRefs = List.copyOf(evidenceRefs);
    }
  }

  private static final Pattern WRAPPERS =
      Pattern.compile(
          "^(?:assume|suppose that|suppose|hypothesis\\s*:|using|use|"
              + "假设|假定|设|使用|利用)\\s*",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern TRANSPORT =
      Pattern.compile(
          "^\\[[^]]+]\\[STATUS:[^]]+]\\[SOURCE:[^]]+]"
              + "\\[PREMISE_ELIGIBLE:(?:true|false)]\\s*",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern NEGATIVE =
      Pattern.compile("\\b(?:not|no|never|cannot)\\b|不成立|不存在|不能");
  private final SemanticProfileService semanticProfiles = new SemanticProfileService();
  private final Map<String, ChallengerTask> tasks = new LinkedHashMap<>();
  private final Map<String, ChallengeReview> results = new LinkedHashMap<>();

  public List<ProofControlModels.AssumptionFamily> analyze(
      List<ProofControlModels.Assumption> assumptions,
      Set<String> liveRouteIds,
      Set<String> frozenRouteIds,
      Map<String, Set<String>> dependencyGraph) {
    Set<String> live = new LinkedHashSet<>(liveRouteIds);
    live.removeAll(frozenRouteIds == null ? Set.of() : frozenRouteIds);
    List<ProofControlModels.Assumption> eligible =
        assumptions.stream()
            .filter(value -> value.domain() == ProofControlModels.AssumptionDomain.MATHEMATICAL)
            .filter(value -> value.routeIds().stream().anyMatch(live::contains))
            .toList();
    Map<String, List<ProofControlModels.Assumption>> families = new LinkedHashMap<>();
    for (ProofControlModels.Assumption assumption : eligible) {
      Set<String> assumptionClosure =
          transitiveClosure(assumption.typedDependencyIds(), dependencyGraph);
      String key =
          familyKey(assumption.statement())
              + (assumptionClosure.isEmpty()
                  ? ""
                  : "|typed:"
                      + String.join(
                          ",", assumptionClosure.stream().sorted().toList()));
      List<ProofControlModels.Assumption> match = families.get(key);
      if (match == null) {
        String crossKey =
            families.entrySet().stream()
                .filter(
                    entry ->
                        transitiveClosure(
                                entry.getValue().getFirst().typedDependencyIds(),
                                dependencyGraph)
                            .equals(assumptionClosure)
                            &&
                        semanticallyMatch(
                            entry.getValue().getFirst().statement(),
                            assumption.statement()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        key = crossKey == null ? key : crossKey;
      }
      families.computeIfAbsent(key, ignored -> new ArrayList<>()).add(assumption);
    }

    // Typed transitive dependency identity is stronger than wording, but the
    // entire closure must agree. Merely sharing a theme is insufficient.
    Map<Set<String>, List<ProofControlModels.Assumption>> closures = new LinkedHashMap<>();
    for (ProofControlModels.Assumption assumption : eligible) {
      Set<String> closure = transitiveClosure(assumption.typedDependencyIds(), dependencyGraph);
      if (!closure.isEmpty()) {
        closures.computeIfAbsent(closure, ignored -> new ArrayList<>()).add(assumption);
      }
    }
    for (Map.Entry<Set<String>, List<ProofControlModels.Assumption>> entry : closures.entrySet()) {
      Set<String> routes = routes(entry.getValue(), live);
      if (routes.size() < 2) {
        continue;
      }
      String key = "typed:" + String.join(",", entry.getKey().stream().sorted().toList());
      families.computeIfAbsent(key, ignored -> new ArrayList<>()).addAll(entry.getValue());
    }

    List<ProofControlModels.AssumptionFamily> result = new ArrayList<>();
    for (Map.Entry<String, List<ProofControlModels.Assumption>> entry : families.entrySet()) {
      List<ProofControlModels.Assumption> members =
          entry.getValue().stream().distinct().sorted(Comparator.comparing(
              ProofControlModels.Assumption::id)).toList();
      Set<String> routes = routes(members, live);
      if (routes.size() < 2) {
        continue;
      }
      boolean verified =
          members.stream().anyMatch(ProofControlModels.Assumption::independentlyVerified);
      Set<String> closure = new LinkedHashSet<>();
      for (ProofControlModels.Assumption member : members) {
        closure.addAll(transitiveClosure(member.typedDependencyIds(), dependencyGraph));
      }
      double load =
          members.stream()
              .mapToDouble(ProofControlModels.Assumption::loadBearingScore)
              .average()
              .orElse(0.0d);
      double risk = verified ? 0.0d : Math.min(1.0d, 0.45d + 0.15d * routes.size() + 0.2d * load);
      String canonical =
          entry.getKey().startsWith("typed:")
              ? members.getFirst().statement()
              : entry.getKey().replaceFirst("\\|typed:.*$", "");
      String id =
          "assumption_family_"
              + CanonicalJson.stableHash(
                      Map.of(
                          "canonical", canonical,
                          "members", members.stream().map(
                              ProofControlModels.Assumption::id).toList(),
                          "routes", routes.stream().sorted().toList(),
                          "closure", closure.stream().sorted().toList()))
                  .substring(0, 20);
      result.add(
          new ProofControlModels.AssumptionFamily(
              id,
              canonical,
              members.stream().map(ProofControlModels.Assumption::id).toList(),
              routes,
              closure,
              risk,
              routes.equals(live) && !live.isEmpty()));
    }
    return result.stream().distinct().sorted(Comparator.comparing(
        ProofControlModels.AssumptionFamily::id)).toList();
  }

  public ChallengerTask challengerForFamily(
      ProofControlModels.AssumptionFamily family) {
    return tasks.computeIfAbsent(
        family.id(),
        ignored ->
            new ChallengerTask(
                "assumption_challenger_"
                    + CanonicalJson.stableHash(
                            Map.of(
                                "family", family.id(),
                                "statement", family.canonicalStatement(),
                                "routes", family.liveRouteIds().stream().sorted().toList()))
                        .substring(0, 20),
                family.id(),
                family.canonicalStatement(),
                family.liveRouteIds().stream().sorted().toList(),
                List.of("prove", "refute", "weaken", "avoid"),
                false,
                "open"));
  }

  public ChallengeReview reviewChallenge(
      ChallengerTask task,
      String challengerAgentId,
      String reviewerAgentId,
      ChallengeOutcome outcome,
      boolean independenceConfirmed,
      List<String> evidenceRefs,
      String detail) {
    if (reviewerAgentId == null
        || reviewerAgentId.isBlank()
        || reviewerAgentId.equals(challengerAgentId)) {
      throw new IllegalArgumentException(
          "assumption challenge requires an independent reviewer");
    }
    if (!independenceConfirmed || evidenceRefs == null || evidenceRefs.isEmpty()) {
      throw new IllegalArgumentException(
          "unreviewed challenge resolution cannot alter common-mode state");
    }
    ChallengeReview review =
        new ChallengeReview(
            task.id(),
            challengerAgentId,
            reviewerAgentId,
            outcome,
            true,
            evidenceRefs.stream().distinct().sorted().toList(),
            ProofControlModels.required(detail, "detail"),
            false);
    results.putIfAbsent(task.id(), review);
    return results.get(task.id());
  }

  public boolean strategyIndependent(
      ProofControlModels.AssumptionFamily family,
      Set<String> strategyDependencyClosure,
      String strategyStatement) {
    if (!java.util.Collections.disjoint(
        family.typedDependencyClosure(), strategyDependencyClosure)) {
      return false;
    }
    return !semanticallyMatch(family.canonicalStatement(), strategyStatement);
  }

  public boolean blocksHardStop(ProofControlModels.AssumptionFamily family) {
    if (family.commonModeRisk() <= 0.0d) {
      return false;
    }
    ChallengerTask task = tasks.get(family.id());
    return task == null || !results.containsKey(task.id());
  }

  public String familyKey(String statement) {
    String text =
        Normalizer.normalize(statement, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT)
            .strip();
    while (true) {
      String before = text;
      text = TRANSPORT.matcher(text).replaceFirst("").strip();
      text = WRAPPERS.matcher(text).replaceFirst("").strip();
      if (before.equals(text)) {
        break;
      }
    }
    text = text.replaceAll("[\\p{Punct}，。；：！？、]", " ");
    text = text.replaceAll("\\b(?:that|the|a|an)\\b", " ");
    text = text.replaceAll("[的地得]", "");
    return text.replaceAll("\\s+", " ").strip();
  }

  public boolean semanticallyMatch(String left, String right) {
    String first = familyKey(left);
    String second = familyKey(right);
    if (first.equals(second)) {
      return true;
    }
    if (NEGATIVE.matcher(first).find() != NEGATIVE.matcher(second).find()) {
      return false;
    }
    return semanticProfiles.conservativelyMatchesAcrossLanguages(left, right);
  }

  public static Set<String> transitiveClosure(
      Set<String> seeds, Map<String, Set<String>> graph) {
    ArrayDeque<String> queue = new ArrayDeque<>(seeds == null ? Set.of() : seeds);
    Set<String> closure = new LinkedHashSet<>();
    while (!queue.isEmpty()) {
      String current = queue.removeFirst();
      if (!closure.add(current)) {
        continue;
      }
      queue.addAll(graph == null ? Set.of() : graph.getOrDefault(current, Set.of()));
    }
    return Set.copyOf(closure);
  }

  private static Set<String> routes(
      List<ProofControlModels.Assumption> assumptions, Set<String> live) {
    Set<String> routes = new LinkedHashSet<>();
    for (ProofControlModels.Assumption assumption : assumptions) {
      for (String route : assumption.routeIds()) {
        if (live.contains(route)) {
          routes.add(route);
        }
      }
    }
    return Set.copyOf(routes);
  }
}
