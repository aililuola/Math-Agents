package io.github.aililuola.mathproofmesh.inspiration;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.InspirationMechanism;
import java.text.Normalizer;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable, core-owned policy derived from external configuration. */
public record InspirationPolicy(
    Mode mode,
    Set<InspirationMechanism> enabledMechanisms,
    Limits limits,
    NoveltyRules novelty,
    ComposerRules composer,
    SurpriseRules surprise,
    AdaptiveRules adaptive,
    boolean requireIndependentReferee) {

  public InspirationPolicy {
    mode = Objects.requireNonNull(mode, "mode");
    enabledMechanisms =
        enabledMechanisms == null
            ? Set.of()
            : Set.copyOf(enabledMechanisms);
    limits = Objects.requireNonNull(limits, "limits");
    novelty = Objects.requireNonNull(novelty, "novelty");
    composer = Objects.requireNonNull(composer, "composer");
    surprise = Objects.requireNonNull(surprise, "surprise");
    adaptive = Objects.requireNonNull(adaptive, "adaptive");
  }

  public static InspirationPolicy defaults(Mode mode) {
    return new InspirationPolicy(
        mode,
        EnumSet.allOf(InspirationMechanism.class),
        new Limits(8, 4, 3, 2, 1, 2, 2, 1, 8, 4, 8_000),
        new NoveltyRules(0.35d, 0.85d, 0.20d, 0.25d, 0.15d, 0.15d, 0.15d, 0.10d),
        new ComposerRules(4, 3, 8, true),
        new SurpriseRules(0.15d, 1, 4, 2, 2),
        new AdaptiveRules(1, 0.20d, 1.0d),
        true);
  }

  public InspirationPolicy withMode(Mode replacement) {
    return new InspirationPolicy(
        replacement,
        enabledMechanisms,
        limits,
        novelty,
        composer,
        surprise,
        adaptive,
        requireIndependentReferee);
  }

  public boolean runs() {
    return mode != Mode.OFF;
  }

  public boolean recordsOnly() {
    return mode == Mode.SHADOW;
  }

  public boolean mayMutateBusinessState() {
    return mode == Mode.ACTIVE;
  }

  public Map<String, Boolean> authorityBoundary() {
    return Map.of(
        "may_write_fact", false,
        "may_close_checkpoint", false,
        "may_close_obligation", false,
        "may_change_problem_hash", false,
        "may_attach_reviewed_proposal", mayMutateBusinessState());
  }

  public enum Mode {
    OFF,
    SHADOW,
    ACTIVE;

    @SuppressFBWarnings(
        value = "IMPROPER_UNICODE",
        justification =
            "NFKC plus Locale.ROOT is intentional; accepted mode tokens are fixed ASCII literals")
    public static Mode parse(String value) {
      String normalized =
          Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
              .strip()
              .toLowerCase(java.util.Locale.ROOT);
      return switch (normalized) {
        case "off" -> OFF;
        case "shadow" -> SHADOW;
        case "active" -> ACTIVE;
        default -> throw new IllegalArgumentException("unsupported inspiration mode: " + value);
      };
    }
  }

  public record Limits(
      int maxTasksPerRound,
      int maxProposalsPerTask,
      int maxReviewedPerTask,
      int maxMaterializedPerTrigger,
      int maxNewRoutesPerTrigger,
      int maxSingleAgentProposals,
      int coldContextProposals,
      int finalizationReserveCalls,
      int warmContextMaxFacts,
      int warmContextMaxNegatives,
      int contextMaxChars) {
    public Limits {
      positive(maxTasksPerRound, "maxTasksPerRound");
      positive(maxProposalsPerTask, "maxProposalsPerTask");
      positive(maxReviewedPerTask, "maxReviewedPerTask");
      nonnegative(maxMaterializedPerTrigger, "maxMaterializedPerTrigger");
      nonnegative(maxNewRoutesPerTrigger, "maxNewRoutesPerTrigger");
      positive(maxSingleAgentProposals, "maxSingleAgentProposals");
      nonnegative(coldContextProposals, "coldContextProposals");
      nonnegative(finalizationReserveCalls, "finalizationReserveCalls");
      nonnegative(warmContextMaxFacts, "warmContextMaxFacts");
      nonnegative(warmContextMaxNegatives, "warmContextMaxNegatives");
      positive(contextMaxChars, "contextMaxChars");
    }
  }

  public record NoveltyRules(
      double threshold,
      double duplicateThreshold,
      double representationWeight,
      double mechanismWeight,
      double objectWeight,
      double transformationWeight,
      double principleWeight,
      double obligationWeight) {
    public NoveltyRules {
      unit(threshold, "threshold");
      unit(duplicateThreshold, "duplicateThreshold");
      nonnegativeFinite(representationWeight, "representationWeight");
      nonnegativeFinite(mechanismWeight, "mechanismWeight");
      nonnegativeFinite(objectWeight, "objectWeight");
      nonnegativeFinite(transformationWeight, "transformationWeight");
      nonnegativeFinite(principleWeight, "principleWeight");
      nonnegativeFinite(obligationWeight, "obligationWeight");
    }
  }

  public record ComposerRules(
      int maxCandidatesPerRound,
      int maxSources,
      int maxCombinedCost,
      boolean requireQuickFalsification) {
    public ComposerRules {
      nonnegative(maxCandidatesPerRound, "maxCandidatesPerRound");
      if (maxSources < 2) {
        throw new IllegalArgumentException("maxSources must be at least 2");
      }
      positive(maxCombinedCost, "maxCombinedCost");
    }
  }

  public record SurpriseRules(
      double budgetFraction,
      int minimumCalls,
      int maximumCalls,
      int maxConsecutiveRejections,
      int cooldownRounds) {
    public SurpriseRules {
      unit(budgetFraction, "budgetFraction");
      nonnegative(minimumCalls, "minimumCalls");
      nonnegative(maximumCalls, "maximumCalls");
      if (maximumCalls < minimumCalls) {
        throw new IllegalArgumentException("maximumCalls cannot be below minimumCalls");
      }
      positive(maxConsecutiveRejections, "maxConsecutiveRejections");
      nonnegative(cooldownRounds, "cooldownRounds");
    }
  }

  public record AdaptiveRules(
      int minimumObservations, double minimumExplorationRate, double ucbWeight) {
    public AdaptiveRules {
      nonnegative(minimumObservations, "minimumObservations");
      unit(minimumExplorationRate, "minimumExplorationRate");
      nonnegativeFinite(ucbWeight, "ucbWeight");
    }
  }

  private static void positive(int value, String field) {
    if (value <= 0) {
      throw new IllegalArgumentException(field + " must be positive");
    }
  }

  private static void nonnegative(int value, String field) {
    if (value < 0) {
      throw new IllegalArgumentException(field + " must be nonnegative");
    }
  }

  private static void unit(double value, String field) {
    if (!Double.isFinite(value) || value < 0.0d || value > 1.0d) {
      throw new IllegalArgumentException(field + " must be in [0, 1]");
    }
  }

  private static void nonnegativeFinite(double value, String field) {
    if (!Double.isFinite(value) || value < 0.0d) {
      throw new IllegalArgumentException(field + " must be finite and nonnegative");
    }
  }
}
