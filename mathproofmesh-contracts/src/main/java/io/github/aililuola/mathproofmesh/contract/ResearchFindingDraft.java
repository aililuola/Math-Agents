package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Public, non-authoritative research progress. Server-owned identifiers are intentionally absent. */
public record ResearchFindingDraft(
    @JsonProperty(value = "kind", required = true) @ContractNonNull ResearchFindingKind kind,
    @JsonProperty(value = "statement", required = true) @ContractNonNull String statement,
    @JsonProperty(value = "rationale", required = true) @ContractNonNull String rationale,
    @JsonProperty(value = "assumptions") @ContractNonNull List<String> assumptions,
    @JsonProperty(value = "scope_limitations") @ContractNonNull List<String> scopeLimitations,
    @JsonProperty(value = "target_obligation_id") String targetObligationId,
    @JsonProperty(value = "source_quote") String sourceQuote,
    @JsonProperty(value = "quote_start") Integer quoteStart,
    @JsonProperty(value = "quote_end") Integer quoteEnd,
    @JsonProperty(value = "quote_sha256") String quoteSha256) implements StrictContract {

  public ResearchFindingDraft {
    kind = ContractValues.required("kind", kind);
    statement = ContractStrings.required("statement", ContractStrings.trim(statement));
    rationale = ContractStrings.required("rationale", ContractStrings.trim(rationale));
    ContractValues.minimumLength("statement", statement, 1);
    ContractValues.maximumLength("statement", statement, 4_096);
    ContractValues.minimumLength("rationale", rationale, 1);
    ContractValues.maximumLength("rationale", rationale, 4_096);
    assumptions = assumptions == null ? List.of() : ImmutableCollections.listOrEmpty(assumptions);
    scopeLimitations =
        scopeLimitations == null ? List.of() : ImmutableCollections.listOrEmpty(scopeLimitations);
    targetObligationId = ContractStrings.trim(targetObligationId);
    sourceQuote = ContractStrings.trim(sourceQuote);
    quoteSha256 = ContractStrings.trim(quoteSha256);
    boolean hasQuote = sourceQuote != null;
    if (hasQuote != (quoteStart != null && quoteEnd != null && quoteSha256 != null)) {
      throw new ContractValidationException(
          "source_quote, quote_start, quote_end, and quote_sha256 must be supplied together");
    }
    if (hasQuote && (quoteStart < 0 || quoteEnd < quoteStart)) {
      throw new ContractValidationException("quote offsets must be nonnegative and ordered");
    }
  }

  @Override
  public List<String> assumptions() {
    return List.copyOf(assumptions);
  }

  @Override
  public List<String> scopeLimitations() {
    return List.copyOf(scopeLimitations);
  }
}
