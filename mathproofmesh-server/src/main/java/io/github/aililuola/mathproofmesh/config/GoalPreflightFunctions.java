package io.github.aililuola.mathproofmesh.config;

import io.github.aililuola.mathproofmesh.contract.GoalClarificationDecision;
import io.github.aililuola.mathproofmesh.contract.GoalClarificationRequest;
import io.github.aililuola.mathproofmesh.contract.GoalInterpretationCandidate;
import io.github.aililuola.mathproofmesh.contract.GoalNormalizationAssessment;
import io.github.aililuola.mathproofmesh.contract.LocalGoalPrecheck;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class GoalPreflightFunctions {
  private static final Pattern CONGRUENCE_ZH = Pattern.compile("同余");
  private static final Pattern CONGRUENCE_EN =
      Pattern.compile("\\bcongruent\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern MODULUS_MARKER =
      Pattern.compile(
          "(?:模\\s*[^，。；;,.!?？\\s]+|\\\\pmod\\b|\\\\bmod\\b|"
              + "\\bmod(?:ulo)?\\b)",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern INCOMPLETE_PLACEHOLDER =
      Pattern.compile(
          "(?:\\[\\s*\\?\\s*\\]|<\\s*(?:missing|unknown|\\?+)\\s*>|"
              + "待补充|待定|\\b(?:TBD|TODO)\\b)",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern MISSING_EXTERNAL_CONTEXT =
      Pattern.compile(
          "(?:如图|见图|下图|上图|附件|上述定理|上述条件|as shown|"
              + "the figure below|the attached (?:figure|file))",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern INCOMPLETE_QUANTIFIER =
      Pattern.compile(
          "(?:对(?:任意|所有)\\s*[，,。.]|存在\\s*(?:使得|满足)|"
              + "\\bfor\\s+(?:all|every)\\s*[,.:;])",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern INCOMPLETE_RESIDUE =
      Pattern.compile(
          "(?:模\\s*[^\\s，。；;,.!?？]+\\s*余\\s*(?:$|[，。；;,.!?？])|"
              + "模\\s*余\\s*[^\\s，。；;,.!?？]+)");

  private GoalPreflightFunctions() {}

  public static LocalGoalPrecheck deterministicGoalPrecheck(String statement) {
    String text = Objects.requireNonNull(statement, "statement").strip();
    List<String> ruleIds = new ArrayList<>();
    List<String> reasons = new ArrayList<>();

    boolean hasCongruence =
        CONGRUENCE_ZH.matcher(text).find() || CONGRUENCE_EN.matcher(text).find();
    if (hasCongruence && !MODULUS_MARKER.matcher(text).find()) {
      add(
          ruleIds,
          reasons,
          "congruence_missing_modulus",
          "同余关系没有明确给出模数，可能对应多个不同的数学命题。");
    }
    if (INCOMPLETE_PLACEHOLDER.matcher(text).find()) {
      add(
          ruleIds,
          reasons,
          "unresolved_placeholder",
          "题目中仍有待补充的占位内容，无法冻结为完整数学目标。");
    }
    if (MISSING_EXTERNAL_CONTEXT.matcher(text).find()) {
      add(
          ruleIds,
          reasons,
          "missing_external_context",
          "题目引用了图形、附件或前文，但当前提交内容中没有对应上下文。");
    }
    if (INCOMPLETE_QUANTIFIER.matcher(text).find()) {
      add(
          ruleIds,
          reasons,
          "quantifier_missing_variable",
          "量词没有明确约束对象，变量范围可能缺失。");
    }
    if (INCOMPLETE_RESIDUE.matcher(text).find()) {
      add(
          ruleIds,
          reasons,
          "residue_missing_parameter",
          "模数或余数没有完整给出，目标命题尚未确定。");
    }
    return ruleIds.isEmpty()
        ? new LocalGoalPrecheck(List.of(), List.of(), "clear")
        : new LocalGoalPrecheck(reasons, ruleIds, "model_review_required");
  }

  public static List<String> candidateStatements(GoalClarificationRequest request) {
    Objects.requireNonNull(request, "request");
    List<String> candidates = new ArrayList<>();
    candidates.add(request.assessment().recommendedStatement());
    request.assessment().alternativeInterpretations().stream()
        .map(GoalInterpretationCandidate::statement)
        .forEach(candidates::add);
    return List.copyOf(candidates);
  }

  public static GoalClarificationDecision validateClarificationDecision(
      GoalClarificationRequest request, GoalClarificationDecision decision) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(decision, "decision");
    if (!decision.requestId().equals(request.requestId())) {
      throw new IllegalArgumentException(
          "clarification decision does not match the pending request");
    }
    List<String> candidates = candidateStatements(request);
    Integer index = decision.selectedCandidateIndex();
    if (index != null) {
      if (index >= candidates.size()) {
        throw new IllegalArgumentException("selected goal interpretation does not exist");
      }
      if (!decision.canonicalStatement().equals(candidates.get(index))) {
        throw new IllegalArgumentException(
            "canonical statement does not match the selected interpretation");
      }
    }
    if ("auto_assumed".equals(decision.source())) {
      if (!Integer.valueOf(0).equals(index)) {
        throw new IllegalArgumentException(
            "automatic interpretation may only use the recommendation");
      }
      if (!decision.canonicalStatement()
          .equals(request.assessment().recommendedStatement())) {
        throw new IllegalArgumentException(
            "automatic interpretation must use the recommendation");
      }
    }
    return decision;
  }

  public static double decisionConfidence(
      GoalClarificationRequest request, GoalClarificationDecision decision) {
    Integer index = decision.selectedCandidateIndex();
    if (index == null) {
      return "user_confirmed".equals(decision.source()) ? 1.0d : 0.0d;
    }
    if (index == 0) {
      return request.assessment().recommendationConfidence();
    }
    return request.assessment().alternativeInterpretations().get(index - 1).confidence();
  }

  public static boolean requiresConfirmation(GoalNormalizationAssessment assessment) {
    Objects.requireNonNull(assessment, "assessment");
    return assessment.hasAmbiguity()
        || !assessment.isWellFormed()
        || assessment.changesMathematicalMeaning();
  }

  private static void add(
      List<String> ruleIds,
      List<String> reasons,
      String ruleId,
      String reason) {
    ruleIds.add(ruleId);
    reasons.add(reason);
  }
}
