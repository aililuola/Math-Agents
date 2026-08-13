package io.github.aililuola.mathproofmesh.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aililuola.mathproofmesh.contract.CheckpointedResearchEnvelope;
import java.util.Objects;

/** Envelope prompt plus the unchanged strict response contract nested under {@code result}. */
public record CheckpointedPromptBundle<T>(
    PromptBundle<T> originalBundle,
    PromptBundle<CheckpointedResearchEnvelope> promptBundle,
    Class<T> resultType,
    JsonNode resultSchema) {

  public CheckpointedPromptBundle {
    originalBundle = Objects.requireNonNull(originalBundle, "originalBundle");
    promptBundle = Objects.requireNonNull(promptBundle, "promptBundle");
    resultType = Objects.requireNonNull(resultType, "resultType");
    resultSchema = Objects.requireNonNull(resultSchema, "resultSchema").deepCopy();
    if (!originalBundle.stage().equals(promptBundle.stage())) {
      throw new IllegalArgumentException("original and checkpointed stages must match");
    }
    if (!ResearchCheckpointedPromptFactory.isAllowedResearchStage(promptBundle.stage())) {
      throw new IllegalArgumentException(
          "checkpointed prompt is forbidden for stage: " + promptBundle.stage());
    }
  }

  @Override
  public JsonNode resultSchema() {
    return resultSchema.deepCopy();
  }
}
