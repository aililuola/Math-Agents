package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public record ProblemSemanticView(
    @JsonProperty(value = "audit_findings") @ContractNonNull List<SemanticInvariantAudit> auditFindings,
    @JsonProperty(value = "authoritative") @ContractNonNull Boolean authoritative,
    @JsonProperty(value = "candidate_confidence", required = true) @ContractNonNull Double candidateConfidence,
    @JsonProperty(value = "deterministic_audit_passed") @ContractNonNull Boolean deterministicAuditPassed,
    @JsonProperty(value = "english_statement", required = true) @ContractNonNull String englishStatement,
    @JsonProperty(value = "missing_protected_fragments") @ContractNonNull List<String> missingProtectedFragments,
    @JsonProperty(value = "notes") @ContractNonNull List<String> notes,
    @JsonProperty(value = "protected_fragments") @ContractNonNull List<String> protectedFragments,
    @JsonProperty(value = "source_language", required = true) @ContractNonNull String sourceLanguage,
    @JsonProperty(value = "source_statement_hash", required = true) @ContractNonNull String sourceStatementHash,
    @JsonProperty(value = "status", required = true) @ContractNonNull String status
) implements StrictContract {

  public ProblemSemanticView {
    if (auditFindings == null) {
      auditFindings = List.of();
    }
    auditFindings = ImmutableCollections.listOrEmpty(auditFindings);
    if (authoritative == null) {
      authoritative = false;
    }
    ContractValues.constant("authoritative", authoritative, false);
    candidateConfidence = ContractValues.required("candidate_confidence", candidateConfidence);
    ContractValues.minimum("candidate_confidence", candidateConfidence, 0.0);
    ContractValues.maximum("candidate_confidence", candidateConfidence, 1.0);
    if (deterministicAuditPassed == null) {
      deterministicAuditPassed = false;
    }
    englishStatement = ContractStrings.trim(englishStatement);
    englishStatement = ContractStrings.required("english_statement", englishStatement);
    if (missingProtectedFragments == null) {
      missingProtectedFragments = List.of();
    }
    missingProtectedFragments = ImmutableCollections.listOrEmpty(missingProtectedFragments);
    if (notes == null) {
      notes = List.of();
    }
    notes = ImmutableCollections.listOrEmpty(notes);
    if (protectedFragments == null) {
      protectedFragments = List.of();
    }
    protectedFragments = ImmutableCollections.listOrEmpty(protectedFragments);
    sourceLanguage = ContractStrings.trim(sourceLanguage);
    sourceLanguage = ContractStrings.required("source_language", sourceLanguage);
    sourceStatementHash = ContractStrings.trim(sourceStatementHash);
    sourceStatementHash = ContractStrings.required("source_statement_hash", sourceStatementHash);
    status = ContractStrings.trim(status);
    status = ContractStrings.required("status", status);
    ContractValues.oneOf("status", status, "usable", "rejected");
    if ("usable".equals(status) && !deterministicAuditPassed) {
      status = "rejected";
      LinkedHashSet<String> quarantinedNotes = new LinkedHashSet<>(notes);
      quarantinedNotes.add(
          "legacy semantic view rejected because it has no deterministic bilingual audit");
      notes = List.copyOf(quarantinedNotes);
    }
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<SemanticInvariantAudit> auditFindings() {
    return auditFindings == null ? null : List.copyOf(auditFindings);
  }

  public List<String> missingProtectedFragments() {
    return missingProtectedFragments == null ? null : List.copyOf(missingProtectedFragments);
  }

  public List<String> notes() {
    return notes == null ? null : List.copyOf(notes);
  }

  public List<String> protectedFragments() {
    return protectedFragments == null ? null : List.copyOf(protectedFragments);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
