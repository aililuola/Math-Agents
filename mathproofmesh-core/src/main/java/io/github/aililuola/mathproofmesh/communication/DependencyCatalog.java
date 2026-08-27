package io.github.aililuola.mathproofmesh.communication;

import java.util.List;

public interface DependencyCatalog {
  boolean exists(String dependencyId);

  boolean invalidated(String dependencyId);

  boolean wouldCreateCycle(String messageId, List<String> dependencyIds);

  static DependencyCatalog empty() {
    return new DependencyCatalog() {
      @Override
      public boolean exists(String dependencyId) {
        return false;
      }

      @Override
      public boolean invalidated(String dependencyId) {
        return false;
      }

      @Override
      public boolean wouldCreateCycle(String messageId, List<String> dependencyIds) {
        return false;
      }
    };
  }
}
