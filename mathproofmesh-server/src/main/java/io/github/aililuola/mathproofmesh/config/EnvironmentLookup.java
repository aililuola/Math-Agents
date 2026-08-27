package io.github.aililuola.mathproofmesh.config;

@FunctionalInterface
public interface EnvironmentLookup {
  String lookup(String name);
}
