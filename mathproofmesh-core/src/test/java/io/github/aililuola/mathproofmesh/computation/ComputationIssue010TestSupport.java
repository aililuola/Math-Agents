package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ExperimentResult;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;

final class ComputationIssue010TestSupport {
  private ComputationIssue010TestSupport() {}

  static ComputationCapabilityRegistry registry() {
    return ComputationHandlerRegistry.javaOnly().capabilityRegistry();
  }

  static ComputationCapabilityDescriptor descriptor(ComputationMethod method) {
    return registry().capability(method).descriptor();
  }

  static ComputationExecutionOutcome run(
      ComputationBroker broker, ExperimentSpec spec) {
    ExperimentResult result = ComputationFixtures.run(broker, spec);
    return broker.executionService().lastOutcome(result.experimentId()).orElseThrow();
  }

  static ExperimentSpec linearAlgebraSpec() {
    return ComputationFixtures.spec(
        ComputationMethod.EXACT_LINEAR_ALGEBRA,
        "{\"operation\":\"rank\",\"matrix\":[[\"1\",\"2\"],[\"2\",\"4\"]]}");
  }

  static ExperimentSpec finiteMapSpec() {
    return ComputationFixtures.spec(
        ComputationMethod.FINITE_SET_MAP_CHECK,
        "{\"operation\":\"bijective\",\"domain\":[\"a\",\"b\"],"
            + "\"codomain\":[\"x\",\"y\"],\"mapping\":{\"a\":\"x\",\"b\":\"y\"}}");
  }

  static ExperimentSpec hypergraphSpec() {
    return ComputationFixtures.spec(
        ComputationMethod.HYPERGRAPH_TRANSVERSAL,
        "{\"operation\":\"is_minimal_hitting_set\",\"vertices\":[\"a\",\"b\",\"c\"],"
            + "\"edges\":[[\"a\",\"b\"],[\"b\",\"c\"]],\"candidate\":[\"b\"]}");
  }

  static ExperimentSpec graphCounterexampleSpec() {
    return ComputationFixtures.spec(
        ComputationMethod.GRAPH_CERTIFICATE,
        "{\"property\":\"connected\",\"graph\":{\"directed\":false,"
            + "\"nodes\":[\"a\",\"b\",\"c\"],\"edges\":[[\"a\",\"b\"]]}}");
  }

  static ExperimentSpec boundedObservationSpec() {
    return ComputationFixtures.spec(
        ComputationMethod.CANDIDATE_PERIOD_CHECK,
        "{\"values\":[1,2,1,2],\"candidate_period\":2}");
  }
}
