package io.github.aililuola.mathproofmesh.communication;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.RouteDescriptor;
import io.github.aililuola.mathproofmesh.contract.RouteMember;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import io.github.aililuola.mathproofmesh.contract.RouteStatus;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RouteRegistry {
  private static final Pattern TOKEN_PATTERN =
      Pattern.compile("[a-z0-9_]+|[\\u4e00-\\u9fff]");
  private static final Set<RouteStatus> SCHEDULABLE =
      Set.of(RouteStatus.ACTIVE, RouteStatus.REPAIR_ONCE);

  private final String problemHash;
  private final int maxNeighbors;
  private final int maxMembers;
  private final double duplicateThreshold;
  private final Map<String, RouteDescriptor> routes = new LinkedHashMap<>();
  private final Map<String, String> strategyIndex = new LinkedHashMap<>();
  private final Map<String, String> strategyAliases = new LinkedHashMap<>();

  public RouteRegistry(
      String problemHash, int maxNeighbors, int maxMembers, double duplicateThreshold) {
    if (problemHash == null || problemHash.isBlank()) {
      throw new IllegalArgumentException("problemHash is required");
    }
    if (maxNeighbors < 0 || maxMembers < 1) {
      throw new IllegalArgumentException("route limits are invalid");
    }
    if (duplicateThreshold < 0.0 || duplicateThreshold > 1.0) {
      throw new IllegalArgumentException("duplicateThreshold must be between zero and one");
    }
    this.problemHash = problemHash.strip();
    this.maxNeighbors = maxNeighbors;
    this.maxMembers = maxMembers;
    this.duplicateThreshold = duplicateThreshold;
  }

  public String problemHash() {
    return problemHash;
  }

  public synchronized List<RouteDescriptor> routes() {
    return List.copyOf(routes.values());
  }

  public synchronized RouteDescriptor get(String routeId) {
    RouteDescriptor route = routes.get(routeId);
    if (route == null) {
      throw new IllegalArgumentException("unknown route: " + routeId);
    }
    return route;
  }

  public synchronized boolean exists(String routeId) {
    return routes.containsKey(routeId);
  }

  public synchronized RouteDescriptor register(RouteDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    RouteDescriptor existing = routes.get(descriptor.routeId());
    if (existing != null) {
      if (!existing.strategyId().equals(descriptor.strategyId())) {
        throw new IllegalArgumentException("route ID already belongs to another strategy");
      }
      return existing;
    }
    String indexed = strategyIndex.get(descriptor.strategyId());
    if (indexed != null) {
      return routes.get(indexed);
    }
    routes.put(descriptor.routeId(), descriptor);
    strategyIndex.put(descriptor.strategyId(), descriptor.routeId());
    recomputeNeighbors();
    return routes.get(descriptor.routeId());
  }

  public synchronized RouteDescriptor registerStrategy(StrategyCard strategy) {
    Objects.requireNonNull(strategy, "strategy");
    String indexed = strategyIndex.get(strategy.strategyId());
    if (indexed != null) {
      return routes.get(indexed);
    }
    Optional<RouteDescriptor> duplicate = findSemanticDuplicate(strategy);
    if (duplicate.isPresent()) {
      strategyAliases.put(strategy.strategyId(), duplicate.orElseThrow().routeId());
      return duplicate.orElseThrow();
    }
    String routeId =
        "route_"
            + CanonicalJson.stableHash(List.of(problemHash, strategy.strategyId()))
                .substring(0, 20);
    List<String> mechanism = new ArrayList<>();
    mechanism.addAll(strategy.tags());
    mechanism.add(strategy.title());
    mechanism.addAll(strategy.expectedLemmas());
    RouteDescriptor descriptor =
        new RouteDescriptor(
            null,
            0,
            0,
            null,
            null,
            strategy.inspirationProposalId(),
            null,
            null,
            null,
            distinct(mechanism),
            List.of(),
            null,
            List.of(),
            0,
            false,
            null,
            routeId,
            distinct(strategy.prerequisites()),
            0,
            RouteStatus.ACTIVE,
            strategy.strategyId(),
            strategySignature(strategy));
    return register(descriptor);
  }

  public synchronized Optional<RouteDescriptor> routeForStrategy(String strategyId) {
    String routeId = strategyIndex.get(strategyId);
    if (routeId == null) {
      routeId = strategyAliases.get(strategyId);
    }
    return Optional.ofNullable(routes.get(routeId));
  }

  public synchronized void assignMember(
      String routeId, String agentId, RouteRole role, int roundIndex) {
    RouteDescriptor route = get(routeId);
    if (route.members().stream()
        .anyMatch(member -> member.agentId().equals(agentId) && member.role() == role)) {
      return;
    }
    long uniqueMembers =
        route.members().stream().map(RouteMember::agentId).distinct().count();
    boolean alreadyAssigned =
        route.members().stream().anyMatch(member -> member.agentId().equals(agentId));
    if (!alreadyAssigned && uniqueMembers >= maxMembers) {
      throw new IllegalStateException("route " + routeId + " has reached its member limit");
    }
    List<RouteMember> members = new ArrayList<>(route.members());
    members.add(new RouteMember(agentId, roundIndex, role));
    routes.put(routeId, copy(route, List.copyOf(members), route.neighborRouteIds(), route.status()));
  }

  public synchronized boolean ownsAgent(String routeId, String agentId, RouteRole role) {
    RouteDescriptor route = routes.get(routeId);
    return route != null
        && route.members().stream()
            .anyMatch(
                member ->
                    member.agentId().equals(agentId)
                        && (role == null || member.role() == role));
  }

  public synchronized void setNeighbors(String routeId, Collection<String> candidates) {
    RouteDescriptor route = get(routeId);
    List<String> selected =
        candidates.stream()
            .filter(candidate -> !candidate.equals(routeId))
            .filter(routes::containsKey)
            .distinct()
            .limit(maxNeighbors)
            .toList();
    routes.put(routeId, copy(route, route.members(), selected, route.status()));
  }

  public synchronized List<String> neighbors(String routeId) {
    RouteDescriptor route = get(routeId);
    return route.neighborRouteIds().stream()
        .filter(routes::containsKey)
        .filter(candidate -> SCHEDULABLE.contains(routes.get(candidate).status()))
        .toList();
  }

  public synchronized void markCooling(String routeId, int untilRound, boolean requiresRevision) {
    RouteDescriptor route = get(routeId);
    routes.put(
        routeId,
        new RouteDescriptor(
            untilRound,
            route.duplicateAttemptCount(),
            route.failureCount(),
            route.frozenReason(),
            route.frozenSignature(),
            route.inspirationProposalId(),
            route.lastProgressSignature(),
            route.latestAttemptId(),
            route.latestCheckpointId(),
            route.mechanismSignature(),
            route.members(),
            route.mergedIntoRouteId(),
            route.neighborRouteIds(),
            route.noProgressStrikes(),
            requiresRevision,
            null,
            route.routeId(),
            route.sharedAssumptions(),
            route.stagnationRounds(),
            RouteStatus.COOLING,
            route.strategyId(),
            route.strategySignature()));
    recomputeNeighbors();
  }

  public synchronized void reactivate(String routeId, String revisionSummary) {
    RouteDescriptor route = get(routeId);
    if (route.requiresRevision() && (revisionSummary == null || revisionSummary.isBlank())) {
      throw new IllegalArgumentException(
          "a counterexample-dependent route requires an explicit revision");
    }
    routes.put(
        routeId,
        new RouteDescriptor(
            null,
            route.duplicateAttemptCount(),
            route.failureCount(),
            null,
            null,
            route.inspirationProposalId(),
            route.lastProgressSignature(),
            route.latestAttemptId(),
            route.latestCheckpointId(),
            route.mechanismSignature(),
            route.members(),
            route.mergedIntoRouteId(),
            route.neighborRouteIds(),
            0,
            false,
            revisionSummary == null ? "" : revisionSummary.strip(),
            route.routeId(),
            route.sharedAssumptions(),
            route.stagnationRounds(),
            RouteStatus.ACTIVE,
            route.strategyId(),
            route.strategySignature()));
    recomputeNeighbors();
  }

  public synchronized void merge(String sourceRouteId, String targetRouteId) {
    if (sourceRouteId.equals(targetRouteId)) {
      throw new IllegalArgumentException("a route cannot be merged into itself");
    }
    RouteDescriptor source = get(sourceRouteId);
    RouteDescriptor target = get(targetRouteId);
    routes.put(
        sourceRouteId,
        new RouteDescriptor(
            source.cooldownUntilRound(),
            source.duplicateAttemptCount(),
            source.failureCount(),
            source.frozenReason(),
            source.frozenSignature(),
            source.inspirationProposalId(),
            source.lastProgressSignature(),
            source.latestAttemptId(),
            source.latestCheckpointId(),
            source.mechanismSignature(),
            source.members(),
            targetRouteId,
            List.of(),
            source.noProgressStrikes(),
            source.requiresRevision(),
            source.revisionSummary(),
            source.routeId(),
            source.sharedAssumptions(),
            source.stagnationRounds(),
            RouteStatus.MERGED,
            source.strategyId(),
            source.strategySignature()));
    List<String> combined = new ArrayList<>(target.mechanismSignature());
    combined.addAll(source.mechanismSignature());
    routes.put(
        targetRouteId,
        new RouteDescriptor(
            target.cooldownUntilRound(),
            target.duplicateAttemptCount(),
            target.failureCount(),
            target.frozenReason(),
            target.frozenSignature(),
            target.inspirationProposalId(),
            target.lastProgressSignature(),
            target.latestAttemptId(),
            target.latestCheckpointId(),
            distinct(combined),
            target.members(),
            target.mergedIntoRouteId(),
            target.neighborRouteIds(),
            target.noProgressStrikes(),
            target.requiresRevision(),
            target.revisionSummary(),
            target.routeId(),
            target.sharedAssumptions(),
            target.stagnationRounds(),
            target.status(),
            target.strategyId(),
            target.strategySignature()));
    recomputeNeighbors();
  }

  public synchronized void activateCooledRoutes(int currentRound) {
    for (RouteDescriptor route : List.copyOf(routes.values())) {
      if (route.status() == RouteStatus.COOLING
          && route.cooldownUntilRound() != null
          && route.cooldownUntilRound() <= currentRound
          && !route.requiresRevision()) {
        reactivate(route.routeId(), "");
      }
    }
  }

  public synchronized void recomputeNeighbors() {
    List<RouteDescriptor> active =
        routes.values().stream().filter(route -> SCHEDULABLE.contains(route.status())).toList();
    for (RouteDescriptor route : List.copyOf(routes.values())) {
      if (!SCHEDULABLE.contains(route.status())) {
        routes.put(route.routeId(), copy(route, route.members(), List.of(), route.status()));
        continue;
      }
      Set<String> sourceTokens = tokens(String.join(" ", route.mechanismSignature()));
      List<String> selected =
          active.stream()
              .filter(candidate -> !candidate.routeId().equals(route.routeId()))
              .sorted(
                  Comparator.<RouteDescriptor>comparingDouble(
                          candidate ->
                              -similarity(
                                  sourceTokens,
                                  tokens(String.join(" ", candidate.mechanismSignature()))))
                      .thenComparing(RouteDescriptor::routeId))
              .limit(maxNeighbors)
              .map(RouteDescriptor::routeId)
              .toList();
      routes.put(route.routeId(), copy(route, route.members(), selected, route.status()));
    }
  }

  private Optional<RouteDescriptor> findSemanticDuplicate(StrategyCard strategy) {
    String signature = strategySignature(strategy);
    Optional<RouteDescriptor> exact =
        routes.values().stream()
            .filter(route -> route.strategySignature().equals(signature))
            .findFirst();
    if (exact.isPresent()) {
      return exact;
    }
    Set<String> candidateTokens =
        tokens(
            String.join(
                " ",
                strategy.title(),
                strategy.coreIdea(),
                strategy.falsificationTest(),
                String.join(" ", strategy.tags()),
                String.join(" ", strategy.prerequisites())));
    return routes.values().stream()
        .filter(route -> route.status() != RouteStatus.MERGED)
        .map(
            route ->
                Map.entry(
                    route,
                    similarity(
                        candidateTokens, tokens(String.join(" ", route.mechanismSignature())))))
        .filter(entry -> entry.getValue() >= duplicateThreshold)
        .max(
            Comparator.<Map.Entry<RouteDescriptor, Double>>comparingDouble(Map.Entry::getValue)
                .thenComparing(entry -> entry.getKey().routeId()))
        .map(Map.Entry::getKey);
  }

  private static String strategySignature(StrategyCard strategy) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("title", sortedTokenText(strategy.title()));
    payload.put("core", sortedTokenText(strategy.coreIdea()));
    payload.put(
        "tags",
        strategy.tags().stream().map(value -> value.toLowerCase(Locale.ROOT)).distinct().sorted().toList());
    payload.put(
        "prerequisites",
        strategy.prerequisites().stream()
            .map(value -> value.toLowerCase(Locale.ROOT))
            .distinct()
            .sorted()
            .toList());
    return CanonicalJson.stableHash(payload);
  }

  private static String sortedTokenText(String value) {
    return String.join(" ", tokens(value).stream().sorted().toList());
  }

  private static Set<String> tokens(String value) {
    Matcher matcher = TOKEN_PATTERN.matcher(value.toLowerCase(Locale.ROOT));
    Set<String> result = new LinkedHashSet<>();
    while (matcher.find()) {
      result.add(matcher.group());
    }
    return result;
  }

  private static double similarity(Set<String> left, Set<String> right) {
    if (left.isEmpty() && right.isEmpty()) {
      return 1.0;
    }
    Set<String> union = new LinkedHashSet<>(left);
    union.addAll(right);
    Set<String> intersection = new LinkedHashSet<>(left);
    intersection.retainAll(right);
    return (double) intersection.size() / Math.max(1, union.size());
  }

  private static List<String> distinct(Collection<String> values) {
    return List.copyOf(new LinkedHashSet<>(values));
  }

  private static RouteDescriptor copy(
      RouteDescriptor route,
      List<RouteMember> members,
      List<String> neighbors,
      RouteStatus status) {
    return new RouteDescriptor(
        route.cooldownUntilRound(),
        route.duplicateAttemptCount(),
        route.failureCount(),
        route.frozenReason(),
        route.frozenSignature(),
        route.inspirationProposalId(),
        route.lastProgressSignature(),
        route.latestAttemptId(),
        route.latestCheckpointId(),
        route.mechanismSignature(),
        members,
        route.mergedIntoRouteId(),
        neighbors,
        route.noProgressStrikes(),
        route.requiresRevision(),
        route.revisionSummary(),
        route.routeId(),
        route.sharedAssumptions(),
        route.stagnationRounds(),
        status,
        route.strategyId(),
        route.strategySignature());
  }
}
