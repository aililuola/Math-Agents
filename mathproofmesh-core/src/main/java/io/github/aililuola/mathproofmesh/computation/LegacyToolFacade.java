package io.github.aililuola.mathproofmesh.computation;

/** Stable migration name for the legacy tools.py facade. */
public final class LegacyToolFacade {
  private final ToolBroker broker;

  public LegacyToolFacade(ToolBroker broker) {
    this.broker = java.util.Objects.requireNonNull(broker, "broker");
  }

  public ToolBroker broker() {
    return broker;
  }
}
