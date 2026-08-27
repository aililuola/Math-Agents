package io.github.aililuola.mathproofmesh.inspiration;

import io.github.aililuola.mathproofmesh.contract.InspirationMechanism;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Ordered mechanism registry used by trigger scheduling and fair rotation. */
public final class InspirationMechanismRegistry {
  private static final List<InspirationMechanism> SCHEDULABLE =
      List.of(
          InspirationMechanism.REPRESENTATION_SWITCH,
          InspirationMechanism.STRUCTURAL_ANALOGY,
          InspirationMechanism.AUXILIARY_CONSTRUCTION,
          InspirationMechanism.INVARIANT_HYPOTHESIS,
          InspirationMechanism.REVERSE_GOAL_ANALYSIS,
          InspirationMechanism.BRIDGE_LEMMA,
          InspirationMechanism.SURPRISE_EXPLORATION,
          InspirationMechanism.META_REPLAN);

  private final Set<InspirationMechanism> enabled;

  public InspirationMechanismRegistry(Set<InspirationMechanism> enabled) {
    this.enabled = enabled == null ? Set.of() : Set.copyOf(enabled);
  }

  public List<InspirationMechanism> enabledSchedulable() {
    return SCHEDULABLE.stream().filter(enabled::contains).toList();
  }

  public boolean isSchedulable(InspirationMechanism mechanism) {
    return mechanism != null && enabled.contains(mechanism) && SCHEDULABLE.contains(mechanism);
  }

  public InspirationMechanism fairChoice(
      Map<InspirationMechanism, Integer> selections,
      Map<InspirationMechanism, Integer> cooldownUntilRound,
      int roundIndex) {
    Map<InspirationMechanism, Integer> safeSelections = new EnumMap<>(InspirationMechanism.class);
    if (selections != null) {
      safeSelections.putAll(selections);
    }
    Map<InspirationMechanism, Integer> safeCooldowns = new EnumMap<>(InspirationMechanism.class);
    if (cooldownUntilRound != null) {
      safeCooldowns.putAll(cooldownUntilRound);
    }
    return enabledSchedulable().stream()
        .filter(item -> safeCooldowns.getOrDefault(item, -1) <= roundIndex)
        .min(
            Comparator.comparingInt(
                    (InspirationMechanism item) -> safeSelections.getOrDefault(item, 0))
                .thenComparingInt(SCHEDULABLE::indexOf))
        .orElseThrow(() -> new IllegalStateException("no schedulable inspiration mechanism"));
  }
}
