package io.github.aililuola.mathproofmesh.provider;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public final class AgentCallFailure extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String agentId;
  private final ProviderException providerFailure;
  private final int retries;

  public AgentCallFailure(
      String agentId, ProviderException providerFailure, int retries) {
    super(
        "agent "
            + agentId
            + " failed after bounded retries: "
            + providerFailure.kind(),
        providerFailure);
    this.agentId = java.util.Objects.requireNonNull(agentId, "agentId");
    this.providerFailure =
        java.util.Objects.requireNonNull(providerFailure, "providerFailure");
    this.retries = retries;
  }

  public String agentId() {
    return agentId;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "The exact typed provider failure is intentionally exposed to the bounded "
              + "failover policy; callers cannot change its immutable classification.")
  public ProviderException providerFailure() {
    return providerFailure;
  }

  public int retries() {
    return retries;
  }

  public boolean retryable() {
    return providerFailure.retryable();
  }
}
