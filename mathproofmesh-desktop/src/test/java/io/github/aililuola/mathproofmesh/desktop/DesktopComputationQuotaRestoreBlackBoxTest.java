package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.computation.ComputationBroker;
import io.github.aililuola.mathproofmesh.computation.ComputationHandlerRegistry;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import org.junit.jupiter.api.Test;

class DesktopComputationQuotaRestoreBlackBoxTest {
  @Test
  void computationQuotaSurvivesAProcessRestore() {
    ComputationBroker before =
        ComputationIssue010BlackBoxFixtures.broker(
            "quota-restore", ComputationHandlerRegistry.javaOnly());
    ComputationIssue010BlackBoxFixtures.run(
        before, ComputationIssue010BlackBoxFixtures.graphSpec("quota-1"));
    ComputationIssue010BlackBoxFixtures.run(
        before,
        ComputationIssue010BlackBoxFixtures.spec(
            "quota-2",
            ComputationMethod.GRAPH_CERTIFICATE,
            """
            {
              "graph": {
                "nodes": ["u", "v", "w"],
                "edges": [["u", "v"], ["v", "w"]],
                "directed": false
              },
              "property": "connected",
              "certificate": {}
            }
            """));
    int beforeRestore = before.ledger().usage("route-010").experiments();

    ComputationBroker after =
        ComputationIssue010BlackBoxFixtures.broker(
            "quota-restore", ComputationHandlerRegistry.javaOnly());
    after.restore(before.snapshot());
    int afterRestore = after.ledger().usage("route-010").experiments();

    System.out.println("EXPERIMENTS_BEFORE_RESTORE=" + beforeRestore);
    System.out.println("EXPERIMENTS_AFTER_RESTORE=" + afterRestore);
    System.out.println("EXPECTED_AFTER_RESTORE=2");
    assertThat(beforeRestore).isEqualTo(2);
    assertThat(afterRestore).isEqualTo(2);
  }
}
