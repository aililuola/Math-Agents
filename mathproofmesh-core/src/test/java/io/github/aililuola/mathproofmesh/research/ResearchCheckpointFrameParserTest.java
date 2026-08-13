package io.github.aililuola.mathproofmesh.research;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ResearchFindingKind;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingDraft;
import io.github.aililuola.mathproofmesh.contract.ResearchCheckpointFrame;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResearchCheckpointFrameParserTest {
  @Test
  void parsesOnlyCompleteBoundedFullLineMarkersAndSkipsCorruption() {
    String first =
        ResearchCheckpointTestFixtures.marker(
            ResearchCheckpointTestFixtures.frame(
                0,
                ResearchCheckpointTestFixtures.finding(
                    ResearchFindingKind.CANDIDATE_LEMMA, "first material lemma")));
    String corrupt =
        ResearchCheckpointFrameParser.BEGIN_MARKER
            + "\n{not-json}\n"
            + ResearchCheckpointFrameParser.END_MARKER;
    String finalFrame =
        ResearchCheckpointTestFixtures.marker(
            ResearchCheckpointTestFixtures.frame(
                2,
                ResearchCheckpointTestFixtures.finding(
                    ResearchFindingKind.SHARP_OBSTRUCTION, "later sharp obstruction")));
    String trace = "private prefix\n" + first + "\n" + corrupt + "\n" + finalFrame;

    var spans = new ResearchCheckpointFrameParser().parse(trace);

    assertThat(spans).hasSize(2);
    assertThat(spans).extracting(span -> span.frame().frameSequence()).containsExactly(0, 2);
    assertThat(spans).allMatch(span -> span.validatesAgainst(trace));
    assertThat(spans)
        .extracting(span -> span.frame().findings().getFirst().statement())
        .doesNotContain("private prefix");
  }

  @Test
  void ignoresIncompleteAndOversizedFramesWithoutHidingLaterValidFrame() {
    String incomplete =
        ResearchCheckpointFrameParser.BEGIN_MARKER + "\n{\"frame_sequence\":0";
    String oversized =
        ResearchCheckpointFrameParser.BEGIN_MARKER
            + "\n"
            + "x".repeat(ResearchCheckpointFrameParser.MAX_FRAME_BYTES + 1)
            + "\n"
            + ResearchCheckpointFrameParser.END_MARKER;
    String valid =
        ResearchCheckpointTestFixtures.marker(
            ResearchCheckpointTestFixtures.frame(
                3,
                ResearchCheckpointTestFixtures.finding(
                    ResearchFindingKind.EXACT_EXAMPLE, "exact later example")));

    assertThat(new ResearchCheckpointFrameParser().parse(incomplete + "\n" + valid))
        .singleElement()
        .extracting(span -> span.frame().frameSequence())
        .isEqualTo(3);
    assertThat(new ResearchCheckpointFrameParser().parse(oversized + "\n" + valid))
        .singleElement()
        .extracting(span -> span.frame().frameSequence())
        .isEqualTo(3);
  }

  @Test
  void rejectsDuplicateSequencesAndInvalidFallbackQuoteBindings() {
    String quote = "exact public quote";
    ResearchFindingDraft invalidQuote =
        new ResearchFindingDraft(
            ResearchFindingKind.CANDIDATE_LEMMA,
            "fallback candidate",
            "No complete marker-free inference receives authority.",
            List.of(),
            List.of("current call"),
            null,
            quote,
            0,
            quote.length(),
            CanonicalJson.stableHash("different quote"));
    String invalid =
        ResearchCheckpointTestFixtures.marker(
            new ResearchCheckpointFrame(4, "invalid quote binding", List.of(invalidQuote)));
    String first =
        ResearchCheckpointTestFixtures.marker(
            ResearchCheckpointTestFixtures.frame(
                5,
                ResearchCheckpointTestFixtures.finding(
                    ResearchFindingKind.EXACT_EXAMPLE, "first sequence five")));
    String duplicate =
        ResearchCheckpointTestFixtures.marker(
            ResearchCheckpointTestFixtures.frame(
                5,
                ResearchCheckpointTestFixtures.finding(
                    ResearchFindingKind.SHARP_OBSTRUCTION, "duplicate sequence five")));

    var spans = new ResearchCheckpointFrameParser().parse(invalid + "\n" + first + "\n" + duplicate);

    assertThat(spans).singleElement();
    assertThat(spans.getFirst().frame().findings().getFirst().statement())
        .isEqualTo("first sequence five");
  }
}
