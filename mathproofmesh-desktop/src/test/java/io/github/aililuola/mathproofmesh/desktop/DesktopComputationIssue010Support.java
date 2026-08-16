package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.computation.ComputationBroker;
import io.github.aililuola.mathproofmesh.computation.ComputationCache;
import io.github.aililuola.mathproofmesh.computation.ComputationContext;
import io.github.aililuola.mathproofmesh.computation.ComputationExecutionContext;
import io.github.aililuola.mathproofmesh.computation.ComputationExecutionOutcome;
import io.github.aililuola.mathproofmesh.computation.ComputationHandlerRegistry;
import io.github.aililuola.mathproofmesh.computation.ComputationLimits;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import io.github.aililuola.mathproofmesh.persistence.ArtifactStore;
import java.nio.file.Path;

final class DesktopComputationIssue010Support {
  private DesktopComputationIssue010Support() {}

  static ComputationBroker broker(
      String runId, Path artifactDirectory, ComputationCache cache) {
    return new ComputationBroker(
        runId,
        ComputationLimits.defaultsEnabled(),
        ComputationHandlerRegistry.javaOnly(),
        cache,
        new ArtifactStoreComputationArtifactStore(
            new ArtifactStore(artifactDirectory, runId)));
  }

  static ComputationExecutionOutcome run(
      ComputationBroker broker, ExperimentSpec spec, String routeId, int round) {
    var prepared = broker.decide(spec, ComputationContext.initial(routeId, 8));
    broker.runExperiment(
        prepared.spec(),
        prepared.decision(),
        null,
        new ComputationExecutionContext(
            "p".repeat(64),
            "g".repeat(64),
            routeId,
            "",
            "",
            "obligation-" + routeId,
            "canonical-" + routeId,
            round,
            null));
    return broker.executionService().lastOutcome(spec.experimentId()).orElseThrow();
  }

  static ExperimentSpec graphCounterexample(String id, int variant) {
    return ComputationIssue010BlackBoxFixtures.spec(
        id,
        ComputationMethod.GRAPH_CERTIFICATE,
        "{\"property\":\"connected\",\"graph\":{\"directed\":false,"
            + "\"nodes\":[\"a"
            + variant
            + "\",\"b"
            + variant
            + "\",\"c"
            + variant
            + "\"],\"edges\":[[\"a"
            + variant
            + "\",\"b"
            + variant
            + "\"]]},\"certificate\":{}}");
  }

  static ExperimentSpec linearAlgebra(String id, int variant) {
    return ComputationIssue010BlackBoxFixtures.spec(
        id,
        ComputationMethod.EXACT_LINEAR_ALGEBRA,
        "{\"operation\":\"rank\",\"matrix\":[[\""
            + (variant + 1)
            + "\",\"0\"],[\"0\",\"1\"]]}");
  }

  static ExperimentSpec finiteMap(String id) {
    return ComputationIssue010BlackBoxFixtures.spec(
        id,
        ComputationMethod.FINITE_SET_MAP_CHECK,
        "{\"operation\":\"bijective\",\"domain\":[\"a\",\"b\"],"
            + "\"codomain\":[\"x\",\"y\"],\"mapping\":{\"a\":\"x\",\"b\":\"y\"}}");
  }

  static ExperimentSpec hypergraph(String id) {
    return ComputationIssue010BlackBoxFixtures.spec(
        id,
        ComputationMethod.HYPERGRAPH_TRANSVERSAL,
        "{\"operation\":\"is_minimal_hitting_set\",\"vertices\":[\"a\",\"b\",\"c\"],"
            + "\"edges\":[[\"a\",\"b\"],[\"b\",\"c\"]],\"candidate\":[\"b\"]}");
  }

  static ExperimentSpec boundedObservation(String id, int variant) {
    return ComputationIssue010BlackBoxFixtures.spec(
        id,
        ComputationMethod.CANDIDATE_PERIOD_CHECK,
        "{\"values\":["
            + variant
            + ','
            + (variant + 1)
            + ','
            + variant
            + ','
            + (variant + 1)
            + "],\"candidate_period\":2}");
  }
}
