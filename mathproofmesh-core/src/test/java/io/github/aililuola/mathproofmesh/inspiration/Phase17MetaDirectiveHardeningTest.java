package io.github.aililuola.mathproofmesh.inspiration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.aililuola.mathproofmesh.contract.InspirationMechanism;
import io.github.aililuola.mathproofmesh.contract.MetaDirective;
import io.github.aililuola.mathproofmesh.contract.MetaDirectiveAction;
import io.github.aililuola.mathproofmesh.contract.MetaStrategyDecision;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Phase17MetaDirectiveHardeningTest {

  @Test
  void decisionsMapEveryActionAndMandatoryCondition() {
    MetaDirectiveController controller =
        new MetaDirectiveController(InspirationPolicy.defaults(InspirationPolicy.Mode.ACTIVE), null);
    InspirationSnapshot snapshot = snapshot(3, 10, 2, false);
    Map<String, MetaDirectiveAction> expected = new LinkedHashMap<>();
    expected.put("continue_current_mechanism", MetaDirectiveAction.CONTINUE);
    expected.put("switch_representation", MetaDirectiveAction.SWITCH_REPRESENTATION);
    expected.put("surprise_exploration", MetaDirectiveAction.ALLOCATE_SURPRISE_BUDGET);
    expected.put("cooldown_route", MetaDirectiveAction.COOLDOWN_ROUTE);
    expected.put("abandon_route", MetaDirectiveAction.ABANDON_ROUTE);
    expected.put("merge_route", MetaDirectiveAction.MERGE_ROUTES);
    expected.put("local_repair", MetaDirectiveAction.REPAIR);
    expected.put("invent_auxiliary_construction", MetaDirectiveAction.REPAIR);
    expected.put("rewrite_plan", MetaDirectiveAction.REWRITE_PLAN);
    expected.put("search_analogy", MetaDirectiveAction.REWRITE_PLAN);
    expected.put("split_route", MetaDirectiveAction.REWRITE_PLAN);
    int index = 0;
    for (var entry : expected.entrySet()) {
      MetaDirective directive =
          controller.fromDecision(
              decision(entry.getKey(), "d-" + index++, List.of(), 1, 3, null), snapshot);
      assertThat(directive.action()).isEqualTo(entry.getValue());
      assertThat(directive.expiresRound()).isEqualTo(5);
      assertThat(directive.mandatory())
          .isEqualTo(
              entry.getValue() == MetaDirectiveAction.COOLDOWN_ROUTE
                  || entry.getValue() == MetaDirectiveAction.ABANDON_ROUTE);
    }
    assertThat(
            controller
                .fromDecision(
                    decision("continue_current_mechanism", "mandatory", List.of(), 0, 3, null),
                    snapshot(3, 10, 2, true))
                .mandatory())
        .isTrue();
  }

  @Test
  void auditReportsEveryIndependentControlFailure() {
    MetaDirectiveController controller =
        new MetaDirectiveController(
            InspirationPolicy.defaults(InspirationPolicy.Mode.ACTIVE),
            List.of(route("r1", true), route("r2", true)));
    InspirationSnapshot snapshot = snapshot(5, 5, 2, false);
    MetaDirective invalid =
        directive(
            MetaDirectiveAction.MERGE_ROUTES,
            "invalid",
            6,
            4,
            6,
            List.of("unknown"),
            Map.of(),
            null);
    var audit = controller.audit(invalid, snapshot);
    assertThat(audit.accepted()).isFalse();
    assertThat(audit.reason())
        .contains(
            "future round",
            "expired",
            "unknown",
            "evidence is incomplete",
            "protected finalization budget",
            "merge requires two routes");

    var noTarget =
        controller.audit(
            directive(
                MetaDirectiveAction.COOLDOWN_ROUTE,
                "no-target",
                1,
                8,
                5,
                List.of(),
                evidence(),
                null),
            snapshot);
    assertThat(noTarget.reason()).contains("no target");

    var valid =
        controller.audit(
            directive(
                MetaDirectiveAction.MERGE_ROUTES,
                "valid",
                1,
                8,
                5,
                List.of("r1", "r2"),
                evidence(),
                null),
            snapshot);
    assertThat(valid.accepted()).isTrue();
    assertThat(valid.budgetSafe()).isTrue();
    assertThat(valid.evidenceComplete()).isTrue();
    assertThat(valid.targetsValid()).isTrue();
  }

  @Test
  void executeCoversRejectionShadowNoopRouteControlAndTaskMechanisms() {
    InspirationPolicy active = InspirationPolicy.defaults(InspirationPolicy.Mode.ACTIVE);
    InspirationSnapshot snapshot = snapshot(2, 10, 2, false);
    MetaDirectiveController controller =
        new MetaDirectiveController(
            active, List.of(route("r1", true), route("r2", false)));

    MetaDirective rejected =
        directive(
            MetaDirectiveAction.REWRITE_PLAN,
            "rejected",
            1,
            4,
            2,
            List.of("missing"),
            evidence(),
            null);
    var rejectedResult =
        controller.execute(rejected, controller.audit(rejected, snapshot), snapshot, "trigger");
    assertThat(rejectedResult.execution().status()).isEqualTo("rejected");
    assertThat(rejectedResult.businessMutation()).isFalse();
    assertThat(controller.execute(rejected, controller.audit(rejected, snapshot), snapshot, "x"))
        .isSameAs(rejectedResult);

    MetaDirective continueDirective =
        directive(
            MetaDirectiveAction.CONTINUE,
            "continue",
            0,
            4,
            2,
            List.of(),
            evidence(),
            null);
    assertThat(
            controller
                .execute(
                    continueDirective,
                    controller.audit(continueDirective, snapshot),
                    snapshot,
                    "trigger")
                .execution()
                .status())
        .isEqualTo("noop");

    MetaDirective cooldown =
        directive(
            MetaDirectiveAction.COOLDOWN_ROUTE,
            "cool",
            0,
            4,
            2,
            List.of("r1", "r2"),
            evidence(),
            null);
    var cooled =
        controller.execute(cooldown, controller.audit(cooldown, snapshot), snapshot, "trigger");
    assertThat(cooled.businessMutation()).isTrue();
    assertThat(cooled.execution().affectedRouteIds()).containsExactly("r1");
    assertThat(controller.route("r1").cooldownUntilRound()).isEqualTo(4);

    MetaDirective abandon =
        directive(
            MetaDirectiveAction.ABANDON_ROUTE,
            "abandon",
            0,
            4,
            2,
            List.of("r2"),
            evidence(),
            null);
    var abandoned =
        controller.execute(abandon, controller.audit(abandon, snapshot), snapshot, "trigger");
    assertThat(abandoned.execution().status()).isEqualTo("noop");
    assertThat(abandoned.businessMutation()).isFalse();

    assertTaskMechanism(
        controller, snapshot, MetaDirectiveAction.SWITCH_REPRESENTATION, null,
        InspirationMechanism.REPRESENTATION_SWITCH);
    assertTaskMechanism(
        controller, snapshot, MetaDirectiveAction.ALLOCATE_SURPRISE_BUDGET, null,
        InspirationMechanism.SURPRISE_EXPLORATION);
    assertTaskMechanism(
        controller, snapshot, MetaDirectiveAction.REWRITE_PLAN, InspirationMechanism.STRUCTURAL_ANALOGY,
        InspirationMechanism.STRUCTURAL_ANALOGY);
    assertTaskMechanism(
        controller, snapshot, MetaDirectiveAction.REPAIR, InspirationMechanism.META_REPLAN,
        InspirationMechanism.AUXILIARY_CONSTRUCTION);
    assertTaskMechanism(
        controller, snapshot, MetaDirectiveAction.REWRITE_PLAN, InspirationMechanism.META_REPLAN,
        InspirationMechanism.REVERSE_GOAL_ANALYSIS);
    assertThat(controller.route("absent")).isNull();

    MetaDirectiveController shadow =
        new MetaDirectiveController(active.withMode(InspirationPolicy.Mode.SHADOW), List.of());
    MetaDirective shadowDirective =
        directive(
            MetaDirectiveAction.REWRITE_PLAN,
            "shadow",
            1,
            4,
            2,
            List.of(),
            evidence(),
            null);
    assertThat(
            shadow
                .execute(
                    shadowDirective,
                    shadow.audit(shadowDirective, snapshot),
                    snapshot,
                    "trigger")
                .execution()
                .status())
        .isEqualTo("noop");

    assertThatThrownBy(() -> new MetaDirectiveController.RouteControl(" ", true, 0, false, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new MetaDirectiveController.RouteControl(" r ", true, 0, false, null).reason())
        .isEmpty();
  }

  private static void assertTaskMechanism(
      MetaDirectiveController controller,
      InspirationSnapshot snapshot,
      MetaDirectiveAction action,
      InspirationMechanism selected,
      InspirationMechanism expected) {
    String id = "task-" + action.value() + "-" + (selected == null ? "none" : selected.value());
    MetaDirective directive =
        directive(action, id, 1, 4, 2, List.of(), evidence(), selected);
    var result =
        controller.execute(directive, controller.audit(directive, snapshot), snapshot, "trigger");
    assertThat(result.businessMutation()).isTrue();
    assertThat(result.generatedTasks())
        .singleElement()
        .satisfies(task -> assertThat(task.mechanism()).isEqualTo(expected));
  }

  private static MetaStrategyDecision decision(
      String action,
      String id,
      List<String> routes,
      int calls,
      int round,
      InspirationMechanism mechanism) {
    return new MetaStrategyDecision(
        action, routes, id, calls, evidence(), "observable reason", round, mechanism);
  }

  private static MetaDirective directive(
      MetaDirectiveAction action,
      String id,
      int calls,
      int expires,
      int round,
      List<String> routes,
      Map<String, JsonNode> evidence,
      InspirationMechanism mechanism) {
    return new MetaDirective(
        action,
        id,
        calls,
        expires,
        false,
        evidence,
        "observable reason",
        round,
        routes,
        mechanism,
        "decision-" + id);
  }

  private static Map<String, JsonNode> evidence() {
    return Map.of("metric", TextNode.valueOf("observed"));
  }

  private static MetaDirectiveController.RouteControl route(String id, boolean active) {
    return new MetaDirectiveController.RouteControl(id, active, -1, false, "");
  }

  private static InspirationSnapshot snapshot(
      int round, int calls, int reserve, boolean finalRepairFailed) {
    return new InspirationSnapshot(
        round,
        "hash",
        "domain",
        List.of("r1", "r2"),
        List.of(),
        Map.of(),
        0,
        Map.of(),
        0.0d,
        List.of(),
        List.of(),
        0.0d,
        List.of(),
        calls,
        reserve,
        0,
        4,
        List.of("o1"),
        Map.of("o1", "subgoal"),
        finalRepairFailed,
        false);
  }
}
