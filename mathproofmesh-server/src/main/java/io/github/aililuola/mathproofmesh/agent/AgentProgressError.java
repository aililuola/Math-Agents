package io.github.aililuola.mathproofmesh.agent;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.UsageRecord;
import java.util.LinkedHashMap;
import java.util.Map;

@SuppressWarnings("serial")
@SuppressFBWarnings(
    value = "SE_BAD_FIELD",
    justification =
        "This process-local control-flow exception is never serialized; its immutable "
            + "UsageRecord and progress snapshot are needed by the audited caller.")
public class AgentProgressError extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final UsageRecord usage;
  private final Map<String, Object> progress;

  public AgentProgressError(
      String message,
      UsageRecord usage,
      Map<String, ?> progress,
      Throwable cause) {
    super(message, cause);
    this.usage = usage == null ? new UsageRecord() : usage;
    this.progress =
        Map.copyOf(
            new LinkedHashMap<>(
                progress == null ? Map.of() : progress));
  }

  public UsageRecord usage() {
    return usage;
  }

  public Map<String, Object> progress() {
    return progress;
  }
}
