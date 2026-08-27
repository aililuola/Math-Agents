package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.ExperimentProgram;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;

@FunctionalInterface
public interface ComputationHandler {
  HandlerEvidence execute(ExperimentSpec spec, ExperimentProgram program);
}
