package io.github.aililuola.mathproofmesh.provider;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.config.AgentConfig;
import io.github.aililuola.mathproofmesh.config.EnvironmentLookup;
import io.github.aililuola.mathproofmesh.config.SecretValue;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Constructs the five supported providers while keeping live calls default-deny. */
public final class ProviderClientRegistry implements AutoCloseable {
  private static final long MINIMUM_RESPONSE_BYTES = 16L * 1024 * 1024;
  private static final long RESPONSE_OVERHEAD_BYTES = 1024L * 1024;
  private static final long SSE_BYTES_PER_OUTPUT_TOKEN = 512L;

  private final Map<String, LLMClient> clients;

  public ProviderClientRegistry(SystemConfig config) {
    this(config, Map.of(), ignored -> new JdkHttpTransport(), false);
  }

  public ProviderClientRegistry(
      SystemConfig config,
      Map<String, MockResponder> mockResponders,
      TransportFactory transportFactory,
      boolean enableLiveCalls) {
    this(
        config,
        mockResponders,
        transportFactory,
        enableLiveCalls,
        System::getenv);
  }

  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification =
          "Construction closes every already-created client and then preserves the "
              + "original typed configuration/provider failure.")
  public ProviderClientRegistry(
      SystemConfig config,
      Map<String, MockResponder> mockResponders,
      TransportFactory transportFactory,
      boolean enableLiveCalls,
      EnvironmentLookup credentialLookup) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(mockResponders, "mockResponders");
    Objects.requireNonNull(transportFactory, "transportFactory");
    Objects.requireNonNull(credentialLookup, "credentialLookup");
    LiveProviderPolicy policy =
        enableLiveCalls
            ? LiveProviderPolicy.explicitlyEnabled()
            : LiveProviderPolicy.disabled();
    ProviderLimits limits =
        new ProviderLimits(
            responseByteLimit(config),
            duration(config.runtime().streamFirstChunkTimeoutSeconds()),
            duration(config.runtime().streamIdleTimeoutSeconds()));
    Map<String, LLMClient> created = new LinkedHashMap<>();
    try {
      for (AgentConfig agent : config.agents()) {
        if (!agent.enabled()) {
          continue;
        }
        LLMClient client =
            create(
                agent,
                mockResponders.get(agent.id()),
                transportFactory.create(agent),
                limits,
                policy,
                credentialLookup);
        if (created.putIfAbsent(agent.id(), client) != null) {
          client.close();
          throw new IllegalArgumentException("duplicate agent id: " + agent.id());
        }
      }
    } catch (RuntimeException exception) {
      created.values().forEach(LLMClient::close);
      throw exception;
    }
    clients = Map.copyOf(created);
  }

  static long responseByteLimit(SystemConfig config) {
    long maximumOutputTokens =
        config.agents().stream()
            .filter(AgentConfig::enabled)
            .mapToLong(
                agent ->
                    Math.min(agent.maxOutputTokens(), agent.providerMaxOutputTokens()))
            .max()
            .orElse(0L);
    long configuredLimit =
        Math.addExact(
            RESPONSE_OVERHEAD_BYTES,
            Math.multiplyExact(maximumOutputTokens, SSE_BYTES_PER_OUTPUT_TOKEN));
    return Math.max(MINIMUM_RESPONSE_BYTES, configuredLimit);
  }

  public LLMClient get(String agentId) {
    LLMClient client = clients.get(agentId);
    if (client == null) {
      throw new IllegalArgumentException("unknown provider client: " + agentId);
    }
    return client;
  }

  public Map<String, LLMClient> clients() {
    return clients;
  }

  @Override
  public void close() {
    clients.values().forEach(LLMClient::close);
  }

  private static LLMClient create(
      AgentConfig agent,
      MockResponder responder,
      HttpTransport transport,
      ProviderLimits limits,
      LiveProviderPolicy policy,
      EnvironmentLookup credentialLookup) {
    if ("mock".equals(agent.provider())) {
      return new MockProvider(agent.model(), responder);
    }
    SecretValue key = agent.resolveKey(credentialLookup);
    try {
      Duration timeout = duration(agent.timeoutSeconds());
      URI baseUri = URI.create(baseUrl(agent));
      return switch (agent.provider()) {
        case "deepseek" ->
            new DeepSeekProvider(
                key,
                agent.model(),
                baseUri,
                timeout,
                agent.extraHeaders(),
                transport,
                limits,
                policy);
        case "anthropic" ->
            new AnthropicProvider(
                key,
                agent.model(),
                baseUri,
                timeout,
                agent.extraHeaders(),
                transport,
                limits,
                policy);
        case "gemini" ->
            new GeminiProvider(
                key,
                agent.model(),
                baseUri,
                timeout,
                agent.extraHeaders(),
                transport,
                limits,
                policy);
        case "openai_compatible" ->
            new OpenAiCompatibleProvider(
                key,
                agent.model(),
                baseUri,
                timeout,
                agent.extraHeaders(),
                transport,
                limits,
                policy);
        default -> throw new IllegalArgumentException(
            "unsupported provider: " + agent.provider());
      };
    } finally {
      key.close();
    }
  }

  private static String baseUrl(AgentConfig agent) {
    if (agent.baseUrl() != null) {
      return agent.baseUrl();
    }
    return switch (agent.provider()) {
      case "deepseek" -> "https://api.deepseek.com";
      case "anthropic" -> "https://api.anthropic.com/v1";
      case "gemini" -> "https://generativelanguage.googleapis.com/v1beta";
      case "openai_compatible" -> "https://api.openai.com/v1";
      default -> throw new IllegalArgumentException(
          "unsupported provider: " + agent.provider());
    };
  }

  private static Duration duration(double seconds) {
    return Duration.ofMillis(Math.max(1L, Math.round(seconds * 1000.0d)));
  }

  @FunctionalInterface
  public interface TransportFactory {
    HttpTransport create(AgentConfig agent);
  }
}
