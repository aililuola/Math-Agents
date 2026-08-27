package io.github.aililuola.mathproofmesh.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class Phase17AgentCapabilityBranchTest {

  @Test
  void settingsAndDimensionsFailClosed() {
    assertThatThrownBy(() -> new AgentCapabilityProfile(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new AgentCapabilityProfile.Settings(0, 1, 1, 1, 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    for (double invalid : new double[] {-1, 2, Double.NaN}) {
      assertThatThrownBy(
              () -> new AgentCapabilityProfile.Settings(1, invalid, 0, 0, 0, 0))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () -> new AgentCapabilityProfile.Settings(1, 1, invalid, 0, 0, 0))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () -> new AgentCapabilityProfile.Settings(1, 1, 0, invalid, 0, 0))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () -> new AgentCapabilityProfile.Settings(1, 1, 0, 0, invalid, 0))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () -> new AgentCapabilityProfile.Settings(1, 1, 0, 0, 0, invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }

    AgentCapabilityProfile profile =
        new AgentCapabilityProfile(AgentCapabilityProfile.Settings.defaults());
    assertThatThrownBy(() -> profile.get("agent", "bad", "prover"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> profile.get("agent", "algebra", "bad"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> profile.update("agent", "algebra", "prover", null, true, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> profile.selectBest(List.of(), "algebra", "prover"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void empiricalUpdatesCoverEveryObservationWeightAndPenalty() {
    AgentCapabilityProfile profile =
        new AgentCapabilityProfile(
            new AgentCapabilityProfile.Settings(1, 0.5, 0.4, 0.3, 0.2, 0.5));
    for (CapabilityObservationKind kind : CapabilityObservationKind.values()) {
      profile.update("agent-" + kind, "algebra", "prover", kind, true, 0.99);
      profile.update("agent-" + kind, "algebra", "prover", kind, false, null);
    }
    assertThat(profile.ignoredSelfReports()).isEqualTo(CapabilityObservationKind.values().length);
    assertThat(profile.cells()).hasSize(CapabilityObservationKind.values().length);

    AgentCapabilityProfile recentFloor =
        new AgentCapabilityProfile(
            new AgentCapabilityProfile.Settings(1, 1, 1, 1, 1, 1));
    assertThat(
            recentFloor
                .update(
                    "floor",
                    "logic",
                    "skeptic",
                    CapabilityObservationKind.RECENT_TASK,
                    true,
                    null)
                .score())
        .isEqualTo(1.0d);

    AgentCapabilityProfile delayed =
        new AgentCapabilityProfile(AgentCapabilityProfile.Settings.defaults());
    assertThat(
            delayed
                .update(
                    "delayed",
                    "geometry",
                    "prover",
                    CapabilityObservationKind.TOOL_AGREEMENT,
                    true,
                    null)
                .score())
        .isEqualTo(0.5d);
    delayed.update(
        "best",
        "geometry",
        "prover",
        CapabilityObservationKind.MUTATION_BENCHMARK,
        true,
        null);
    assertThat(delayed.selectBest(List.of("delayed", "best"), "geometry", "prover"))
        .isEqualTo("best");
    assertThat(delayed.selectBest(List.of("z", "a"), "number_theory", "prover"))
        .isEqualTo("a");
  }

  @Test
  void domainInferenceCoversAllDocumentedFamiliesAndFallback() {
    assertThat(AgentCapabilityProfile.inferDomain((String[]) null)).isEqualTo("algebra");
    assertThat(AgentCapabilityProfile.inferDomain("triangle angle")).isEqualTo("geometry");
    assertThat(AgentCapabilityProfile.inferDomain("prime modulo")).isEqualTo("number_theory");
    assertThat(AgentCapabilityProfile.inferDomain("graph coloring")).isEqualTo("combinatorics");
    assertThat(AgentCapabilityProfile.inferDomain("Jensen inequality")).isEqualTo("inequalities");
    assertThat(AgentCapabilityProfile.inferDomain("logical quantifier")).isEqualTo("logic");
    assertThat(AgentCapabilityProfile.inferDomain("python program")).isEqualTo("computation");
    assertThat(AgentCapabilityProfile.inferDomain("polynomial identity")).isEqualTo("algebra");
  }
}
