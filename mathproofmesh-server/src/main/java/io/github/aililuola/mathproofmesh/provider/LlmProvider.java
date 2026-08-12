package io.github.aililuola.mathproofmesh.provider;

import java.util.List;

public interface LlmProvider extends AutoCloseable {
  String providerId();

  LLMResponse complete(ProviderRequest request);

  default List<String> listModels() {
    return List.of();
  }

  @Override
  default void close() {
    // Most JDK HttpClient-backed providers do not own a closeable client.
  }
}
