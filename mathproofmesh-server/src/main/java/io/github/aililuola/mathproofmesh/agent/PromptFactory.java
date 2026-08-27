package io.github.aililuola.mathproofmesh.agent;

import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class PromptFactory {
  private static final List<String> BLIND_FORBIDDEN =
      List.of(
          "agent_id",
          "route_id",
          "route_score",
          "self_confidence",
          "previous review",
          "vote");

  private final String outputLanguage;

  public PromptFactory(String outputLanguage) {
    this.outputLanguage =
        Objects.requireNonNull(outputLanguage, "outputLanguage").strip();
    if (this.outputLanguage.isEmpty()) {
      throw new IllegalArgumentException("outputLanguage must not be blank");
    }
  }

  public <T> PromptBundle<T> typedStage(
      String stage,
      Class<T> responseType,
      Map<String, ?> sanitizedContext,
      double temperature,
      int maxOutputTokens,
      boolean streaming) {
    Objects.requireNonNull(sanitizedContext, "sanitizedContext");
    String instruction = PromptCatalog.instruction(stage);
    var responseSchema = PromptJsonSchema.forType(responseType);
    String user =
        ("[STAGE:"
                + stage
                + "]\n"
                + instruction
                + "\n"
                + "State assumptions, scope, dependencies, quantifiers, target obligations, "
                + "and a falsification condition explicitly.\n\n"
                + "SANITIZED CONTEXT:\n"
                + ContractObjectMapper.write(
                    PromptContextNormalizer.normalize(sanitizedContext))
                + "\n\nOUTPUT LANGUAGE: "
                + outputLanguage
                + "\nRESPONSE CONTRACT: "
                + responseType.getName()
                + "\nJSON SCHEMA:\n"
                + ContractObjectMapper.write(responseSchema))
            .strip();
    PromptBundle<T> bundle =
        new PromptBundle<>(
            stage,
            PromptCatalog.COMMON_SYSTEM,
            user,
            responseType,
            temperature,
            maxOutputTokens,
            streaming,
            responseSchema);
    assertBlindPromptSafe(bundle);
    return bundle;
  }

  public <T> PromptBundle<T> typedStage(
      String stage, Class<T> responseType, Map<String, ?> sanitizedContext) {
    return typedStage(
        stage, responseType, sanitizedContext, 0.0d, 16_000, false);
  }

  public static void assertBlindPromptSafe(PromptBundle<?> bundle) {
    Objects.requireNonNull(bundle, "bundle");
    if (!bundle.stage().startsWith("blind_")) {
      return;
    }
    String payload =
        (bundle.system() + "\n" + bundle.user()).toLowerCase(Locale.ROOT);
    List<String> leaked =
        BLIND_FORBIDDEN.stream().filter(payload::contains).toList();
    if (!leaked.isEmpty()) {
      throw new IllegalArgumentException(
          "blind review prompt contains forbidden metadata: " + leaked);
    }
  }
}
