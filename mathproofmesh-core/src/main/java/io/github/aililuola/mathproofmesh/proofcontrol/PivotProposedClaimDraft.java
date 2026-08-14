package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.List;

/** Server-compiled payload for a proposed Claim that still has no verification authority. */
public record PivotProposedClaimDraft(
    String claimId,
    String statement,
    String statementHash,
    List<String> assumptions,
    List<String> proofStepRefs,
    List<String> dependencyClaimIds,
    List<String> tags) {
  public PivotProposedClaimDraft {
    claimId = PivotValues.required(claimId, "claimId");
    statement = PivotValues.required(statement, "statement");
    statementHash = PivotValues.required(statementHash, "statementHash");
    assumptions = normalizedStrings(assumptions, "assumption");
    proofStepRefs = normalizedStrings(proofStepRefs, "proofStepRef");
    dependencyClaimIds = normalizedStrings(dependencyClaimIds, "dependencyClaimId");
    tags = normalizedStrings(tags, "tag");
  }

  public String computedStatementHash() {
    return statementHash(statement);
  }

  public boolean hashMatches() {
    return hashesEqual(statementHash, computedStatementHash());
  }

  public static String statementHash(String statement) {
    return CanonicalJson.stableHash(
        ProofIdentity.normalizeText(PivotValues.required(statement, "statement")));
  }

  public static boolean hashesEqual(String left, String right) {
    if (left == null || right == null) {
      return false;
    }
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public List<String> assumptions() {
    return List.copyOf(assumptions);
  }

  @Override
  public List<String> proofStepRefs() {
    return List.copyOf(proofStepRefs);
  }

  @Override
  public List<String> dependencyClaimIds() {
    return List.copyOf(dependencyClaimIds);
  }

  @Override
  public List<String> tags() {
    return List.copyOf(tags);
  }

  private static List<String> normalizedStrings(List<String> values, String name) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (String value : values) {
      normalized.add(PivotValues.required(value, name));
    }
    return List.copyOf(normalized);
  }
}
