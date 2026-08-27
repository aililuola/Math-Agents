package io.github.aililuola.mathproofmesh.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Bounded stdio process pool.
 *
 * <p>Each permit owns one short-lived process. This deliberately trades process
 * reuse for isolation: a timeout, crash, or parser failure cannot contaminate a
 * subsequent request.
 */
public final class PythonSidecarWorkerPool {
  public static final String PROTOCOL_VERSION = "1.0";
  private static final int MAX_REQUEST_BYTES = 1_000_000;
  private static final int MAX_STDERR_BYTES = 4_096;
  private static final Set<String> RESPONSE_FIELDS =
      Set.of(
          "jsonrpc",
          "protocol_version",
          "request_id",
          "result",
          "error",
          "certificate",
          "stdout_hash",
          "tool_version",
          "cpu_ms");
  private static final Set<String> PASSTHROUGH_ENV =
      Set.of("systemroot", "windir", "comspec", "temp", "tmp");
  private static final Pattern SECRET_ASSIGNMENT =
      Pattern.compile(
          "(?i)(api[_-]?key|authorization|bearer|password|secret|token)"
              + "\\s*[:=]\\s*[^\\s,;]+");

  private final Path pythonExecutable;
  private final Path serviceScript;
  private final Path workingDirectory;
  private final Semaphore permits;
  private final Duration acquireTimeout;

  public PythonSidecarWorkerPool(
      Path pythonExecutable,
      Path serviceScript,
      int maxWorkers,
      Duration acquireTimeout) {
    if (maxWorkers < 1 || maxWorkers > 64) {
      throw new IllegalArgumentException("maxWorkers must be in [1, 64]");
    }
    this.pythonExecutable = requireFile(pythonExecutable, "python executable");
    this.serviceScript = requireFile(serviceScript, "sidecar service");
    this.workingDirectory =
        java.util.Objects.requireNonNull(
            this.serviceScript.getParent(), "sidecar service parent");
    this.permits = new Semaphore(maxWorkers, true);
    this.acquireTimeout =
        acquireTimeout == null ? Duration.ofSeconds(10) : acquireTimeout;
  }

  public ObjectNode execute(
      String requestId, String method, ObjectNode params, SidecarLimits limits) {
    validateRequestIdentity(requestId);
    if (method == null || method.isBlank()) {
      throw new IllegalArgumentException("method is required");
    }
    if (params == null || limits == null) {
      throw new IllegalArgumentException("params and limits are required");
    }
    boolean acquired = false;
    try {
      acquired =
          permits.tryAcquire(
              acquireTimeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!acquired) {
        throw new SidecarProtocolException("sidecar worker pool is saturated");
      }
      return executeOwned(requestId, method, params, limits);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new SidecarProtocolException(
          "interrupted while waiting for a sidecar worker", "", exception);
    } finally {
      if (acquired) {
        permits.release();
      }
    }
  }

  @SuppressFBWarnings(
      value = "COMMAND_INJECTION",
      justification =
          "The executable and service script are constructor-validated regular "
              + "files; request data is transferred only through bounded stdin.")
  private ObjectNode executeOwned(
      String requestId, String method, ObjectNode params, SidecarLimits limits) {
    ObjectNode request = ContractObjectMapper.toTree(Map.of()).deepCopy();
    request.put("jsonrpc", "2.0");
    request.put("protocol_version", PROTOCOL_VERSION);
    request.put("request_id", requestId);
    request.put("method", method);
    request.set("params", params.deepCopy());
    ObjectNode limitNode = request.putObject("limits");
    limitNode.put("max_cases", limits.maxCases());
    limitNode.put("seed", limits.seed());
    limitNode.put("timeout_ms", limits.timeout().toMillis());
    limitNode.put("max_output_bytes", limits.maxOutputBytes());
    byte[] encoded =
        (ContractObjectMapper.write(request) + "\n")
            .getBytes(StandardCharsets.UTF_8);
    if (encoded.length > MAX_REQUEST_BYTES) {
      throw new SidecarProtocolException("sidecar request exceeds the protocol limit");
    }

    Process process;
    try {
      ProcessBuilder builder =
          new ProcessBuilder(
              List.of(
                  pythonExecutable.toString(),
                  serviceScript.toString()));
      builder.directory(workingDirectory.toFile());
      configureEnvironment(builder.environment());
      process = builder.start();
    } catch (IOException exception) {
      throw new SidecarProtocolException(
          "could not start the Python computation sidecar", "", exception);
    }

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<BoundedBytes> stdout =
          executor.submit(
              () -> readBounded(process.getInputStream(), limits.maxOutputBytes()));
      Future<BoundedBytes> stderr =
          executor.submit(
              () -> readBounded(process.getErrorStream(), MAX_STDERR_BYTES));
      try (OutputStream input = process.getOutputStream()) {
        input.write(encoded);
      }
      boolean exited =
          process.waitFor(limits.timeout().toMillis(), TimeUnit.MILLISECONDS);
      if (!exited) {
        destroyTree(process);
        String diagnostic = boundedStderr(stderr, Duration.ofSeconds(2));
        throw new SidecarProtocolException(
            "Python computation sidecar timed out", diagnostic);
      }
      BoundedBytes outputBytes = await(stdout, Duration.ofSeconds(2), "stdout");
      BoundedBytes errorBytes = await(stderr, Duration.ofSeconds(2), "stderr");
      String diagnostic = redact(decode(errorBytes.bytes()));
      if (outputBytes.exceeded()) {
        throw new SidecarProtocolException(
            "Python computation sidecar exceeded its output bound", diagnostic);
      }
      if (process.exitValue() != 0) {
        throw new SidecarProtocolException(
            "Python computation sidecar exited with code " + process.exitValue(),
            diagnostic);
      }
      return validateResponse(
          requestId, decodeSingleLine(outputBytes.bytes()), limits.maxOutputBytes());
    } catch (IOException exception) {
      destroyTree(process);
      throw new SidecarProtocolException(
          "sidecar stdio transport failed", "", exception);
    } catch (InterruptedException exception) {
      destroyTree(process);
      Thread.currentThread().interrupt();
      throw new SidecarProtocolException(
          "sidecar execution was interrupted", "", exception);
    }
  }

  private static ObjectNode validateResponse(
      String requestId, String line, int maxOutputBytes) {
    if (line.getBytes(StandardCharsets.UTF_8).length > maxOutputBytes) {
      throw new SidecarProtocolException("sidecar response exceeds maxOutputBytes");
    }
    JsonNode parsed;
    try {
      parsed = ContractObjectMapper.parseTree(line);
    } catch (RuntimeException exception) {
      throw new SidecarProtocolException(
          "sidecar returned malformed JSON", "", exception);
    }
    if (!parsed.isObject()) {
      throw new SidecarProtocolException("sidecar response must be an object");
    }
    ObjectNode response = (ObjectNode) parsed;
    Set<String> actual = new java.util.HashSet<>();
    response.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(RESPONSE_FIELDS)) {
      throw new SidecarProtocolException(
          "sidecar response has an invalid field set");
    }
    requiredText(response, "jsonrpc", "2.0");
    requiredText(response, "protocol_version", PROTOCOL_VERSION);
    requiredText(response, "request_id", requestId);
    JsonNode result = response.get("result");
    JsonNode error = response.get("error");
    if ((result == null || result.isNull()) == (error == null || error.isNull())) {
      throw new SidecarProtocolException(
          "sidecar response must contain exactly one of result or error");
    }
    if (result != null && !result.isNull() && !result.isObject()) {
      throw new SidecarProtocolException("sidecar result must be an object");
    }
    if (error != null && !error.isNull() && !error.isObject()) {
      throw new SidecarProtocolException("sidecar error must be an object");
    }
    JsonNode hashedNode = result != null && !result.isNull() ? result : error;
    String expectedHash = sha256(pythonCanonicalJson(hashedNode));
    JsonNode suppliedHash = response.get("stdout_hash");
    if (suppliedHash == null
        || !suppliedHash.isTextual()
        || !MessageDigest.isEqual(
            expectedHash.getBytes(StandardCharsets.US_ASCII),
            suppliedHash.textValue().getBytes(StandardCharsets.US_ASCII))) {
      throw new SidecarProtocolException("sidecar stdout_hash mismatch");
    }
    JsonNode toolVersion = response.get("tool_version");
    if (toolVersion == null
        || !toolVersion.isTextual()
        || !toolVersion
            .textValue()
            .matches(
                "mathproofmesh-python-compute/0\\.8\\.0;"
                    + "sympy=[0-9]+(?:\\.[0-9]+){1,3};"
                    + "z3=[0-9]+(?:\\.[0-9]+){1,3}")) {
      throw new SidecarProtocolException("sidecar tool_version is invalid");
    }
    JsonNode cpuMs = response.get("cpu_ms");
    if (cpuMs == null || !cpuMs.isIntegralNumber() || cpuMs.longValue() < 0) {
      throw new SidecarProtocolException("sidecar cpu_ms is invalid");
    }
    JsonNode topCertificate = nullIfJsonNull(response.get("certificate"));
    JsonNode resultCertificate =
        result != null && result.isObject() ? result.get("certificate") : null;
    resultCertificate = nullIfJsonNull(resultCertificate);
    if (!java.util.Objects.equals(topCertificate, resultCertificate)) {
      throw new SidecarProtocolException(
          "sidecar certificate envelope does not match the result");
    }
    return response.deepCopy();
  }

  private static JsonNode nullIfJsonNull(JsonNode value) {
    return value == null || value.isNull() ? null : value;
  }

  private static void configureEnvironment(Map<String, String> environment) {
    Map<String, String> inherited = Map.copyOf(environment);
    environment.clear();
    inherited.forEach(
        (key, value) -> {
          if (PASSTHROUGH_ENV.contains(key.toLowerCase(Locale.ROOT))) {
            environment.put(key, value);
          }
        });
    environment.put("PYTHONUTF8", "1");
    environment.put("PYTHONIOENCODING", "utf-8");
    environment.put("PYTHONNOUSERSITE", "1");
    environment.put("PYTHONDONTWRITEBYTECODE", "1");
    environment.put("LANG", "C.UTF-8");
    environment.put("LC_ALL", "C.UTF-8");
  }

  private static BoundedBytes readBounded(InputStream stream, int maximum)
      throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 8_192));
    byte[] buffer = new byte[8_192];
    int total = 0;
    boolean exceeded = false;
    int count;
    while ((count = stream.read(buffer)) >= 0) {
      if (count == 0) {
        continue;
      }
      int remaining = Math.max(0, maximum - total);
      int retained = Math.min(remaining, count);
      if (retained > 0) {
        output.write(buffer, 0, retained);
      }
      total += count;
      exceeded |= total > maximum;
    }
    return new BoundedBytes(output.toByteArray(), exceeded);
  }

  private static BoundedBytes await(
      Future<BoundedBytes> future, Duration timeout, String streamName) {
    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (ExecutionException exception) {
      throw new SidecarProtocolException(
          "could not read sidecar " + streamName, "", exception.getCause());
    } catch (TimeoutException exception) {
      throw new SidecarProtocolException(
          "sidecar " + streamName + " did not close", "", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new SidecarProtocolException(
          "interrupted while reading sidecar " + streamName, "", exception);
    }
  }

  private static String boundedStderr(
      Future<BoundedBytes> future, Duration timeout) {
    try {
      return redact(decode(await(future, timeout, "stderr").bytes()));
    } catch (RuntimeException exception) {
      return "";
    }
  }

  private static String decodeSingleLine(byte[] bytes) {
    String value = decode(bytes);
    if (value.endsWith("\r\n")) {
      value = value.substring(0, value.length() - 2);
    } else if (value.endsWith("\n")) {
      value = value.substring(0, value.length() - 1);
    }
    if (value.isEmpty() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
      throw new SidecarProtocolException(
          "sidecar must emit exactly one JSON line per request");
    }
    return value;
  }

  private static String decode(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException exception) {
      throw new SidecarProtocolException(
          "sidecar emitted invalid UTF-8", "", exception);
    }
  }

  private static String redact(String value) {
    String normalized = value.replace('\r', ' ').replace('\n', ' ');
    String redacted =
        SECRET_ASSIGNMENT.matcher(normalized).replaceAll("$1=[REDACTED]");
    return redacted.length() <= MAX_STDERR_BYTES
        ? redacted
        : redacted.substring(0, MAX_STDERR_BYTES);
  }

  private static void destroyTree(Process process) {
    ProcessHandle handle = process.toHandle();
    handle.descendants().forEach(ProcessHandle::destroyForcibly);
    handle.destroyForcibly();
    try {
      process.waitFor(2, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  private static void validateRequestIdentity(String requestId) {
    if (requestId == null
        || requestId.isEmpty()
        || requestId.length() > 128
        || requestId.chars().anyMatch(value -> value < 33 || value > 126)) {
      throw new IllegalArgumentException(
          "requestId must contain 1..128 printable ASCII characters");
    }
  }

  private static void requiredText(
      ObjectNode response, String field, String expected) {
    JsonNode value = response.get(field);
    if (value == null || !value.isTextual() || !expected.equals(value.textValue())) {
      throw new SidecarProtocolException(
          "sidecar " + field + " does not match the request");
    }
  }

  private static Path requireFile(Path path, String label) {
    if (path == null) {
      throw new IllegalArgumentException(label + " is required");
    }
    Path normalized = path.toAbsolutePath().normalize();
    if (!Files.isRegularFile(normalized)) {
      throw new IllegalArgumentException(label + " is not a regular file");
    }
    return normalized;
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required", exception);
    }
  }

  private static String pythonCanonicalJson(JsonNode node) {
    StringBuilder output = new StringBuilder();
    appendCanonical(output, node);
    return output.toString();
  }

  private static void appendCanonical(StringBuilder output, JsonNode node) {
    if (node == null || node.isNull()) {
      output.append("null");
    } else if (node.isObject()) {
      output.append('{');
      List<String> names =
          java.util.stream.StreamSupport.stream(
                  java.util.Spliterators.spliteratorUnknownSize(
                      node.fieldNames(), java.util.Spliterator.ORDERED),
                  false)
              .sorted(PythonSidecarWorkerPool::compareCodePoints)
              .toList();
      for (int index = 0; index < names.size(); index++) {
        if (index > 0) {
          output.append(',');
        }
        appendAsciiString(output, names.get(index));
        output.append(':');
        appendCanonical(output, node.get(names.get(index)));
      }
      output.append('}');
    } else if (node.isArray()) {
      output.append('[');
      for (int index = 0; index < node.size(); index++) {
        if (index > 0) {
          output.append(',');
        }
        appendCanonical(output, node.get(index));
      }
      output.append(']');
    } else if (node.isTextual()) {
      appendAsciiString(output, node.textValue());
    } else if (node.isBoolean()) {
      output.append(node.booleanValue());
    } else if (node.isIntegralNumber()) {
      output.append(node.bigIntegerValue());
    } else if (node.isFloatingPointNumber()) {
      double value = node.doubleValue();
      if (!Double.isFinite(value)) {
        throw new SidecarProtocolException("sidecar returned a non-finite number");
      }
      output.append(Double.toString(value));
    } else {
      throw new SidecarProtocolException("sidecar returned an unsupported JSON value");
    }
  }

  private static void appendAsciiString(StringBuilder output, String value) {
    output.append('"');
    value.codePoints()
        .forEach(
            codePoint -> {
              switch (codePoint) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                  if (codePoint >= 0x20 && codePoint <= 0x7e) {
                    output.append((char) codePoint);
                  } else if (codePoint <= 0xffff) {
                    output.append(String.format("\\u%04x", codePoint));
                  } else {
                    char[] pair = Character.toChars(codePoint);
                    output.append(String.format("\\u%04x\\u%04x", (int) pair[0], (int) pair[1]));
                  }
                }
              }
            });
    output.append('"');
  }

  private static int compareCodePoints(String left, String right) {
    int leftIndex = 0;
    int rightIndex = 0;
    while (leftIndex < left.length() && rightIndex < right.length()) {
      int leftPoint = left.codePointAt(leftIndex);
      int rightPoint = right.codePointAt(rightIndex);
      if (leftPoint != rightPoint) {
        return Integer.compare(leftPoint, rightPoint);
      }
      leftIndex += Character.charCount(leftPoint);
      rightIndex += Character.charCount(rightPoint);
    }
    return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
  }

  private record BoundedBytes(byte[] bytes, boolean exceeded) {}
}
