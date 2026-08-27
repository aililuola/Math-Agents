package io.github.aililuola.mathproofmesh.provider;

/**
 * Compatibility name for the Python {@code LLMClient} boundary.
 *
 * <p>New Java code should depend on {@link LlmProvider}; provider adapters
 * implement both names through this interface.
 */
public interface LLMClient extends LlmProvider {}
