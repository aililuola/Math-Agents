package io.github.aililuola.mathproofmesh.verification;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.FormalCertificateRef;
import io.github.aililuola.mathproofmesh.contract.FormalStatementPacket;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphStore;
import java.util.List;
import java.util.Map;

/** Formal compiler failure opens a repair task; it never refutes natural mathematics. */
public final class CompilerFeedbackInterpreter {

  public FormalCertificateRef unavailable(
      FormalStatementPacket packet, String backendName) {
    return new FormalCertificateRef(
        null,
        backendName,
        null,
        null,
        List.of("formal verifier backend unavailable"),
        packet.packetId(),
        statementHash(packet.statement()),
        "pending");
  }

  public ProofObligation applyFailure(
      FormalStatementPacket packet,
      FormalCertificateRef certificate,
      ProofGraphStore graph) {
    if (!"failed".equals(certificate.status())) {
      return null;
    }
    ProofObligation source = graph.getObligation(packet.obligationId());
    ProofObligation task =
        new ProofObligation(
            packet.assumptions(),
            source.centrality(),
            null,
            List.of(packet.obligationId()),
            List.of(),
            List.of(),
            null,
            ObligationKind.FORMALIZATION_TASK,
            "formalize:" + packet.obligationId(),
            null,
            Math.max(0.6, source.priority()),
            packet.problemHash(),
            packet.quantifiers(),
            source.routeIds(),
            "Repair formalization of "
                + packet.obligationId()
                + ": "
                + String.join(" | ", certificate.diagnostics()),
            "open");
    return graph.addObligation(task);
  }

  private static String statementHash(String statement) {
    return CanonicalJson.stableHash(Map.of("statement", statement));
  }
}
