package io.github.aililuola.mathproofmesh.computation;

@FunctionalInterface
public interface ComputationProducer {
  ProducedComputation execute(ValidatedComputationRequest request);
}
