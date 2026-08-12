package io.github.aililuola.mathproofmesh.orchestration.teams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.RouteRole;
import java.util.List;
import org.junit.jupiter.api.Test;

class Phase17RouteTeamBranchTest {

  @Test
  void thresholdsAndNullSignalsFailClosed() {
    for (double invalid : new double[] {-0.1, 1.1, Double.NaN}) {
      assertThatThrownBy(() -> new RouteTeam(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new RouteTeam(0.5).classifyRisk(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void eachRiskSignalContributesItsReasonAndMandatoryReview() {
    RouteTeam team = new RouteTeam(0.15);
    var none = team.classifyRisk(signals());
    assertThat(none.score()).isZero();
    assertThat(none.needsSkeptic()).isFalse();
    assertThat(none.needsTool()).isFalse();

    List<RouteTeam.RiskSignals> mandatory =
        List.of(
            new RouteTeam.RiskSignals(true, false, false, false, false, false, false, false, false, false),
            new RouteTeam.RiskSignals(false, false, false, true, false, false, false, false, false, false),
            new RouteTeam.RiskSignals(false, false, false, false, false, true, false, false, false, false),
            new RouteTeam.RiskSignals(false, false, false, false, false, false, true, false, false, false),
            new RouteTeam.RiskSignals(false, false, false, false, false, false, false, true, false, false),
            new RouteTeam.RiskSignals(false, false, false, false, false, false, false, false, true, false));
    assertThat(mandatory)
        .allSatisfy(value -> assertThat(team.classifyRisk(value).needsSkeptic()).isTrue());

    var weighted =
        team.classifyRisk(
            new RouteTeam.RiskSignals(
                false, true, true, false, true, false, false, false, false, true));
    assertThat(weighted.reasons()).hasSize(3);
    assertThat(weighted.needsSkeptic()).isTrue();
    assertThat(weighted.needsTool()).isTrue();

    var saturated =
        team.classifyRisk(
            new RouteTeam.RiskSignals(true, true, true, true, true, true, true, true, true, true));
    assertThat(saturated.score()).isEqualTo(1.0d);
    assertThat(saturated.enteringGlobalFactGate()).isTrue();
  }

  @Test
  void independentReviewGateCoversOptionalAndRequiredRoles() {
    RouteTeam team = new RouteTeam(0.5);
    RoleAssignment prover = role(RouteRole.PROVER, "prover");
    RoleAssignment referee = role(RouteRole.REFEREE, "referee");
    RoleAssignment skeptic = role(RouteRole.SKEPTIC, "skeptic");
    RoleAssignment tool = role(RouteRole.TOOL_SPECIALIST, "tool");
    RiskAssessment risk = team.classifyRisk(signals());

    RouteTeamPlan optional =
        new RouteTeamPlan("route", prover, null, null, referee, risk, true, null);
    assertThat(team.review(optional, false, false, true).globalShareAllowed()).isTrue();

    RouteTeamPlan required =
        new RouteTeamPlan(
            "route", prover, skeptic, tool, referee, risk, true, List.of("initial"));
    assertThat(team.review(required, false, false, false).diagnostics()).hasSize(4);
    assertThat(team.review(required, true, true, true).globalShareAllowed()).isTrue();

    RouteTeamPlan local =
        new RouteTeamPlan("route", prover, null, null, referee, risk, false, List.of());
    assertThat(team.review(local, true, true, true).globalShareAllowed()).isFalse();
  }

  private static RouteTeam.RiskSignals signals() {
    return new RouteTeam.RiskSignals(
        false, false, false, false, false, false, false, false, false, false);
  }

  private static RoleAssignment role(RouteRole role, String id) {
    return new RoleAssignment("route", role, id, "test", false, "phase17");
  }
}
