package io.github.aililuola.mathproofmesh.config;

import io.github.aililuola.mathproofmesh.contract.GoalClarificationRequest;
import java.util.Objects;

public final class GoalClarificationRequired extends GoalNormalizationError {
  private static final long serialVersionUID = 1L;

  private final transient GoalClarificationRequest request;

  public GoalClarificationRequired(GoalClarificationRequest request) {
    super("the submitted problem is ambiguous and requires explicit confirmation");
    this.request = Objects.requireNonNull(request, "request");
  }

  public GoalClarificationRequest request() {
    return request;
  }
}
