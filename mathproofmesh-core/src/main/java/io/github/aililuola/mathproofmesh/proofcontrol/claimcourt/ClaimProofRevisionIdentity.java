package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ClaimProofRevisionIdentity {
  private static final String PREFIX = "claim-proof-original-";
  private static final Pattern LEGACY =
      Pattern.compile("^claim-proof-original-([0-9a-f]{24})$");
  private static final Pattern SCOPED =
      Pattern.compile("^claim-proof-original-([0-9a-f]{24})-claim-([0-9a-f]{24})$");

  private ClaimProofRevisionIdentity() {}

  static String originalId(
      String problemHash,
      String rootGoalHash,
      String claimSemanticHash,
      String proofHash) {
    String proof = ClaimCourtValues.required(proofHash, "proofHash");
    String claimScope =
        CanonicalJson.stableHash(
                List.of(
                    ClaimCourtValues.required(problemHash, "problemHash"),
                    ClaimCourtValues.required(rootGoalHash, "rootGoalHash"),
                    ClaimCourtValues.required(claimSemanticHash, "claimSemanticHash")))
            .substring(0, 24);
    return PREFIX + proof.substring(0, 24) + "-claim-" + claimScope;
  }

  static boolean compatible(String left, String right) {
    if (left.equals(right)) {
      return true;
    }
    Matcher leftLegacy = LEGACY.matcher(left);
    Matcher rightLegacy = LEGACY.matcher(right);
    Matcher leftScoped = SCOPED.matcher(left);
    Matcher rightScoped = SCOPED.matcher(right);
    return leftLegacy.matches()
            && rightScoped.matches()
            && leftLegacy.group(1).equals(rightScoped.group(1))
        || rightLegacy.matches()
            && leftScoped.matches()
            && rightLegacy.group(1).equals(leftScoped.group(1));
  }
}
