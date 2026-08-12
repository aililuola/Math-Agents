package io.github.aililuola.mathproofmesh.provider;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public class MockClient implements LLMClient {
  private final String model;
  private final MockResponder responder;
  private final AtomicLong calls = new AtomicLong();

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "The constructor validates only the immutable model before assigning state; "
              + "the compatibility subclass defines no finalizer.")
  public MockClient(String model, MockResponder responder) {
    this.model = Objects.requireNonNull(model, "model");
    this.responder = responder;
  }

  @Override
  public String providerId() {
    return "mock";
  }

  @Override
  public LLMResponse complete(ProviderRequest request) {
    long callNumber = calls.incrementAndGet();
    if (responder == null) {
      return new LLMResponse(
          "{}",
          model,
          "mock",
          0L,
          0L,
          0.0d,
          "mock-" + callNumber,
          "stop",
          request.streaming(),
          JsonNodeFactory.instance.objectNode());
    }
    return Objects.requireNonNull(responder.respond(request), "mock response");
  }

  public long calls() {
    return calls.get();
  }
}
