package io.github.aililuola.mathproofmesh.inspiration;

import io.github.aililuola.mathproofmesh.contract.InspirationMechanism;
import io.github.aililuola.mathproofmesh.contract.InspirationOutcome;
import io.github.aililuola.mathproofmesh.contract.InspirationProposal;
import io.github.aililuola.mathproofmesh.contract.InspirationTriggerType;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Causal outcome bookkeeping used for scheduling, never as proof evidence. */
public final class InspirationOutcomeLedger {
  private final InspirationPolicy.AdaptiveRules rules;
  private final Map<String, InspirationOutcome> outcomes = new LinkedHashMap<>();
  private final Map<String, InspirationOutcome> historical = new LinkedHashMap<>();

  public InspirationOutcomeLedger(InspirationPolicy.AdaptiveRules rules) {
    this.rules = java.util.Objects.requireNonNull(rules, "rules");
  }

  public synchronized InspirationOutcome register(
      InspirationProposal proposal,
      InspirationSnapshot snapshot,
      InspirationTriggerType triggerType,
      List<ObligationKind> obligationKinds,
      double proofDebtBefore,
      List<String> creditRouteIds,
      List<String> creditObligationIds) {
    InspirationOutcome existing = outcomes.get(proposal.proposalId());
    if (existing != null) {
      return existing;
    }
    InspirationOutcome created =
        new InspirationOutcome(
            false,
            distinct(creditObligationIds),
            distinct(creditRouteIds),
            snapshot.domain(),
            null,
            false,
            proposal.mechanism(),
            obligationKinds,
            List.of(),
            snapshot.problemHash(),
            null,
            Math.max(0.0d, proofDebtBefore),
            0.0d,
            proposal.proposalId(),
            0,
            false,
            0,
            0.0d,
            snapshot.roundIndex(),
            null,
            0,
            proposal.taskId(),
            0,
            triggerType,
            0);
    outcomes.put(proposal.proposalId(), created);
    return created;
  }

  public synchronized void recordUsage(
      String proposalId, String phase, int calls, int tokens) {
    InspirationOutcome value = outcomes.get(proposalId);
    if (value == null) {
      return;
    }
    int proposer = value.proposerCalls();
    int review = value.reviewCalls();
    int route = value.routeCalls();
    if ("proposer".equals(phase)) {
      proposer += Math.max(0, calls);
    } else if ("referee".equals(phase) || "skeptic".equals(phase)) {
      review += Math.max(0, calls);
    } else if ("route".equals(phase)) {
      route += Math.max(0, calls);
    }
    outcomes.put(
        proposalId,
        with(
            value,
            value.citedByFinalProof(),
            value.materializationAction(),
            value.materialized(),
            value.obligationsClosed(),
            value.proofDebtAfter(),
            value.proofDebtDelta(),
            proposer,
            value.refuted(),
            review,
            value.roundsToFirstGain(),
            route,
            value.tokens() + Math.max(0, tokens),
            value.verifiedFactGain()));
    recompute(proposalId);
  }

  public synchronized void recordMaterialization(
      String proposalId, String action, boolean refuted) {
    InspirationOutcome value = outcomes.get(proposalId);
    if (value == null) {
      return;
    }
    outcomes.put(
        proposalId,
        with(
            value,
            value.citedByFinalProof(),
            action,
            !Set.of("shadow_only", "rejected").contains(action),
            value.obligationsClosed(),
            value.proofDebtAfter(),
            value.proofDebtDelta(),
            value.proposerCalls(),
            refuted,
            value.reviewCalls(),
            value.roundsToFirstGain(),
            value.routeCalls(),
            value.tokens(),
            value.verifiedFactGain()));
    recompute(proposalId);
  }

  public synchronized InspirationOutcome recordVerifiedGain(
      String proposalId,
      int roundIndex,
      double proofDebtAfter,
      List<String> obligationsClosed) {
    InspirationOutcome value = required(proposalId);
    List<String> closed =
        distinct(
            java.util.stream.Stream.concat(
                    value.obligationsClosed().stream(),
                    (obligationsClosed == null
                            ? List.<String>of()
                            : obligationsClosed)
                        .stream())
                .toList());
    Integer first = value.roundsToFirstGain();
    if (first == null) {
      first = Integer.valueOf(Math.max(0, roundIndex - value.roundCreated()));
    }
    double after = Math.max(0.0d, proofDebtAfter);
    outcomes.put(
        proposalId,
        with(
            value,
            value.citedByFinalProof(),
            value.materializationAction(),
            value.materialized(),
            closed,
            after,
            after - value.proofDebtBefore(),
            value.proposerCalls(),
            value.refuted(),
            value.reviewCalls(),
            first,
            value.routeCalls(),
            value.tokens(),
            value.verifiedFactGain() + 1));
    recompute(proposalId);
    return outcomes.get(proposalId);
  }

  public synchronized void markFinalCitation(String proposalId) {
    InspirationOutcome value = required(proposalId);
    outcomes.put(
        proposalId,
        with(
            value,
            true,
            value.materializationAction(),
            value.materialized(),
            value.obligationsClosed(),
            value.proofDebtAfter(),
            value.proofDebtDelta(),
            value.proposerCalls(),
            value.refuted(),
            value.reviewCalls(),
            value.roundsToFirstGain(),
            value.routeCalls(),
            value.tokens(),
            value.verifiedFactGain()));
    recompute(proposalId);
  }

  public synchronized Map<String, SelectionProfile> selectionProfiles(
      InspirationTriggerType triggerType,
      String domain,
      Set<InspirationMechanism> schedulable) {
    if (schedulable == null || schedulable.isEmpty()) {
      return Map.of();
    }
    Map<InspirationMechanism, List<InspirationOutcome>> grouped =
        new EnumMap<>(InspirationMechanism.class);
    java.util.stream.Stream.concat(historical.values().stream(), outcomes.values().stream())
        .filter(item -> item.triggerType() == triggerType)
        .filter(item -> item.domain().equals(domain) || item.domain().equals("unknown"))
        .filter(item -> schedulable.contains(item.mechanism()))
        .forEach(
            item ->
                grouped
                    .computeIfAbsent(item.mechanism(), ignored -> new ArrayList<>())
                    .add(item));
    int total = grouped.values().stream().mapToInt(List::size).sum();
    int minimum =
        schedulable.stream()
            .mapToInt(item -> grouped.getOrDefault(item, List.of()).size())
            .min()
            .orElse(0);
    int interval =
        rules.minimumExplorationRate() > 0.0d
            ? Math.max(1, (int) Math.round(1.0d / rules.minimumExplorationRate()))
            : 0;
    boolean scheduledExploration = interval > 0 && total % interval == 0;
    Map<String, SelectionProfile> result = new LinkedHashMap<>();
    schedulable.stream()
        .sorted(java.util.Comparator.comparing(InspirationMechanism::value))
        .forEach(
            mechanism -> {
              List<InspirationOutcome> values = grouped.getOrDefault(mechanism, List.of());
              int observations = values.size();
              double mean =
                  observations == 0
                      ? 0.0d
                      : values.stream().mapToDouble(InspirationOutcome::reward).sum()
                          / observations;
              double bonus =
                  rules.ucbWeight()
                      * Math.sqrt(Math.log(total + 2.0d) / (observations + 1.0d));
              result.put(
                  profileKey(triggerType, mechanism),
                  new SelectionProfile(
                      observations,
                      mean,
                      mean + bonus,
                      observations < rules.minimumObservations()
                          || (scheduledExploration && observations == minimum)));
            });
    return Map.copyOf(result);
  }

  public synchronized void loadHistorical(List<InspirationOutcome> values) {
    for (InspirationOutcome value : values == null ? List.<InspirationOutcome>of() : values) {
      historical.put(value.problemHash() + ":" + value.proposalId(), value);
    }
  }

  public synchronized Map<String, InspirationOutcome> snapshot() {
    return Map.copyOf(outcomes);
  }

  /** Prevents a mechanism from repeatedly materializing proposals without verified gain. */
  public synchronized boolean inNoGainCooldown(
      InspirationMechanism mechanism,
      int currentRound,
      int consecutiveRoundLimit,
      int cooldownRounds) {
    if (consecutiveRoundLimit < 1 || cooldownRounds < 1) {
      throw new IllegalArgumentException("cooldown limits must be positive");
    }
    Map<Integer, List<InspirationOutcome>> byRound = new java.util.TreeMap<>();
    outcomes.values().stream()
        .filter(item -> item.mechanism() == mechanism)
        .filter(InspirationOutcome::materialized)
        .filter(item -> item.roundCreated() < currentRound)
        .forEach(
            item ->
                byRound.computeIfAbsent(item.roundCreated(), ignored -> new ArrayList<>()).add(item));
    if (byRound.isEmpty()) {
      return false;
    }
    int latestRound = byRound.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
    if (currentRound - latestRound > cooldownRounds) {
      return false;
    }
    int expectedRound = latestRound;
    int noGainRounds = 0;
    for (int round : byRound.keySet().stream().sorted(java.util.Comparator.reverseOrder()).toList()) {
      if (round != expectedRound) {
        break;
      }
      boolean gained = byRound.get(round).stream().anyMatch(InspirationOutcomeLedger::hasGain);
      if (gained) {
        break;
      }
      noGainRounds++;
      if (noGainRounds >= consecutiveRoundLimit) {
        return true;
      }
      expectedRound--;
    }
    return false;
  }

  public static String profileKey(
      InspirationTriggerType triggerType, InspirationMechanism mechanism) {
    return triggerType.value() + ":" + mechanism.value();
  }

  private void recompute(String proposalId) {
    InspirationOutcome value = required(proposalId);
    int calls = value.proposerCalls() + value.reviewCalls() + value.routeCalls();
    double reward =
        2.0d * value.verifiedFactGain()
            + Math.max(0.0d, -value.proofDebtDelta())
            + value.obligationsClosed().size()
            + 2.0d * (value.citedByFinalProof() ? 1 : 0)
            - 0.1d * calls
            - value.tokens() / 100_000.0d
            - (value.refuted() ? 1.0d : 0.0d);
    outcomes.put(proposalId, withReward(value, reward));
  }

  private static boolean hasGain(InspirationOutcome value) {
    return value.verifiedFactGain() > 0
        || !value.obligationsClosed().isEmpty()
        || value.proofDebtDelta() < 0.0d;
  }

  private InspirationOutcome required(String proposalId) {
    InspirationOutcome value = outcomes.get(proposalId);
    if (value == null) {
      throw new IllegalArgumentException("unknown inspiration outcome: " + proposalId);
    }
    return value;
  }

  private static InspirationOutcome with(
      InspirationOutcome value,
      boolean cited,
      String action,
      boolean materialized,
      List<String> closed,
      Double debtAfter,
      double debtDelta,
      int proposerCalls,
      boolean refuted,
      int reviewCalls,
      Integer roundsToFirstGain,
      int routeCalls,
      int tokens,
      int verifiedGain) {
    return new InspirationOutcome(
        cited,
        value.creditObligationIds(),
        value.creditRouteIds(),
        value.domain(),
        action,
        materialized,
        value.mechanism(),
        value.obligationKinds(),
        closed,
        value.problemHash(),
        debtAfter,
        value.proofDebtBefore(),
        debtDelta,
        value.proposalId(),
        proposerCalls,
        refuted,
        reviewCalls,
        value.reward(),
        value.roundCreated(),
        roundsToFirstGain,
        routeCalls,
        value.taskId(),
        tokens,
        value.triggerType(),
        verifiedGain);
  }

  private static InspirationOutcome withReward(InspirationOutcome value, double reward) {
    return new InspirationOutcome(
        value.citedByFinalProof(),
        value.creditObligationIds(),
        value.creditRouteIds(),
        value.domain(),
        value.materializationAction(),
        value.materialized(),
        value.mechanism(),
        value.obligationKinds(),
        value.obligationsClosed(),
        value.problemHash(),
        value.proofDebtAfter(),
        value.proofDebtBefore(),
        value.proofDebtDelta(),
        value.proposalId(),
        value.proposerCalls(),
        value.refuted(),
        value.reviewCalls(),
        reward,
        value.roundCreated(),
        value.roundsToFirstGain(),
        value.routeCalls(),
        value.taskId(),
        value.tokens(),
        value.triggerType(),
        value.verifiedFactGain());
  }

  private static List<String> distinct(List<String> values) {
    return values == null ? List.of() : values.stream().distinct().toList();
  }

  public record SelectionProfile(
      int observations, double meanReward, double ucbScore, boolean forceExploration) {
    public static SelectionProfile empty() {
      return new SelectionProfile(0, 0.0d, 0.0d, false);
    }
  }
}
