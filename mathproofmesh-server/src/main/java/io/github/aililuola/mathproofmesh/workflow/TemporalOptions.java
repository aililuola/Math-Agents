package io.github.aililuola.mathproofmesh.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import java.time.Duration;

/** Fixed timeout and retry policy for bounded workflow activities. */
public final class TemporalOptions {
  private TemporalOptions() {}

  public static ActivityOptions activities() {
    return ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofSeconds(30))
        .setScheduleToCloseTimeout(Duration.ofMinutes(2))
        .setHeartbeatTimeout(Duration.ofSeconds(10))
        .setRetryOptions(
            RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofMillis(100))
                .setMaximumInterval(Duration.ofSeconds(2))
                .setBackoffCoefficient(2.0d)
                .setMaximumAttempts(3)
                .build())
        .build();
  }
}
