package io.github.aililuola.mathproofmesh.provider;

import java.util.List;
import java.util.Optional;

public interface ProviderCallRepository {
  ProviderCallRecord plan(ProviderCallPlan plan);

  ProviderCallRecord transition(ProviderCallTransition transition);

  boolean markApplied(String runId, String callId, String applicationKey);

  Optional<ProviderCallRecord> findByIdempotencyKey(
      String runId, String idempotencyKey);

  List<ProviderCallRecord> findByRun(String runId);

  default UsageTotals usageTotals(String runId) {
    UsageTotals result = UsageTotals.zero();
    for (ProviderCallRecord call : findByRun(runId)) {
      if (call.state() == ProviderCallState.SUCCEEDED
          || call.state() == ProviderCallState.AMBIGUOUS) {
        result =
            result.plus(
                new UsageTotals(
                    1L,
                    call.inputTokens(),
                    call.outputTokens(),
                    call.costUsd().add(call.possibleDuplicateCostUsd()),
                    call.latencyMs()));
      }
    }
    return result;
  }
}
