package io.github.aililuola.mathproofmesh.provider;

@FunctionalInterface
public interface MockResponder {
  LLMResponse respond(ProviderRequest request);
}
