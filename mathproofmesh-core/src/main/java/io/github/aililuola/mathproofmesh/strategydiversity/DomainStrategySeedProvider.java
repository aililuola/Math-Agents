package io.github.aililuola.mathproofmesh.strategydiversity;

import io.github.aililuola.mathproofmesh.contract.ProblemContract;
import java.util.List;

public interface DomainStrategySeedProvider {
  boolean supports(ProblemContract problem);

  List<StrategySeed> seeds(ProblemContract problem);

  static DomainStrategySeedProvider empty() {
    return new DomainStrategySeedProvider() {
      @Override
      public boolean supports(ProblemContract problem) {
        return false;
      }

      @Override
      public List<StrategySeed> seeds(ProblemContract problem) {
        return List.of();
      }
    };
  }
}
