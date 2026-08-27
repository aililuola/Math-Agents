package io.github.aililuola.mathproofmesh.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProviderEndpointPolicyTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final StrictYamlConfigLoader LOADER = new StrictYamlConfigLoader();

  @Test
  void permitsAllowlistedHttpsEndpointResolvedToPublicAddress() throws Exception {
    ProviderEndpointPolicy policy =
        policy("allowed.example", address(93, 184, 216, 34));

    URI endpoint =
        policy.endpointForRequest(
            agent("openai_compatible", "https://allowed.example/v1"),
            null,
            false);

    assertEquals("https://allowed.example/v1", endpoint.toString());
  }

  @Test
  void rejectsNonHttpsAndDangerousSchemesInProduction() throws Exception {
    ProviderEndpointPolicy policy =
        policy("allowed.example", address(93, 184, 216, 34));

    assertThrows(
        ConfigValidationException.class,
        () ->
            policy.endpointForRequest(
                agent("openai_compatible", "http://allowed.example/v1"),
                null,
                false));
    assertThrows(
        ConfigValidationException.class,
        () ->
            policy.endpointForRequest(
                agent("openai_compatible", "file:///etc/passwd"),
                null,
                false));
  }

  @Test
  void rejectsHostsOutsideAdministratorAllowlist() throws Exception {
    ProviderEndpointPolicy policy =
        policy("allowed.example", address(93, 184, 216, 34));

    assertThrows(
        ConfigValidationException.class,
        () ->
            policy.endpointForRequest(
                agent("openai_compatible", "https://other.example/v1"),
                null,
                false));
  }

  @Test
  void rejectsLoopbackPrivateAndLinkLocalDnsAnswers() throws Exception {
    AgentConfig configured =
        agent("openai_compatible", "https://allowed.example/v1");

    assertThrows(
        ConfigValidationException.class,
        () -> policy("allowed.example", address(127, 0, 0, 1))
            .endpointForRequest(configured, null, false));
    assertThrows(
        ConfigValidationException.class,
        () -> policy("allowed.example", address(10, 0, 0, 7))
            .endpointForRequest(configured, null, false));
    assertThrows(
        ConfigValidationException.class,
        () -> policy("allowed.example", address(169, 254, 2, 3))
            .endpointForRequest(configured, null, false));
  }

  @Test
  void rejectsEveryUserBaseUrlOverride() throws Exception {
    ProviderEndpointPolicy policy =
        policy("allowed.example", address(93, 184, 216, 34));

    assertThrows(
        ConfigValidationException.class,
        () ->
            policy.endpointForRequest(
                agent("openai_compatible", "https://allowed.example/v1"),
                "https://allowed.example/v2",
                false));
  }

  @Test
  void rejectsCrossHostRedirectAndAllowsSameHostRedirect() throws Exception {
    ProviderEndpointPolicy policy =
        policy("allowed.example", address(93, 184, 216, 34));
    URI original = URI.create("https://allowed.example/v1");

    assertThrows(
        ConfigValidationException.class,
        () ->
            policy.validateRedirect(
                original,
                URI.create("https://other.example/v1"),
                "openai_compatible",
                false));
    assertEquals(
        URI.create("https://allowed.example/v2"),
        policy.validateRedirect(
            original,
            URI.create("/v2"),
            "openai_compatible",
            false));
  }

  @Test
  void developmentMockExceptionIsLimitedToLoopback() throws Exception {
    ProviderEndpointPolicy loopback =
        policy("unused.example", address(127, 0, 0, 1));
    ProviderEndpointPolicy publicAddress =
        policy("unused.example", address(93, 184, 216, 34));
    AgentConfig mock = agent("mock", "http://localhost:8080");

    assertEquals(
        URI.create("http://localhost:8080"),
        loopback.endpointForRequest(mock, null, true));
    assertThrows(
        ConfigValidationException.class,
        () -> publicAddress.endpointForRequest(mock, null, true));
  }

  private static ProviderEndpointPolicy policy(String host, InetAddress address) {
    return new ProviderEndpointPolicy(Set.of(host), ignored -> List.of(address));
  }

  private static InetAddress address(int first, int second, int third, int fourth)
      throws UnknownHostException {
    return InetAddress.getByAddress(
        new byte[] {(byte) first, (byte) second, (byte) third, (byte) fourth});
  }

  private static AgentConfig agent(String provider, String baseUrl) {
    ObjectNode node = JSON.createObjectNode();
    node.put("id", "endpoint-agent");
    node.put("provider", provider);
    node.put("model", "mock".equals(provider) ? "mock-model" : "provider-model");
    node.put("base_url", baseUrl);
    if (!"mock".equals(provider)) {
      node.put("api_key_env", "ENDPOINT_AGENT_KEY");
    }
    return LOADER.bindValue(node, AgentConfig.class);
  }
}
