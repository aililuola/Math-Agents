package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AgentConfig(
    @JsonProperty(value = "id", required = true) String id,
    @JsonProperty(value = "provider", required = true) String provider,
    @JsonProperty(value = "model", required = true) String model,
    @JsonProperty(value = "api_key_env") @ConfigNullable String apiKeyEnv,
    @JsonProperty(value = "api_key", access = JsonProperty.Access.WRITE_ONLY) @ConfigNullable SecretValue apiKey,
    @JsonProperty(value = "base_url") @ConfigNullable String baseUrl,
    @JsonProperty(value = "roles") List<String> roles,
    @JsonProperty(value = "specialties") List<String> specialties,
    @JsonProperty(value = "max_concurrency") Integer maxConcurrency,
    @JsonProperty(value = "requests_per_minute") @ConfigNullable Integer requestsPerMinute,
    @JsonProperty(value = "temperature") Double temperature,
    @JsonProperty(value = "max_output_tokens") Integer maxOutputTokens,
    @JsonProperty(value = "provider_max_output_tokens") Integer providerMaxOutputTokens,
    @JsonProperty(value = "timeout_seconds") Double timeoutSeconds,
    @JsonProperty(value = "trust_prior") Double trustPrior,
    @JsonProperty(value = "enabled") Boolean enabled,
    @JsonProperty(value = "pricing") PricingConfig pricing,
    @JsonProperty(value = "extra_headers") Map<String, String> extraHeaders,
    @JsonProperty(value = "mock_profile") @ConfigNullable String mockProfile,
    @JsonProperty(value = "thinking_enabled") Boolean thinkingEnabled,
    @JsonProperty(value = "reasoning_effort") @ConfigNullable String reasoningEffort,
    @JsonProperty(value = "streaming") Boolean streaming,
    @JsonProperty(value = "user_id") @ConfigNullable String userId
) implements ConfigModel {

  @JsonCreator
  public AgentConfig(String id, String provider, String model, String apiKeyEnv, SecretValue apiKey, String baseUrl, List<String> roles, List<String> specialties, Integer maxConcurrency, Integer requestsPerMinute, Double temperature, Integer maxOutputTokens, Integer providerMaxOutputTokens, Double timeoutSeconds, Double trustPrior, Boolean enabled, PricingConfig pricing, Map<String, String> extraHeaders, String mockProfile, Boolean thinkingEnabled, String reasoningEffort, Boolean streaming, String userId) {
    id = ConfigValidation.required("id", id);
    id = ConfigValidation.trim(id);
    provider = ConfigValidation.required("provider", provider);
    provider = ConfigValidation.trim(provider);
    ConfigValidation.oneOf("provider", provider, "openai_compatible", "deepseek", "anthropic", "gemini", "mock");
    model = ConfigValidation.required("model", model);
    model = ConfigValidation.trim(model);
    apiKeyEnv = ConfigValidation.trim(apiKeyEnv);
    baseUrl = ConfigValidation.trim(baseUrl);
    if (roles == null) {
      roles = List.of("general");
    }
    roles = ConfigValidation.trimStrings("roles", roles);
    ConfigValidation.itemsOneOf("roles", roles, "planner", "explorer", "summarizer", "structural_verifier", "detailed_verifier", "meta_reviewer", "synthesizer", "final_verifier", "experimenter", "route_prover", "route_skeptic", "tool_specialist", "route_referee", "bridge_prover", "conflict_resolver", "counterexample_hunter", "representation_switchboard", "analogy_agent", "construction_inventor", "invariant_hypothesis_agent", "reverse_goal_analyzer", "meta_strategist", "inspiration_referee", "general");
    if (specialties == null) {
      specialties = List.of();
    }
    specialties = ConfigValidation.trimStrings("specialties", specialties);
    if (maxConcurrency == null) {
      maxConcurrency = 1;
    }
    ConfigValidation.minimum("max_concurrency", maxConcurrency, 1);
    ConfigValidation.maximum("max_concurrency", maxConcurrency, 32);
    ConfigValidation.minimum("requests_per_minute", requestsPerMinute, 1);
    if (temperature == null) {
      temperature = 0.2d;
    }
    ConfigValidation.minimum("temperature", temperature, 0.0d);
    ConfigValidation.maximum("temperature", temperature, 2.0d);
    if (maxOutputTokens == null) {
      maxOutputTokens = 8192;
    }
    ConfigValidation.minimum("max_output_tokens", maxOutputTokens, 256);
    ConfigValidation.maximum("max_output_tokens", maxOutputTokens, 384000);
    if (providerMaxOutputTokens == null) {
      providerMaxOutputTokens = 384000;
    }
    ConfigValidation.minimum("provider_max_output_tokens", providerMaxOutputTokens, 256);
    ConfigValidation.maximum("provider_max_output_tokens", providerMaxOutputTokens, 384000);
    if (timeoutSeconds == null) {
      timeoutSeconds = 180.0d;
    }
    ConfigValidation.minimum("timeout_seconds", timeoutSeconds, 5.0d);
    ConfigValidation.maximum("timeout_seconds", timeoutSeconds, 3600.0d);
    if (trustPrior == null) {
      trustPrior = 0.5d;
    }
    ConfigValidation.minimum("trust_prior", trustPrior, 0.0d);
    ConfigValidation.maximum("trust_prior", trustPrior, 1.0d);
    if (enabled == null) {
      enabled = true;
    }
    if (pricing == null) {
      pricing = PricingConfig.defaults();
    }
    if (extraHeaders == null) {
      extraHeaders = Map.of();
    }
    extraHeaders = ConfigValidation.trimStringMap("extra_headers", extraHeaders);
    mockProfile = ConfigValidation.trim(mockProfile);
    if (thinkingEnabled == null) {
      thinkingEnabled = false;
    }
    reasoningEffort = ConfigValidation.trim(reasoningEffort);
    ConfigValidation.oneOf("reasoning_effort", reasoningEffort, "high", "max");
    if (streaming == null) {
      streaming = false;
    }
    userId = ConfigValidation.trim(userId);
    ConfigValidation.minimumLength("user_id", userId, 1);
    ConfigValidation.maximumLength("user_id", userId, 512);
    this.id = id;
    this.provider = provider;
    this.model = model;
    this.apiKeyEnv = apiKeyEnv;
    this.apiKey = apiKey;
    this.baseUrl = baseUrl;
    this.roles = roles;
    this.specialties = specialties;
    this.maxConcurrency = maxConcurrency;
    this.requestsPerMinute = requestsPerMinute;
    this.temperature = temperature;
    this.maxOutputTokens = maxOutputTokens;
    this.providerMaxOutputTokens = providerMaxOutputTokens;
    this.timeoutSeconds = timeoutSeconds;
    this.trustPrior = trustPrior;
    this.enabled = enabled;
    this.pricing = pricing;
    this.extraHeaders = extraHeaders;
    this.mockProfile = mockProfile;
    this.thinkingEnabled = thinkingEnabled;
    this.reasoningEffort = reasoningEffort;
    this.streaming = streaming;
    this.userId = userId;
    ConfigInvariants.validate(this);
  }

  @JsonProperty("roles")
  @Override
  public List<String> roles() {
    return roles == null ? null : List.copyOf(roles);
  }

  @JsonProperty("specialties")
  @Override
  public List<String> specialties() {
    return specialties == null ? null : List.copyOf(specialties);
  }

  @JsonProperty("extra_headers")
  @Override
  public Map<String, String> extraHeaders() {
    return extraHeaders == null ? null : Map.copyOf(extraHeaders);
  }


  public SecretValue resolveKey() {
    return resolveKey(System::getenv);
  }

  public SecretValue resolveKey(EnvironmentLookup environment) {
    java.util.Objects.requireNonNull(environment, "environment");
    if (apiKey != null) {
      return apiKey.copy();
    }
    if (apiKeyEnv != null && !apiKeyEnv.isBlank()) {
      String value = environment.lookup(apiKeyEnv);
      if (value != null && !value.isBlank()) {
        return SecretValue.of(value);
      }
      throw new ConfigValidationException(
          "missing API key environment variable for agent '" + id + "': " + apiKeyEnv);
    }
    if ("mock".equals(provider)) {
      return SecretValue.of("mock");
    }
    throw new ConfigValidationException("no API key configured for agent '" + id + "'");
  }

}
