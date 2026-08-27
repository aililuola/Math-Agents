package io.github.aililuola.mathproofmesh.inspiration;

/** Small composition root for the phase-11 inspiration services. */
public record InspirationFacade(
    InspirationEngine engine,
    TriggerPolicy triggers,
    InspirationOutcomeLedger outcomes,
    InspirationAssignmentPlanner assignments,
    InspirationComposer composer,
    PersistentMetaStrategist metaStrategist,
    MetaDirectiveController metaDirectives) {
  public InspirationFacade {
    java.util.Objects.requireNonNull(engine, "engine");
    java.util.Objects.requireNonNull(triggers, "triggers");
    java.util.Objects.requireNonNull(outcomes, "outcomes");
    java.util.Objects.requireNonNull(assignments, "assignments");
    java.util.Objects.requireNonNull(composer, "composer");
    java.util.Objects.requireNonNull(metaStrategist, "metaStrategist");
    java.util.Objects.requireNonNull(metaDirectives, "metaDirectives");
  }
}
