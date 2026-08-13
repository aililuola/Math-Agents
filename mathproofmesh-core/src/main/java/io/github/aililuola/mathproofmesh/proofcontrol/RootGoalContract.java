package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/** Immutable authority boundary for every semantic view derived from the root problem. */
public record RootGoalContract(
    String sourceStatement,
    String sourceStatementHash,
    ExactGoalContractChecker.GoalSignature signature) {
  public RootGoalContract {
    sourceStatement = ProofControlModels.required(sourceStatement, "sourceStatement");
    sourceStatementHash =
        ProofControlModels.required(sourceStatementHash, "sourceStatementHash");
    signature = Objects.requireNonNull(signature, "signature");
    String expectedHash = CanonicalJson.stableHash(sourceStatement);
    if (!MessageDigest.isEqual(
        expectedHash.getBytes(StandardCharsets.US_ASCII),
        sourceStatementHash.getBytes(StandardCharsets.US_ASCII))) {
      throw new IllegalArgumentException("sourceStatementHash does not match sourceStatement");
    }
  }

  public static RootGoalContract freeze(
      String sourceStatement, ExactGoalContractChecker checker) {
    String source = ProofControlModels.required(sourceStatement, "sourceStatement");
    ExactGoalContractChecker exactChecker = Objects.requireNonNull(checker, "checker");
    return new RootGoalContract(
        source, CanonicalJson.stableHash(source), exactChecker.extract(source));
  }
}
