package io.github.aililuola.mathproofmesh.research;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingKind;
import org.junit.jupiter.api.Test;

class ResearchCheckpointTraceSpanValidationTest {
  @Test
  void validatesTraceOffsetsHashAndFallbackExactQuoteDeterministically() {
    String trace =
        "prefix\n"
            + ResearchCheckpointTestFixtures.marker(
                ResearchCheckpointTestFixtures.frame(
                    4,
                    ResearchCheckpointTestFixtures.finding(
                        ResearchFindingKind.REPRESENTATION_INSIGHT,
                        "triangle hitting-set representation")))
            + "\nsuffix";
    ResearchCheckpointTraceSpan span =
        new ResearchCheckpointFrameParser().parse(trace).getFirst();
    assertThat(span.validatesAgainst(trace)).isTrue();
    assertThat(span.validatesAgainst(trace + "x")).isFalse();

    String quote = "triangle hitting-set representation";
    int start = trace.indexOf(quote);
    assertThat(
            ResearchCheckpointTraceSpan.validatesExactQuote(
                trace, start, start + quote.length(), quote, CanonicalJson.stableHash(quote)))
        .isTrue();
    assertThat(
            ResearchCheckpointTraceSpan.validatesExactQuote(
                trace, start + 1, start + quote.length(), quote, CanonicalJson.stableHash(quote)))
        .isFalse();
  }
}
