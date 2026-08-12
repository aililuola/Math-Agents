package io.github.aililuola.mathproofmesh.config;

import io.github.aililuola.mathproofmesh.contract.GoalClarificationDecision;
import io.github.aililuola.mathproofmesh.contract.GoalClarificationRequest;
import io.github.aililuola.mathproofmesh.contract.GoalNormalizationAssessment;
import io.github.aililuola.mathproofmesh.contract.LocalGoalPrecheck;
import io.github.aililuola.mathproofmesh.contract.ProblemContract;
import io.github.aililuola.mathproofmesh.contract.ProblemKind;
import io.github.aililuola.mathproofmesh.contract.TaskRequirement;
import java.util.List;
import java.util.Objects;

public final class GoalPreflightService {
  public static final int NORMALIZATION_MAX_OUTPUT_TOKENS = 4096;

  public GoalPreflightOutcome prepare(
      String originalStatement,
      GoalContext context,
      GoalNormalizer normalizer,
      ClarificationResolver clarificationResolver) {
    String original = Objects.requireNonNull(originalStatement, "originalStatement").strip();
    if (original.isEmpty()) {
      throw new GoalNormalizationError("the submitted problem cannot be empty");
    }
    GoalContext safeContext = context == null ? GoalContext.defaults() : context;
    LocalGoalPrecheck precheck =
        GoalPreflightFunctions.deterministicGoalPrecheck(original);
    if ("clear".equals(precheck.status())) {
      return new GoalPreflightOutcome(
          freeze(
              original,
              original,
              "original",
              1.0d,
              null,
              null,
              List.of(),
              safeContext),
          precheck,
          false);
    }
    if (normalizer == null) {
      throw new GoalNormalizationError(
          "a goal normalizer is required after deterministic review findings");
    }
    GoalNormalizationAssessment assessment =
        Objects.requireNonNull(
            normalizer.normalize(
                original,
                precheck,
                NORMALIZATION_MAX_OUTPUT_TOKENS,
                false),
            "normalizer result");
    GoalClarificationRequest request =
        new GoalClarificationRequest(assessment, precheck, original, null);

    GoalClarificationDecision decision;
    if (GoalPreflightFunctions.requiresConfirmation(assessment)) {
      if (clarificationResolver == null) {
        throw new GoalClarificationRequired(request);
      }
      decision =
          GoalPreflightFunctions.validateClarificationDecision(
              request,
              Objects.requireNonNull(
                  clarificationResolver.resolve(request),
                  "clarification decision"));
    } else {
      decision =
          GoalPreflightFunctions.validateClarificationDecision(
              request,
              new GoalClarificationDecision(
                  assessment.recommendedStatement(),
                  request.requestId(),
                  0,
                  "auto_assumed"));
    }
    ProblemContract problem =
        freeze(
            original,
            decision.canonicalStatement(),
            decision.source(),
            GoalPreflightFunctions.decisionConfidence(request, decision),
            normalizer.agentId(),
            normalizer.rawReference(),
            assessment.ambiguityReasons(),
            safeContext);
    return new GoalPreflightOutcome(problem, precheck, true);
  }

  private static ProblemContract freeze(
      String original,
      String canonical,
      String source,
      double confidence,
      String agentId,
      String rawReference,
      List<String> reasons,
      GoalContext context) {
    return new ProblemContract(
        context.allowedTools(),
        canonical,
        null,
        context.definitions(),
        context.deliverables(),
        canonical,
        null,
        context.hardConstraints(),
        null,
        agentId,
        confidence,
        rawReference,
        reasons,
        source,
        canonical,
        original,
        context.outputLanguage(),
        null,
        context.problemKind(),
        null,
        context.taskRequirements());
  }

  @FunctionalInterface
  public interface ClarificationResolver {
    GoalClarificationDecision resolve(GoalClarificationRequest request);
  }

  public interface GoalNormalizer {
    GoalNormalizationAssessment normalize(
        String statement,
        LocalGoalPrecheck precheck,
        int maxOutputTokens,
        boolean thinkingEnabled);

    default String agentId() {
      return null;
    }

    default String rawReference() {
      return null;
    }
  }

  public record GoalContext(
      ProblemKind problemKind,
      String outputLanguage,
      List<String> deliverables,
      List<String> hardConstraints,
      List<String> allowedTools,
      List<String> definitions,
      List<TaskRequirement> taskRequirements
  ) {
    public GoalContext {
      problemKind = problemKind == null ? ProblemKind.UNKNOWN : problemKind;
      outputLanguage =
          outputLanguage == null || outputLanguage.isBlank()
              ? "zh-CN"
              : outputLanguage.strip();
      deliverables = deliverables == null ? List.of() : List.copyOf(deliverables);
      hardConstraints =
          hardConstraints == null ? List.of() : List.copyOf(hardConstraints);
      allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
      definitions = definitions == null ? List.of() : List.copyOf(definitions);
      taskRequirements =
          taskRequirements == null
              ? List.of(TaskRequirement.PROOF)
              : List.copyOf(taskRequirements);
    }

    public static GoalContext defaults() {
      return new GoalContext(null, null, null, null, null, null, null);
    }
  }

  public record GoalPreflightOutcome(
      ProblemContract problem,
      LocalGoalPrecheck precheck,
      boolean apiCall
  ) {
    public GoalPreflightOutcome {
      Objects.requireNonNull(problem, "problem");
      Objects.requireNonNull(precheck, "precheck");
    }
  }
}
