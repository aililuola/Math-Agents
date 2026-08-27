package io.github.aililuola.mathproofmesh.verification;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProblemContract;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import io.github.aililuola.mathproofmesh.contract.ProofStep;
import io.github.aililuola.mathproofmesh.contract.VerificationReport;
import io.github.aililuola.mathproofmesh.contract.VerificationStage;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import java.util.List;

final class VerificationFixtures {
  static final String PROBLEM_HASH = "phase-09-problem";

  private VerificationFixtures() {}

  static ProofStep step(String id, String statement) {
    ObjectNode node = object();
    node.put("step_id", id);
    node.put("statement", statement);
    node.put("justification", "Established from the declared dependencies.");
    return ContractObjectMapper.read(node, ProofStep.class);
  }

  static ProofObligation obligation(
      String id,
      ObligationKind kind,
      double centrality,
      List<String> routeIds) {
    ObjectNode node = object();
    node.put("obligation_id", id);
    node.put("problem_hash", PROBLEM_HASH);
    node.put("kind", kind.value());
    node.put("statement", id + " statement");
    node.put("normalized_statement", id + " statement");
    node.put("centrality", centrality);
    node.set("route_ids", ContractObjectMapper.toTree(routeIds));
    return ContractObjectMapper.read(node, ProofObligation.class);
  }

  static MessageEnvelope fact(
      String id, String statement, EvidenceType evidenceType, List<String> dependencies) {
    ObjectNode node = object();
    node.put("message_id", id);
    node.put("problem_hash", PROBLEM_HASH);
    node.put("source_route_id", "private-route-" + id);
    node.put("source_agent_id", "private-agent-" + id);
    node.put("source_role", "prover");
    node.put("message_type", "verified_lemma");
    node.put("statement", statement);
    node.put("normalized_statement", statement.toLowerCase(java.util.Locale.ROOT));
    node.put("conclusion", statement);
    node.put("evidence_type", evidenceType.value());
    node.put("memory_tier", "fact");
    node.put("verification_status", "verified");
    node.put("verification_confidence", 0.95);
    node.put("normalization_confidence", 0.90);
    node.put("round_created", 1);
    node.set("dependencies", ContractObjectMapper.toTree(dependencies));
    node.set(
        "artifact_refs",
        ContractObjectMapper.toTree(List.of("runs/private-agent/private-route/cert.txt")));
    return ContractObjectMapper.read(node, MessageEnvelope.class);
  }

  static ProblemContract problem() {
    ObjectNode node = object();
    node.put("exact_statement", "Prove the displayed identity for every positive integer.");
    node.put("normalized_statement", "displayed identity positive integer");
    return ContractObjectMapper.read(node, ProblemContract.class);
  }

  static VerificationReport report(
      String reviewer,
      VerificationStage stage,
      VerificationVerdict verdict) {
    ObjectNode node = object();
    node.put("agent_id", reviewer);
    node.put("target_id", "target");
    node.put("target_type", "attempt");
    node.put("stage", stage.value());
    node.put("verdict", verdict.value());
    node.put("confidence", 0.95);
    node.put("concise_feedback", "Independent verification result.");
    if (verdict == VerificationVerdict.FAIL) {
      ObjectNode issue = object();
      issue.put("phase", "structural");
      issue.put("severity", "error");
      issue.put("description", "The proof structure is incomplete.");
      node.putArray("issues").add(issue);
    }
    return ContractObjectMapper.read(node, VerificationReport.class);
  }

  static ObjectNode negative(
      String id, String statement, String evidenceType) {
    ObjectNode node = object();
    node.put("item_id", id);
    node.put("statement", statement);
    node.put("evidence_type", evidenceType);
    node.put("agent_id", "private-negative-agent");
    node.put("route_id", "private-negative-route");
    return node;
  }

  static ObjectNode object() {
    return JsonNodeFactory.instance.objectNode();
  }
}
