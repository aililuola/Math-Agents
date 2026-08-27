package io.github.aililuola.mathproofmesh.orchestration;

import io.github.aililuola.mathproofmesh.contract.BudgetAction;
import io.github.aililuola.mathproofmesh.contract.BudgetDecision;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Converts a soft-budget decision into an explicit inspiration admission. */
public final class InspirationPhaseService {
  public InspirationAdmission admit(List<String> taskIds, BudgetDecision decision) {
    java.util.Objects.requireNonNull(decision, "decision");
    List<String> tasks =
        taskIds == null
            ? List.of()
            : taskIds.stream().map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
    java.util.Set<String> selected =
        decision.actions().stream()
            .filter(BudgetAction::selected)
            .map(BudgetAction::targetId)
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    ArrayList<String> admitted = new ArrayList<>();
    LinkedHashMap<String, String> rejected = new LinkedHashMap<>();
    for (String task : tasks) {
      if (selected.contains(task)) {
        admitted.add(task);
      } else {
        String reason =
            decision.candidates().stream()
                .filter(action -> task.equals(action.targetId()))
                .map(BudgetAction::blockedReason)
                .filter(java.util.Objects::nonNull)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("scheduler did not admit this task");
        rejected.put(task, reason);
      }
    }
    return new InspirationAdmission(admitted, rejected, decision);
  }
}
