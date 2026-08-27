package io.github.aililuola.mathproofmesh.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.GoalClarificationDecision;
import io.github.aililuola.mathproofmesh.contract.GoalClarificationRequest;
import io.github.aililuola.mathproofmesh.contract.GoalInterpretationCandidate;
import io.github.aililuola.mathproofmesh.contract.GoalNormalizationAssessment;
import io.github.aililuola.mathproofmesh.contract.LocalGoalPrecheck;
import io.github.aililuola.mathproofmesh.contract.ProblemKind;
import io.github.aililuola.mathproofmesh.contract.TaskRequirement;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GoalPreflightParityTest {
  private static final String AMBIGUOUS = "证明存在无穷多个两两同余的素数。";
  private static final String CANONICAL = "证明存在无穷多个模 4 余 1 的素数。";

  @Test
  void clearGoalStaysOnZeroApiFastPath() {
    LocalGoalPrecheck result =
        GoalPreflightFunctions.deterministicGoalPrecheck(
            "证明存在无穷多个模 4 余 1 的素数。");

    assertEquals("clear", result.status());
    assertEquals(List.of(), result.ruleIds());
  }

  @Test
  void missingCongruenceModulusRequiresModelReview() {
    LocalGoalPrecheck result =
        GoalPreflightFunctions.deterministicGoalPrecheck(AMBIGUOUS);

    assertEquals("model_review_required", result.status());
    assertEquals(List.of("congruence_missing_modulus"), result.ruleIds());
  }

  @Test
  void problemContractFreezesCanonicalGoalWithoutLosingOriginal() {
    GoalPreflightService service = new GoalPreflightService();
    GoalPreflightService.GoalPreflightOutcome outcome =
        service.prepare(
            AMBIGUOUS,
            new GoalPreflightService.GoalContext(
                ProblemKind.PROOF,
                "zh-CN",
                List.of("formal proof"),
                List.of("no external theorem"),
                List.of("lean"),
                List.of("prime means positive prime"),
                List.of(TaskRequirement.PROOF)),
            normalizer(),
            request ->
                new GoalClarificationDecision(
                    CANONICAL, request.requestId(), 0, "user_confirmed"));

    assertEquals(AMBIGUOUS, outcome.problem().originalStatement());
    assertEquals(CANONICAL, outcome.problem().exactStatement());
    assertEquals(CANONICAL, outcome.problem().canonicalStatement());
    assertEquals(CanonicalJson.stableHash(CANONICAL), outcome.problem().goalHash());
    assertEquals(outcome.problem().goalHash(), outcome.problem().integrityHash());
    assertEquals(ProblemKind.PROOF, outcome.problem().problemKind());
    assertEquals(List.of("formal proof"), outcome.problem().deliverables());
    assertEquals(List.of("no external theorem"), outcome.problem().hardConstraints());
    assertEquals("zh-CN", outcome.problem().outputLanguage());
  }

  @Test
  void goalNormalizerIsSmallAndNonThinking() {
    AtomicInteger maxTokens = new AtomicInteger();
    AtomicBoolean thinking = new AtomicBoolean(true);
    GoalPreflightService.GoalNormalizer observing =
        (statement, precheck, limit, thinkingEnabled) -> {
          maxTokens.set(limit);
          thinking.set(thinkingEnabled);
          return assessment();
        };
    GoalPreflightService service = new GoalPreflightService();

    service.prepare(
        AMBIGUOUS,
        null,
        observing,
        request ->
            new GoalClarificationDecision(
                CANONICAL, request.requestId(), 0, "user_confirmed"));
    SystemConfig config =
        new StrictYamlConfigLoader()
            .load(
                Path.of(System.getProperty("mathproofmesh.projectRoot"))
                    .resolve("config/application.yaml"));

    assertEquals(4096, maxTokens.get());
    assertFalse(thinking.get());
    assertEquals(
        "disabled",
        config.runtime().stageThinkingModes().get("goal_normalization"));
  }

  @Test
  void clearGoalNeverCallsNormalizer() {
    AtomicBoolean called = new AtomicBoolean();
    GoalPreflightService.GoalNormalizer forbidden =
        (statement, precheck, maxTokens, thinkingEnabled) -> {
          called.set(true);
          throw new AssertionError("clear goal must not invoke the normalizer");
        };

    GoalPreflightService.GoalPreflightOutcome outcome =
        new GoalPreflightService()
            .prepare("Prove that 1 + 1 = 2.", null, forbidden, null);

    assertFalse(called.get());
    assertFalse(outcome.apiCall());
    assertEquals("original", outcome.problem().interpretationSource());
    assertEquals(
        outcome.problem().originalStatement(),
        outcome.problem().canonicalStatement());
  }

  @Test
  void ambiguousGoalWaitsForUserBeforeFreezing() {
    GoalPreflightService service = new GoalPreflightService();

    GoalClarificationRequired pending =
        assertThrows(
            GoalClarificationRequired.class,
            () -> service.prepare(AMBIGUOUS, null, normalizer(), null));
    assertNotNull(pending.request());
    assertEquals(AMBIGUOUS, pending.request().originalStatement());

    AtomicReference<GoalClarificationRequest> seen = new AtomicReference<>();
    GoalPreflightService.GoalPreflightOutcome outcome =
        service.prepare(
            AMBIGUOUS,
            null,
            normalizer(),
            request -> {
              seen.set(request);
              return new GoalClarificationDecision(
                  CANONICAL, request.requestId(), 0, "user_confirmed");
            });

    assertNotNull(seen.get());
    assertTrue(outcome.apiCall());
    assertEquals("user_confirmed", outcome.problem().interpretationSource());
    assertEquals("planner-agent", outcome.problem().interpretationAgentId());
    assertEquals(AMBIGUOUS, outcome.problem().originalStatement());
    assertEquals(CANONICAL, outcome.problem().canonicalStatement());
  }

  private static GoalPreflightService.GoalNormalizer normalizer() {
    return new GoalPreflightService.GoalNormalizer() {
      @Override
      public GoalNormalizationAssessment normalize(
          String statement,
          LocalGoalPrecheck precheck,
          int maxOutputTokens,
          boolean thinkingEnabled) {
        return assessment();
      }

      @Override
      public String agentId() {
        return "planner-agent";
      }

      @Override
      public String rawReference() {
        return "raw:goal-normalization";
      }
    };
  }

  private static GoalNormalizationAssessment assessment() {
    return new GoalNormalizationAssessment(
        List.of(
            new GoalInterpretationCandidate(
                0.75d,
                "another common congruence class",
                "证明存在无穷多个模 3 余 1 的素数。")),
        List.of("同余关系缺少模数。"),
        true,
        "你要证明模 4 还是模 3？",
        true,
        false,
        0.91d,
        CANONICAL);
  }
}
