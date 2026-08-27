package io.github.aililuola.mathproofmesh.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResearchCheckpointContractsTest {

  @Test
  void envelopeAndFrameApplyBoundsDefaultsAndDefensiveCopies() {
    ObjectNode result = (ObjectNode) ContractObjectMapper.parseTree("{\"answer\":\"kept\"}");
    CheckpointedResearchEnvelope envelope =
        new CheckpointedResearchEnvelope(null, null, result);
    result.put("answer", "mutated");

    assertEquals(ResearchFindingUpdateBatch.empty(), envelope.findingUpdates());
    assertEquals("kept", envelope.result().path("answer").asText());
    assertNotSame(envelope.result(), envelope.result());
    assertThrows(
        ContractValidationException.class,
        () -> new CheckpointedResearchEnvelope(null, null, null));

    ResearchCheckpointFrame frame = new ResearchCheckpointFrame(0, " summary ", null);
    assertEquals("summary", frame.summary());
    assertEquals(List.of(), frame.findings());
    assertThrows(
        ContractValidationException.class,
        () -> new ResearchCheckpointFrame(-1, "summary", List.of()));
    assertThrows(
        ContractValidationException.class,
        () -> new ResearchCheckpointFrame(0, " ", List.of()));
    assertThrows(
        ContractValidationException.class,
        () -> new ResearchCheckpointFrame(0, "x".repeat(2_049), List.of()));
    assertThrows(
        ContractValidationException.class,
        () ->
            new ResearchCheckpointFrame(
                0,
                "summary",
                java.util.stream.IntStream.range(0, 9)
                    .mapToObj(index -> draft(ResearchFindingKind.EXACT_EXAMPLE, "item " + index))
                    .toList()));
  }

  @Test
  void findingDraftRequiresCompleteAndOrderedExactQuoteBinding() {
    ResearchFindingDraft defaults =
        new ResearchFindingDraft(
            ResearchFindingKind.CANDIDATE_LEMMA,
            " statement ",
            " rationale ",
            null,
            null,
            " target ",
            null,
            null,
            null,
            null);
    assertEquals("statement", defaults.statement());
    assertEquals("rationale", defaults.rationale());
    assertEquals(List.of(), defaults.assumptions());
    assertEquals(List.of(), defaults.scopeLimitations());
    assertEquals("target", defaults.targetObligationId());

    ResearchFindingDraft quoted =
        new ResearchFindingDraft(
            ResearchFindingKind.EXACT_EXAMPLE,
            "statement",
            "rationale",
            List.of("assumption"),
            List.of("scope"),
            null,
            "exact",
            2,
            7,
            "hash");
    assertEquals("exact", quoted.sourceQuote());

    assertThrows(
        ContractValidationException.class,
        () ->
            new ResearchFindingDraft(
                ResearchFindingKind.EXACT_EXAMPLE,
                "statement",
                "rationale",
                List.of(),
                List.of(),
                null,
                "partial",
                null,
                null,
                null));
    assertThrows(
        ContractValidationException.class,
        () ->
            new ResearchFindingDraft(
                ResearchFindingKind.EXACT_EXAMPLE,
                "statement",
                "rationale",
                List.of(),
                List.of(),
                null,
                "bad offsets",
                -1,
                2,
                "hash"));
    assertThrows(
        ContractValidationException.class,
        () ->
            new ResearchFindingDraft(
                ResearchFindingKind.EXACT_EXAMPLE,
                "statement",
                "rationale",
                List.of(),
                List.of(),
                null,
                "bad offsets",
                4,
                2,
                "hash"));
    assertThrows(
        ContractValidationException.class,
        () ->
            new ResearchFindingDraft(
                null,
                "statement",
                "rationale",
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null));
    assertThrows(
        ContractValidationException.class,
        () -> draft(ResearchFindingKind.EXACT_EXAMPLE, " "));
  }

  @Test
  void dispositionsRequireActionSpecificEvidenceAndUniqueFindingIds() {
    ResearchFindingDisposition keep =
        new ResearchFindingDisposition(
            " finding ", ResearchFindingDispositionAction.KEEP_ACTIVE, null, null);
    assertEquals("finding", keep.findingId());

    assertThrows(
        ContractValidationException.class,
        () ->
            new ResearchFindingDisposition(
                "finding", ResearchFindingDispositionAction.DEFER, null, null));
    assertThrows(
        ContractValidationException.class,
        () ->
            new ResearchFindingDisposition(
                "finding", ResearchFindingDispositionAction.REJECT_WITH_REASON, " ", null));
    assertThrows(
        ContractValidationException.class,
        () ->
            new ResearchFindingDisposition(
                "finding", ResearchFindingDispositionAction.SUPERSEDE_WITH, null, null));
    assertThrows(
        ContractValidationException.class,
        () ->
            new ResearchFindingDisposition(
                "finding", ResearchFindingDispositionAction.KEEP_ACTIVE, null, "replacement"));

    ResearchFindingDisposition deferred =
        new ResearchFindingDisposition(
            "finding", ResearchFindingDispositionAction.DEFER, "later", null);
    ResearchFindingDisposition superseded =
        new ResearchFindingDisposition(
            "other", ResearchFindingDispositionAction.SUPERSEDE_WITH, null, "finding");
    assertEquals(2, new ResearchFindingUpdateBatch(List.of(deferred, superseded)).dispositions().size());
    assertEquals(List.of(), new ResearchFindingUpdateBatch(null).dispositions());
    assertThrows(
        ContractValidationException.class,
        () -> new ResearchFindingUpdateBatch(List.of(deferred, deferred)));
  }

  @Test
  void enumWireValuesAreStrictAndRoundTrip() {
    Arrays.stream(ResearchFindingKind.values())
        .forEach(kind -> assertEquals(kind, ResearchFindingKind.fromValue(kind.value())));
    Arrays.stream(ResearchFindingDispositionAction.values())
        .forEach(action -> assertEquals(action, ResearchFindingDispositionAction.fromValue(action.value())));

    assertEquals(
        ResearchFindingKind.CANDIDATE_LEMMA,
        ResearchFindingKind.fromValue(" candidate_lemma "));
    assertThrows(ContractValidationException.class, () -> ResearchFindingKind.fromValue(null));
    assertThrows(
        ContractValidationException.class, () -> ResearchFindingKind.fromValue("verified"));
    assertThrows(
        ContractValidationException.class,
        () -> ResearchFindingDispositionAction.fromValue(null));
    assertThrows(
        ContractValidationException.class,
        () -> ResearchFindingDispositionAction.fromValue("promote_to_fact"));
  }

  private static ResearchFindingDraft draft(ResearchFindingKind kind, String statement) {
    return new ResearchFindingDraft(
        kind,
        statement,
        "rationale",
        List.of(),
        List.of(),
        null,
        null,
        null,
        null,
        null);
  }
}
