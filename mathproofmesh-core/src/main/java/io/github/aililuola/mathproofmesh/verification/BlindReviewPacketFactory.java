package io.github.aililuola.mathproofmesh.verification;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.BlindReviewPacket;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.FinalProof;
import io.github.aililuola.mathproofmesh.contract.ProblemContract;
import io.github.aililuola.mathproofmesh.contract.ProofStep;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Builds the sole packet accepted by final blind reviewers. */
public final class BlindReviewPacketFactory {

  public BlindReviewPacket build(
      ProblemContract problem,
      FinalProof proof,
      List<ObjectNode> factPackets,
      List<String> missingFactRefs,
      List<ObjectNode> negativeEvidence,
      int negativeMaxItems,
      int negativeCharBudget) {
    return build(
        problem,
        renderProof(proof),
        factPackets,
        missingFactRefs,
        negativeEvidence,
        negativeMaxItems,
        negativeCharBudget);
  }

  public BlindReviewPacket build(
      ProblemContract problem,
      String sanitizedProof,
      List<ObjectNode> factPackets,
      List<String> missingFactRefs,
      List<ObjectNode> negativeEvidence,
      int negativeMaxItems,
      int negativeCharBudget) {
    if (negativeMaxItems < 0 || negativeCharBudget < 0) {
      throw new IllegalArgumentException("negative context bounds cannot be negative");
    }
    List<ObjectNode> facts = sanitize(factPackets);
    List<ObjectNode> sanitizedNegative = sanitize(negativeEvidence);
    List<ObjectNode> ordered =
        sanitizedNegative.stream()
            .sorted(
                Comparator.comparing(
                        (ObjectNode item) ->
                            !"counterexample".equals(item.path("evidence_type").asText()))
                    .thenComparing(item -> item.path("item_id").asText()))
            .toList();
    List<ObjectNode> selectedNegative = new ArrayList<>();
    int used = 0;
    int mandatoryOmitted = 0;
    for (ObjectNode item : ordered) {
      int size = ContractObjectMapper.write(item).length();
      boolean mandatory =
          "counterexample".equals(item.path("evidence_type").asText());
      if (selectedNegative.size() >= negativeMaxItems
          || used + size > negativeCharBudget) {
        if (mandatory) {
          mandatoryOmitted++;
        }
        continue;
      }
      selectedNegative.add(item);
      used += size;
    }
    List<String> forbiddenClaims =
        selectedNegative.stream()
            .filter(item -> "counterexample".equals(item.path("evidence_type").asText()))
            .map(item -> item.path("statement").asText())
            .filter(value -> !value.isBlank())
            .toList();
    int omitted = ordered.size() - selectedNegative.size();
    List<String> missing =
        missingFactRefs == null ? List.of() : List.copyOf(missingFactRefs);
    List<String> failureReasons = new ArrayList<>();
    if (!missing.isEmpty()) {
      failureReasons.add("missing cited Fact references");
    }
    if (mandatoryOmitted > 0) {
      failureReasons.add("mandatory counterexample evidence omitted by bound");
    }
    BlindReviewPacket packet =
        new BlindReviewPacket(
            facts,
            null,
            missing.isEmpty(),
            failureReasons,
            sanitizedProof,
            forbiddenClaims,
            missing,
            negativeCharBudget,
            used,
            mandatoryOmitted == 0,
            omitted > 0,
            omitted,
            selectedNegative,
            ordered.size(),
            mandatoryOmitted,
            problem);
    BlindReviewPolicy.assertSafe(reviewerPayload(packet));
    return packet;
  }

  public ObjectNode reviewerPayload(BlindReviewPacket packet) {
    ObjectNode payload =
        BlindReviewPolicy.sanitize((ObjectNode) ContractObjectMapper.toTree(packet));
    ObjectNode problem = BlindProblemView.from(packet.problem());
    payload.set("problem", problem);
    BlindReviewPolicy.assertSafe(payload);
    return payload;
  }

  private static List<ObjectNode> sanitize(List<ObjectNode> packets) {
    if (packets == null) {
      return List.of();
    }
    return packets.stream().map(BlindReviewPolicy::sanitize).toList();
  }

  private static String renderProof(FinalProof proof) {
    java.util.Objects.requireNonNull(proof, "proof");
    StringBuilder rendered = new StringBuilder();
    rendered.append("Final answer:\n").append(proof.answer()).append("\n\nProof steps:\n");
    int ordinal = 1;
    for (ProofStep step : proof.proofSteps()) {
      rendered
          .append(ordinal++)
          .append(". [")
          .append(step.stepId())
          .append("] ")
          .append(step.statement())
          .append("\n   Justification: ")
          .append(step.justification());
      if (!step.dependencies().isEmpty()) {
        rendered.append("\n   Depends on: ").append(String.join(", ", step.dependencies()));
      }
      if (!step.calculations().isEmpty()) {
        rendered.append("\n   Calculations: ").append(String.join("; ", step.calculations()));
      }
      rendered.append('\n');
    }
    if (!proof.dependencies().isEmpty()) {
      rendered.append("\nDeclared dependencies:\n- ")
          .append(String.join("\n- ", proof.dependencies()))
          .append('\n');
    }
    if (!proof.caveats().isEmpty()) {
      rendered.append("\nCaveats:\n- ")
          .append(String.join("\n- ", proof.caveats()))
          .append('\n');
    }
    return rendered.toString().strip();
  }

  private static final class BlindProblemView {
    private BlindProblemView() {}

    static ObjectNode from(ProblemContract problem) {
      ObjectNode result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
      result.put("exact_statement", problem.exactStatement());
      result.put("normalized_statement", problem.normalizedStatement());
      result.put("canonical_statement", problem.canonicalStatement());
      result.put("original_statement", problem.originalStatement());
      result.set("definitions", ContractObjectMapper.toTree(problem.definitions()));
      result.set("deliverables", ContractObjectMapper.toTree(problem.deliverables()));
      result.set("hard_constraints", ContractObjectMapper.toTree(problem.hardConstraints()));
      result.put("output_language", problem.outputLanguage());
      return result;
    }
  }
}
