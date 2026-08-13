package io.github.aililuola.mathproofmesh.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.CheckpointedResearchEnvelope;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.research.ResearchCheckpointFrameParser;
import java.util.Objects;
import java.util.Set;

/** Applies public research checkpoints only to an exact, reviewed stage allowlist. */
public final class ResearchCheckpointedPromptFactory {
  private static final Set<String> ALLOWED_RESEARCH_STAGES =
      Set.of(
          "strategy_generation",
          "independent_exploration",
          "representation_switchboard",
          "structural_analogy_search",
          "invent_auxiliary_construction",
          "hypothesize_invariant",
          "reverse_goal_analysis",
          "bridge_lemma",
          "surprise_exploration",
          "persistent_meta_strategy");

  public <T> CheckpointedPromptBundle<T> checkpoint(PromptBundle<T> original) {
    PromptBundle<T> source = Objects.requireNonNull(original, "original");
    if (!isAllowedResearchStage(source.stage())) {
      throw new IllegalArgumentException(
          "public research checkpoints are forbidden for stage: " + source.stage());
    }
    JsonNode resultSchema =
        source.responseSchema() == null
            ? PromptJsonSchema.forType(source.responseType())
            : source.responseSchema();
    ObjectNode envelopeSchema =
        (ObjectNode) PromptJsonSchema.forType(CheckpointedResearchEnvelope.class);
    envelopeSchema.withObject("properties").set("result", resultSchema.deepCopy());
    ArrayNode required = envelopeSchema.withArray("required");
    boolean resultRequired = false;
    for (JsonNode name : required) {
      resultRequired |= "result".equals(name.asText());
    }
    if (!resultRequired) {
      required.add("result");
    }

    String checkpointInstruction =
        ("PUBLIC RESEARCH CHECKPOINTS:\n"
                + "Do not reveal private chain-of-thought. Publish only concise declarative "
                + "intermediate findings that another agent may safely inspect. During long "
                + "research, a complete checkpoint may be emitted exactly as three full-line "
                + "parts:\n"
                + ResearchCheckpointFrameParser.BEGIN_MARKER
                + "\n{ strict ResearchCheckpointFrame JSON; at most 8 findings }\n"
                + ResearchCheckpointFrameParser.END_MARKER
                + "\nEach frame must be at most 16 KiB and use a unique nonnegative "
                + "frame_sequence. Emit at most 16 frames. Never label a finding verified, fact, "
                + "or proved. The final response must be one CheckpointedResearchEnvelope JSON "
                + "object with public_checkpoint, finding_updates, and result. The nested result "
                + "must satisfy the original response contract exactly. An omitted finding remains "
                + "active; do not silently discard it.")
            .strip();
    String user =
        (source.user()
                + "\n\n"
                + checkpointInstruction
                + "\n\nCHECKPOINTED RESPONSE CONTRACT: "
                + CheckpointedResearchEnvelope.class.getName()
                + "\nORIGINAL RESULT CONTRACT: "
                + source.responseType().getName()
                + "\nCHECKPOINTED JSON SCHEMA:\n"
                + ContractObjectMapper.write(envelopeSchema))
            .strip();
    PromptBundle<CheckpointedResearchEnvelope> envelope =
        new PromptBundle<>(
            source.stage(),
            source.system(),
            user,
            CheckpointedResearchEnvelope.class,
            source.temperature(),
            source.maxOutputTokens(),
            source.streaming(),
            envelopeSchema);
    return new CheckpointedPromptBundle<>(source, envelope, source.responseType(), resultSchema);
  }

  public static boolean isAllowedResearchStage(String stage) {
    return stage != null && ALLOWED_RESEARCH_STAGES.contains(stage.strip());
  }

  public static Set<String> allowedResearchStages() {
    return ALLOWED_RESEARCH_STAGES;
  }
}
