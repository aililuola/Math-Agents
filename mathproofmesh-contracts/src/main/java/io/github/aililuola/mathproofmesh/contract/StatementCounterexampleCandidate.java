package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Non-authoritative counterexample proposal emitted by a statement falsifier. */
public record StatementCounterexampleCandidate(
    @JsonProperty(value = "candidate_id") String candidateId,
    @JsonProperty(value = "claim_id", required = true) @ContractNonNull String claimId,
    @JsonProperty(value = "statement_hash", required = true) @ContractNonNull String statementHash,
    @JsonProperty(value = "witness", required = true) @ContractNonNull String witness,
    @JsonProperty(value = "assumptions") @ContractNonNull List<String> assumptions,
    @JsonProperty(value = "quantifiers") @ContractNonNull List<QuantifierSpec> quantifiers,
    @JsonProperty(value = "scope_limitations") @ContractNonNull List<String> scopeLimitations,
    @JsonProperty(value = "polarity", required = true) @ContractNonNull String polarity,
    @JsonProperty(value = "evidence_refs") @ContractNonNull List<String> evidenceRefs)
    implements StrictContract {
  public StatementCounterexampleCandidate {
    if (candidateId == null) {
      candidateId = PythonCompatibleIdGenerator.newId("counterexample-candidate");
    }
    candidateId = ContractStrings.required("candidate_id", ContractStrings.trim(candidateId));
    claimId = ContractStrings.required("claim_id", ContractStrings.trim(claimId));
    statementHash =
        ContractStrings.required("statement_hash", ContractStrings.trim(statementHash));
    witness = ContractStrings.required("witness", ContractStrings.trim(witness));
    assumptions = ImmutableCollections.listOrEmpty(assumptions);
    quantifiers = ImmutableCollections.listOrEmpty(quantifiers);
    scopeLimitations = ImmutableCollections.listOrEmpty(scopeLimitations);
    polarity = ContractStrings.required("polarity", ContractStrings.trim(polarity));
    evidenceRefs = ImmutableCollections.listOrEmpty(evidenceRefs);
  }

  @Override
  public List<String> assumptions() {
    return List.copyOf(assumptions);
  }

  @Override
  public List<QuantifierSpec> quantifiers() {
    return List.copyOf(quantifiers);
  }

  @Override
  public List<String> scopeLimitations() {
    return List.copyOf(scopeLimitations);
  }

  @Override
  public List<String> evidenceRefs() {
    return List.copyOf(evidenceRefs);
  }
}
