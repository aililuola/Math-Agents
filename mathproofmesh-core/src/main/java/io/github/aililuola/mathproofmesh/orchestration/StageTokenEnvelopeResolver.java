package io.github.aililuola.mathproofmesh.orchestration;

import java.util.Objects;

/** Pure stage-token clamp shared by ordinary, continuation, repair, and deep-tier calls. */
public final class StageTokenEnvelopeResolver {
  public Resolution resolve(Request request) {
    Objects.requireNonNull(request, "request");
    long globalAfterFinish = request.globalRemainingTokens() - request.finishReserveTokens();
    if (globalAfterFinish < 0L) {
      return Resolution.blocked("FINISH_RESERVE_EXHAUSTED");
    }
    long availableTotal = Math.min(request.actionRemainingTotalTokens(), globalAfterFinish);
    if (request.estimatedInputTokens() > availableTotal) {
      return Resolution.blocked("INPUT_CONTEXT_EXCEEDS_BUDGET_ENVELOPE");
    }
    long outputBudget = availableTotal - request.estimatedInputTokens();
    long budgetBound =
        Math.min(request.actionRemainingOutputTokens(), outputBudget)
            - request.outputMeteringHeadroomTokens();
    long resolved =
        min(
            request.agentMaxOutputTokens(),
            request.providerMaxOutputTokens(),
            request.configuredStageLimit(),
            request.continuationOrDeepTierLimit(),
            budgetBound);
    if (resolved < 1L) {
      return Resolution.blocked("OUTPUT_TOKEN_BUDGET_EXHAUSTED");
    }
    return new Resolution(
        true,
        "ALLOW",
        Math.toIntExact(Math.min(Integer.MAX_VALUE, resolved)),
        request.estimatedInputTokens(),
        Math.addExact(
            request.estimatedInputTokens(),
            Math.addExact(resolved, request.outputMeteringHeadroomTokens())));
  }

  private static long min(long... values) {
    long result = Long.MAX_VALUE;
    for (long value : values) {
      result = Math.min(result, value);
    }
    return result;
  }

  public record Request(
      long estimatedInputTokens,
      long agentMaxOutputTokens,
      long providerMaxOutputTokens,
      long configuredStageLimit,
      long continuationOrDeepTierLimit,
      long actionRemainingOutputTokens,
      long actionRemainingTotalTokens,
      long globalRemainingTokens,
      long finishReserveTokens,
      long outputMeteringHeadroomTokens) {

    public Request(
        long estimatedInputTokens,
        long agentMaxOutputTokens,
        long providerMaxOutputTokens,
        long configuredStageLimit,
        long continuationOrDeepTierLimit,
        long actionRemainingOutputTokens,
        long actionRemainingTotalTokens,
        long globalRemainingTokens,
        long finishReserveTokens) {
      this(
          estimatedInputTokens,
          agentMaxOutputTokens,
          providerMaxOutputTokens,
          configuredStageLimit,
          continuationOrDeepTierLimit,
          actionRemainingOutputTokens,
          actionRemainingTotalTokens,
          globalRemainingTokens,
          finishReserveTokens,
          0L);
    }

    public Request {
      if (estimatedInputTokens < 0
          || agentMaxOutputTokens < 1
          || providerMaxOutputTokens < 1
          || configuredStageLimit < 1
          || continuationOrDeepTierLimit < 1
          || actionRemainingOutputTokens < 0
            || actionRemainingTotalTokens < 0
            || globalRemainingTokens < 0
            || finishReserveTokens < 0
            || outputMeteringHeadroomTokens < 0) {
        throw new IllegalArgumentException("invalid stage token envelope input");
      }
    }
  }

  public record Resolution(
      boolean allowed,
      String code,
      int maxOutputTokens,
      long estimatedInputTokens,
      long reservedTotalTokens) {

    public Resolution {
      code = code == null ? "" : code.strip();
      if (code.isEmpty()
          || maxOutputTokens < 0
          || estimatedInputTokens < 0
          || reservedTotalTokens < 0) {
        throw new IllegalArgumentException("invalid stage token envelope resolution");
      }
    }

    static Resolution blocked(String code) {
      return new Resolution(false, code, 0, 0L, 0L);
    }
  }
}
