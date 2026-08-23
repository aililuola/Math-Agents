package io.github.aililuola.mathproofmesh.desktop.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.provider.HttpTransport;
import io.github.aililuola.mathproofmesh.provider.HttpTransportRequest;
import io.github.aililuola.mathproofmesh.provider.HttpTransportResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Network allowlist and exact-root binding applied immediately before a benchmark HTTP call. */
public final class OlympiadPromptTransportGuard implements HttpTransport {
  private static final int MAXIMUM_REQUEST_BYTES = 8 * 1024 * 1024;
  private static final String CONTEXT_MARKER = "SANITIZED CONTEXT:\n";
  private static final String CONTEXT_END_MARKER = "\n\nOUTPUT LANGUAGE:";
  private static final Pattern STAGE = Pattern.compile("\\[STAGE:([a-zA-Z0-9_-]+)]");

  private final HttpTransport delegate;
  private final OlympiadProblemCatalog.ProblemPrompt expected;
  private final String keyLabel;
  private final Audit audit;

  public OlympiadPromptTransportGuard(
      HttpTransport delegate,
      OlympiadProblemCatalog.ProblemPrompt expected,
      String keyLabel,
      Audit audit) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.expected = Objects.requireNonNull(expected, "expected");
    this.keyLabel = requireKeyLabel(keyLabel);
    this.audit = Objects.requireNonNull(audit, "audit");
    if (!sameHash(expected.sha256(), audit.expectedProblemHash())) {
      throw new IllegalArgumentException("prompt audit is bound to another canonical problem");
    }
  }

  @Override
  public HttpTransportResponse send(HttpTransportRequest request)
      throws IOException, InterruptedException {
    Objects.requireNonNull(request, "request");
    validateEndpoint(request.uri(), request.method());
    byte[] bytes = request.body();
    if (bytes.length == 0 || bytes.length > MAXIMUM_REQUEST_BYTES) {
      throw new IllegalStateException("benchmark provider request size is outside the allowlist");
    }
    JsonNode body = ContractObjectMapper.parseTree(new String(bytes, StandardCharsets.UTF_8));
    Payload payload = payload(body);
    OlympiadPromptPolicy.validateNoForbiddenMetadata(payload.combinedText());
    String stage = stage(payload.userText());
    boolean repair = stage.endsWith("_json_repair");
    String actualProblemHash;
    if (repair) {
      if (!audit.canonicalRequestBound()) {
        throw new IllegalStateException(
            "benchmark JSON repair cannot precede a canonical problem request");
      }
      actualProblemHash = expected.sha256();
    } else {
      String actualProblem = immutableProblem(payload.userText());
      actualProblemHash =
          OlympiadProblemCatalog.sha256(OlympiadProblemCatalog.normalize(actualProblem));
      if (!sameHash(expected.sha256(), actualProblemHash)
          || !expected.text().equals(OlympiadProblemCatalog.normalize(actualProblem))) {
        throw new IllegalStateException("provider request is bound to a different root goal");
      }
      OlympiadPromptPolicy.validateProviderPayload(
          payload.combinedText(), expected, !audit.canonicalRequestBound());
      audit.bindCanonicalRequest();
    }

    try {
      HttpTransportResponse response = delegate.send(request);
      audit.record(
          new RequestEvent(
              keyLabel,
              stage,
              request.uri().getPath(),
              actualProblemHash,
              repair,
              response.statusCode(),
              "RESPONSE"));
      return response;
    } catch (IOException exception) {
      audit.record(
          new RequestEvent(
              keyLabel,
              stage,
              request.uri().getPath(),
              actualProblemHash,
              repair,
              0,
              "IO_ERROR"));
      throw exception;
    } catch (InterruptedException exception) {
      audit.record(
          new RequestEvent(
              keyLabel,
              stage,
              request.uri().getPath(),
              actualProblemHash,
              repair,
              0,
              "INTERRUPTED"));
      throw exception;
    }
  }

  @Override
  public boolean reachesNetwork() {
    return delegate.reachesNetwork();
  }

  private static void validateEndpoint(URI uri, String method) {
    boolean allowedPath =
        "/chat/completions".equals(uri.getPath())
            || "/v1/chat/completions".equals(uri.getPath());
    if (!"POST".equals(method)
        || !asciiEqualsIgnoreCase("https", uri.getScheme())
        || !asciiEqualsIgnoreCase("api.deepseek.com", uri.getHost())
        || (uri.getPort() != -1 && uri.getPort() != 443)
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !allowedPath) {
      throw new IllegalStateException("benchmark network destination is not allowlisted");
    }
  }

  private static Payload payload(JsonNode body) {
    JsonNode messages = body.path("messages");
    if (!body.isObject() || !messages.isArray() || messages.isEmpty()) {
      throw new IllegalStateException("benchmark provider request has no messages");
    }
    List<String> content = new ArrayList<>();
    String user = "";
    for (JsonNode message : messages) {
      String role = message.path("role").asText("");
      String text = messageText(message.path("content"));
      content.add(text);
      if ("user".equals(role)) {
        user = text;
      }
    }
    if (user.isBlank()) {
      throw new IllegalStateException("benchmark provider request has no user prompt");
    }
    return new Payload(String.join("\n", content), user);
  }

  private static String messageText(JsonNode value) {
    if (value.isTextual()) {
      return value.textValue();
    }
    if (value.isArray()) {
      StringBuilder text = new StringBuilder();
      for (JsonNode part : value) {
        if (part.isTextual()) {
          text.append(part.textValue());
        } else if (part.path("text").isTextual()) {
          text.append(part.path("text").textValue());
        }
      }
      return text.toString();
    }
    throw new IllegalStateException("benchmark provider message content is not textual");
  }

  private static String immutableProblem(String user) {
    int start = user.indexOf(CONTEXT_MARKER);
    if (start < 0) {
      throw new IllegalStateException("benchmark provider prompt has no sanitized context");
    }
    start += CONTEXT_MARKER.length();
    int end = user.indexOf(CONTEXT_END_MARKER, start);
    if (end < 0) {
      throw new IllegalStateException("benchmark provider prompt has no context terminator");
    }
    JsonNode context = ContractObjectMapper.parseTree(user.substring(start, end));
    JsonNode immutable = context.path("immutable_problem");
    if (immutable.isTextual()) {
      return required(immutable.textValue(), "immutable problem");
    }
    if (immutable.isObject()) {
      for (String field :
          List.of("exact_statement", "canonical_statement", "original_statement")) {
        String statement = immutable.path(field).asText("").strip();
        if (!statement.isEmpty()) {
          return statement;
        }
      }
    }
    throw new IllegalStateException("benchmark provider prompt has no exact root statement");
  }

  private static String stage(String user) {
    Matcher matcher = STAGE.matcher(user);
    if (!matcher.find()) {
      throw new IllegalStateException("benchmark provider prompt has no stage binding");
    }
    return matcher.group(1).toLowerCase(Locale.ROOT);
  }

  private static String requireKeyLabel(String value) {
    String label = required(value, "keyLabel");
    if (!OlympiadBenchmarkPlan.KEY_LABELS.contains(label)) {
      throw new IllegalArgumentException("unknown benchmark key label");
    }
    return label;
  }

  private static String required(String value, String field) {
    String normalized = Objects.requireNonNull(value, field).strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return normalized;
  }

  private static boolean sameHash(String left, String right) {
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.US_ASCII),
        right.getBytes(StandardCharsets.US_ASCII));
  }

  private static boolean asciiEqualsIgnoreCase(String expected, String actual) {
    if (actual == null || expected.length() != actual.length()) {
      return false;
    }
    for (int index = 0; index < expected.length(); index++) {
      char left = expected.charAt(index);
      char right = actual.charAt(index);
      if (right >= 'A' && right <= 'Z') {
        right = (char) (right + ('a' - 'A'));
      }
      if (left != right) {
        return false;
      }
    }
    return true;
  }

  private record Payload(String combinedText, String userText) {}

  public record RequestEvent(
      String keyLabel,
      String stage,
      String endpointPath,
      String actualProblemHash,
      boolean repair,
      int statusCode,
      String outcome) {
    public RequestEvent {
      keyLabel = requireKeyLabel(keyLabel);
      stage = required(stage, "stage");
      endpointPath = required(endpointPath, "endpointPath");
      actualProblemHash = required(actualProblemHash, "actualProblemHash");
      if (!actualProblemHash.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("actualProblemHash must be SHA-256");
      }
      if (statusCode < 0 || statusCode > 999) {
        throw new IllegalArgumentException("statusCode is outside the supported range");
      }
      outcome = required(outcome, "outcome");
    }
  }

  public static final class Audit {
    private final String expectedProblemHash;
    private final AtomicBoolean canonicalRequestBound = new AtomicBoolean();
    private final CopyOnWriteArrayList<RequestEvent> requests = new CopyOnWriteArrayList<>();

    public Audit(String expectedProblemHash) {
      this.expectedProblemHash = required(expectedProblemHash, "expectedProblemHash");
      if (!this.expectedProblemHash.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("expectedProblemHash must be SHA-256");
      }
    }

    public String expectedProblemHash() {
      return expectedProblemHash;
    }

    public boolean canonicalRequestBound() {
      return canonicalRequestBound.get();
    }

    public List<RequestEvent> requests() {
      return List.copyOf(requests);
    }

    private void bindCanonicalRequest() {
      canonicalRequestBound.set(true);
    }

    private void record(RequestEvent event) {
      requests.add(Objects.requireNonNull(event, "event"));
    }
  }
}
