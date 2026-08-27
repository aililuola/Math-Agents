package io.github.aililuola.mathproofmesh.inspiration;

import io.github.aililuola.mathproofmesh.contract.InspirationContextMode;
import io.github.aililuola.mathproofmesh.contract.InspirationMechanism;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Bounded inspiration prompt context with warm/cold/meta isolation. */
public final class MechanismContextProfile {
  private final InspirationPolicy.Limits limits;

  public MechanismContextProfile(InspirationPolicy.Limits limits) {
    this.limits = java.util.Objects.requireNonNull(limits, "limits");
  }

  public PromptContext build(
      InspirationMechanism mechanism,
      InspirationContextMode mode,
      String problemStatement,
      List<String> openObligations,
      List<String> verifiedFacts,
      List<String> negativeAnalogies,
      Map<String, Number> observableMetrics,
      List<String> proofTranscripts) {
    List<String> facts =
        mode == InspirationContextMode.WARM
            ? bounded(verifiedFacts, limits.warmContextMaxFacts())
            : List.of();
    List<String> negatives =
        mode == InspirationContextMode.WARM
            ? bounded(negativeAnalogies, limits.warmContextMaxNegatives())
            : List.of();
    StringBuilder content = new StringBuilder();
    content.append("mechanism=").append(mechanism.value()).append('\n');
    content.append("mode=").append(mode.value()).append('\n');
    content.append("problem=").append(sanitize(problemStatement)).append('\n');
    content.append("open_obligations=").append(bounded(openObligations, 16)).append('\n');
    if (!facts.isEmpty()) {
      content.append("verified_facts=").append(facts).append('\n');
    }
    if (!negatives.isEmpty()) {
      content.append("negative_analogies=").append(negatives).append('\n');
    }
    if (mechanism == InspirationMechanism.META_REPLAN) {
      content.append("observable_metrics=")
          .append(observableMetrics == null ? Map.of() : Map.copyOf(observableMetrics))
          .append('\n');
    }
    String result = content.toString();
    if (result.length() > limits.contextMaxChars()) {
      result = result.substring(0, limits.contextMaxChars());
    }
    return new PromptContext(
        mechanism,
        mode,
        facts,
        negatives,
        mechanism == InspirationMechanism.META_REPLAN
            ? (observableMetrics == null ? Map.of() : Map.copyOf(observableMetrics))
            : Map.of(),
        result,
        false);
  }

  private static String sanitize(String value) {
    if (value == null) {
      return "";
    }
    return value.replace('\u0000', ' ').replaceAll("[\\r\\n]+", " ").strip();
  }

  private static <T> List<T> bounded(List<T> values, int limit) {
    if (values == null || limit == 0) {
      return List.of();
    }
    return List.copyOf(new ArrayList<>(values).subList(0, Math.min(limit, values.size())));
  }

  public record PromptContext(
      InspirationMechanism mechanism,
      InspirationContextMode mode,
      List<String> verifiedFacts,
      List<String> negativeAnalogies,
      Map<String, Number> observableMetrics,
      String content,
      boolean containsProofTranscript) {
    public PromptContext {
      verifiedFacts = List.copyOf(verifiedFacts);
      negativeAnalogies = List.copyOf(negativeAnalogies);
      observableMetrics = Map.copyOf(observableMetrics);
      content = content == null ? "" : content;
    }
  }
}
