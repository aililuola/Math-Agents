package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ClaimEvidenceSemanticBinding;
import io.github.aililuola.mathproofmesh.contract.ClaimStatementFalsificationDecision;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import io.github.aililuola.mathproofmesh.contract.StatementFalsificationDisposition;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.FrozenClaimSnapshot;
import java.util.List;
import java.util.Map;

final class DesktopNegativeKnowledgePolarityTestSupport {
  private DesktopNegativeKnowledgePolarityTestSupport() {}

  static MessageEnvelope verifiedFact(
      ClaimEvidenceSemanticBinding binding, String polarity, String suffix) {
    String semanticHash = semanticHash(binding, polarity);
    return new MessageEnvelope(
        List.of("formal://polarity/" + suffix),
        binding.assumptions(),
        binding.conclusion(),
        "",
        null,
        binding.dependencyClaimIds(),
        List.of(),
        EvidenceType.FORMAL_KERNEL_CERTIFICATE,
        MemoryTier.FACT,
        "polarity-fact-" + suffix,
        MessageType.FORMAL_CERTIFICATE,
        1.0d,
        binding.statement(),
        binding.problemHash(),
        binding.quantifiers(),
        "formal-result-" + suffix,
        0,
        "1",
        binding.scopeLimitations(),
        "formal-kernel",
        RouteRole.REFEREE,
        "route-polarity",
        binding.statement(),
        List.of(),
        2,
        binding.variableBindings(),
        1.0d,
        ClaimStatus.VERIFIED,
        binding.claimStatementHash(),
        semanticHash,
        polarity);
  }

  static FrozenClaimSnapshot frozen(
      ClaimEvidenceSemanticBinding binding, String polarity) {
    String suffix = polarity + "-" + binding.claimId();
    return new FrozenClaimSnapshot(
        "court-" + suffix,
        binding.problemHash(),
        CanonicalJson.stableHash(List.of("root", binding.problemHash())),
        "claim-" + suffix,
        binding.claimStatementHash(),
        semanticHash(binding, polarity),
        binding.statement(),
        binding.conclusion(),
        binding.assumptions(),
        binding.quantifiers(),
        binding.variableBindings(),
        binding.scopeLimitations(),
        polarity,
        binding.dependencyClaimIds(),
        CanonicalJson.stableHash(binding.dependencyClaimIds()),
        "revision-" + suffix,
        "attempt-" + suffix,
        "route-polarity",
        "author-polarity");
  }

  static ClaimStatementFalsificationDecision noCounterexample(FrozenClaimSnapshot frozen) {
    return new ClaimStatementFalsificationDecision(
        frozen.claimId(),
        StatementFalsificationDisposition.NO_COUNTEREXAMPLE_FOUND,
        List.of(),
        "No additional counterexample was proposed.");
  }

  private static String semanticHash(
      ClaimEvidenceSemanticBinding binding, String polarity) {
    if (binding.polarity().equals(polarity)) {
      return binding.claimSemanticHash();
    }
    return CanonicalJson.stableHash(
        Map.of(
            "statement_hash", binding.claimStatementHash(),
            "assumptions", binding.assumptions(),
            "quantifiers", binding.quantifiers(),
            "bindings", binding.variableBindings(),
            "scope", binding.scopeLimitations(),
            "polarity", polarity));
  }
}
