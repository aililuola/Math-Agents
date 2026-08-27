package io.github.aililuola.mathproofmesh.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ResearchCheckpointFrame;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingDisposition;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingDispositionAction;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingDraft;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingKind;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingUpdateBatch;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResearchCheckpointBoundaryValidationTest {

  @Test
  void parserHandlesHashCrLfNestingAndExactLineBoundaries() {
    ResearchCheckpointFrame frame =
        ResearchCheckpointTestFixtures.frame(
            1,
            ResearchCheckpointTestFixtures.finding(
                ResearchFindingKind.EXACT_EXAMPLE, "hash-bound example"));
    String marker = ResearchCheckpointTestFixtures.marker(frame);
    String crlfTrace = ("prefix\n" + marker + "\nsuffix").replace("\n", "\r\n");
    ResearchCheckpointFrameParser parser = new ResearchCheckpointFrameParser();

    assertThat(parser.parse(crlfTrace, CanonicalJson.stableHash(crlfTrace))).hasSize(1);
    assertThat(parser.parse(crlfTrace, CanonicalJson.stableHash(crlfTrace + "x"))).isEmpty();
    assertThat(parser.parse(null)).isEmpty();
    assertThat(parser.parse("inline " + marker)).isEmpty();
    assertThat(parser.parse(ResearchCheckpointFrameParser.BEGIN_MARKER)).isEmpty();
    assertThat(parser.parse(ResearchCheckpointFrameParser.BEGIN_MARKER + "\n{}"))
        .isEmpty();
    assertThat(parser.parse(ResearchCheckpointFrameParser.BEGIN_MARKER + "\r{}"))
        .isEmpty();
    assertThatThrownBy(() -> parser.parse(crlfTrace, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expectedTraceSha256");

    String nested =
        ResearchCheckpointFrameParser.BEGIN_MARKER
            + "\n{\"frame_sequence\":0"
            + "\n"
            + marker;
    assertThat(parser.parse(nested))
        .singleElement()
        .extracting(span -> span.frame().frameSequence())
        .isEqualTo(1);
  }

  @Test
  void parserCapsCandidatesAndAcceptsOnlyVerifiedExactQuotes() {
    String prefix = "exact public quote\n";
    String quote = "exact public quote";
    ResearchFindingDraft quoted =
        new ResearchFindingDraft(
            ResearchFindingKind.CANDIDATE_LEMMA,
            "quoted candidate",
            "bounded rationale",
            List.of(),
            List.of(),
            null,
            quote,
            0,
            quote.length(),
            CanonicalJson.stableHash(quote));
    String quotedTrace =
        prefix
            + ResearchCheckpointTestFixtures.marker(
                new ResearchCheckpointFrame(3, "quoted frame", List.of(quoted)));
    assertThat(new ResearchCheckpointFrameParser().parse(quotedTrace)).hasSize(1);

    String corrupt =
        ResearchCheckpointFrameParser.BEGIN_MARKER
            + "\nnot-json\n"
            + ResearchCheckpointFrameParser.END_MARKER;
    String candidates = String.join("\n", java.util.Collections.nCopies(20, corrupt));
    String valid =
        ResearchCheckpointTestFixtures.marker(
            ResearchCheckpointTestFixtures.frame(
                99,
                ResearchCheckpointTestFixtures.finding(
                    ResearchFindingKind.SHARP_OBSTRUCTION, "too late after cap")));
    assertThat(new ResearchCheckpointFrameParser().parse(candidates + "\n" + valid)).isEmpty();
  }

  @Test
  void ledgerCoversEveryDispositionWithoutGrantingAuthority() {
    ResearchCheckpointLedger ledger = new ResearchCheckpointLedger();
    ResearchCheckpointFrame frame =
        ResearchCheckpointTestFixtures.frame(
            0,
            finding(ResearchFindingKind.CANDIDATE_LEMMA, "lemma candidate", null),
            finding(
                ResearchFindingKind.COUNTEREXAMPLE_CANDIDATE,
                "targeted counterexample",
                "obligation-1"),
            finding(
                ResearchFindingKind.COUNTEREXAMPLE_CANDIDATE,
                "untargeted counterexample",
                null),
            finding(ResearchFindingKind.EXACT_EXAMPLE, "reject this example", null),
            finding(ResearchFindingKind.CONSTRUCTION_CANDIDATE, "defer construction", null),
            finding(ResearchFindingKind.REPRESENTATION_INSIGHT, "superseded insight", null),
            finding(ResearchFindingKind.REPRESENTATION_INSIGHT, "replacement insight", null),
            finding(ResearchFindingKind.NEXT_MICRO_OBLIGATION, "keep active", null));
    ledger.appendEnvelopeFrame("problem", "route", "independent_exploration", "call", frame);
    Map<String, ResearchFindingRecord> byStatement = new LinkedHashMap<>();
    ledger.findings().forEach(record -> byStatement.put(record.statement(), record));

    ledger.applyUpdates(
        "route",
        updates(
            disposition(
                byStatement.get("lemma candidate"),
                ResearchFindingDispositionAction.PROMOTE_TO_PROPOSED_LEMMA,
                null,
                null),
            disposition(
                byStatement.get("targeted counterexample"),
                ResearchFindingDispositionAction.PROMOTE_TO_COUNTEREXAMPLE_CANDIDATE,
                null,
                null),
            disposition(
                byStatement.get("reject this example"),
                ResearchFindingDispositionAction.REJECT_WITH_REASON,
                "not useful",
                null),
            disposition(
                byStatement.get("defer construction"),
                ResearchFindingDispositionAction.DEFER,
                "later",
                null),
            disposition(
                byStatement.get("superseded insight"),
                ResearchFindingDispositionAction.SUPERSEDE_WITH,
                null,
                byStatement.get("replacement insight").findingId()),
            disposition(
                byStatement.get("keep active"),
                ResearchFindingDispositionAction.KEEP_ACTIVE,
                null,
                null)));

    assertThat(ledger.finding(byStatement.get("lemma candidate").findingId()).status())
        .isEqualTo(ResearchFindingStatus.PROMOTED_TO_ATTEMPT_CANDIDATE);
    assertThat(ledger.finding(byStatement.get("targeted counterexample").findingId()).status())
        .isEqualTo(ResearchFindingStatus.PROMOTED_TO_ATTEMPT_CANDIDATE);
    assertThat(ledger.finding(byStatement.get("reject this example").findingId()).status())
        .isEqualTo(ResearchFindingStatus.REJECTED);
    assertThat(ledger.finding(byStatement.get("defer construction").findingId()).status())
        .isEqualTo(ResearchFindingStatus.DEFERRED);
    assertThat(ledger.finding(byStatement.get("superseded insight").findingId()).status())
        .isEqualTo(ResearchFindingStatus.SUPERSEDED);
    assertThat(ledger.applyUpdates("route", null)).isEmpty();

    assertThatThrownBy(
            () ->
                ledger.applyUpdates(
                    "route",
                    updates(
                        disposition(
                            byStatement.get("untargeted counterexample"),
                            ResearchFindingDispositionAction
                                .PROMOTE_TO_COUNTEREXAMPLE_CANDIDATE,
                            null,
                            null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact target");
    assertThatThrownBy(
            () ->
                ledger.applyUpdates(
                    "route",
                    updates(
                        disposition(
                            byStatement.get("reject this example"),
                            ResearchFindingDispositionAction.KEEP_ACTIVE,
                            null,
                            null))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot move");

    assertThat(ledger.deferRouteEnd("route")).hasSize(3);
    assertThat(ledger.deferRouteEnd("route")).isEmpty();
    assertThat(ledger.activeFindings("route")).isEmpty();
  }

  @Test
  void invalidUpdateBatchIsAtomic() {
    ResearchCheckpointLedger ledger = new ResearchCheckpointLedger();
    ledger.appendEnvelopeFrame(
        "problem",
        "route",
        "bridge_lemma",
        "call",
        ResearchCheckpointTestFixtures.frame(
            0,
            finding(ResearchFindingKind.EXACT_EXAMPLE, "first", null),
            finding(ResearchFindingKind.EXACT_EXAMPLE, "second", null)));
    List<ResearchFindingRecord> before = ledger.findings();
    List<ResearchFindingAuditEvent> auditBefore = ledger.audit();

    assertThatThrownBy(
            () ->
                ledger.applyUpdates(
                    "route",
                    updates(
                        disposition(
                            before.get(0),
                            ResearchFindingDispositionAction.DEFER,
                            "valid first transition",
                            null),
                        new ResearchFindingDisposition(
                            "missing",
                            ResearchFindingDispositionAction.KEEP_ACTIVE,
                            null,
                            null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown research finding");

    assertThat(ledger.findings()).isEqualTo(before);
    assertThat(ledger.audit()).isEqualTo(auditBefore);
  }

  @Test
  void invalidSupersedeAfterValidDispositionIsAtomic() {
    ResearchCheckpointLedger ledger = new ResearchCheckpointLedger();
    ledger.appendEnvelopeFrame(
        "problem",
        "route",
        "bridge_lemma",
        "call",
        ResearchCheckpointTestFixtures.frame(
            0,
            finding(ResearchFindingKind.EXACT_EXAMPLE, "first", null),
            finding(ResearchFindingKind.REPRESENTATION_INSIGHT, "second", null)));
    ResearchCheckpointLedger other = new ResearchCheckpointLedger();
    other.appendEnvelopeFrame(
        "problem",
        "other-route",
        "bridge_lemma",
        "other-call",
        ResearchCheckpointTestFixtures.frame(
            0, finding(ResearchFindingKind.REPRESENTATION_INSIGHT, "other", null)));
    ResearchFindingRecord first =
        ledger.findings().stream().filter(item -> item.statement().equals("first")).findFirst().orElseThrow();
    ResearchFindingRecord second =
        ledger.findings().stream().filter(item -> item.statement().equals("second")).findFirst().orElseThrow();
    ResearchFindingRecord foreign = other.findings().getFirst();
    Map<String, ResearchFindingRecord> combinedFindings = new LinkedHashMap<>(ledger.snapshot().findings());
    combinedFindings.put(foreign.findingId(), foreign);
    ledger =
        ResearchCheckpointLedger.restore(
            new ResearchCheckpointSnapshot(
                1, ledger.snapshot().checkpoints(), combinedFindings, ledger.audit()));
    List<ResearchFindingRecord> before = ledger.findings();
    List<ResearchFindingAuditEvent> auditBefore = ledger.audit();
    ResearchCheckpointLedger subject = ledger;

    assertThatThrownBy(
            () ->
                subject.applyUpdates(
                    "route",
                    updates(
                        disposition(
                            first,
                            ResearchFindingDispositionAction.DEFER,
                            "would otherwise mutate",
                            null),
                        disposition(
                            second,
                            ResearchFindingDispositionAction.SUPERSEDE_WITH,
                            null,
                            foreign.findingId()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("crossed problem or route boundary");
    assertThat(subject.findings()).isEqualTo(before);
    assertThat(subject.audit()).isEqualTo(auditBefore);
  }

  @Test
  void traceFramesRestoreAndCollisionChecksFailClosed() {
    String trace =
        ResearchCheckpointTestFixtures.marker(
            ResearchCheckpointTestFixtures.frame(
                2,
                ResearchCheckpointTestFixtures.finding(
                    ResearchFindingKind.SHARP_OBSTRUCTION, "trace obstruction")));
    ResearchCheckpointTraceSpan span = new ResearchCheckpointFrameParser().parse(trace).getFirst();
    ResearchCheckpointLedger ledger = new ResearchCheckpointLedger();
    assertThat(
            ledger.appendTraceFrames(
                "problem", "route", "bridge_lemma", "call", "trace-call", "trace-task", List.of(span)))
        .hasSize(1);
    assertThat(ledger.checkpointsForRoute("route")).hasSize(1);
    assertThat(ledger.audit()).hasSize(1);

    ResearchCheckpointSnapshot snapshot = ledger.snapshot();
    ResearchCheckpointRecord checkpoint = snapshot.checkpoints().values().iterator().next();
    ResearchFindingRecord finding = snapshot.findings().values().iterator().next();
    assertThatThrownBy(
            () ->
                ResearchCheckpointLedger.restore(
                    new ResearchCheckpointSnapshot(
                        1, Map.of("wrong", checkpoint), snapshot.findings(), snapshot.audit())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("checkpoint snapshot key mismatch");
    assertThatThrownBy(
            () ->
                ResearchCheckpointLedger.restore(
                    new ResearchCheckpointSnapshot(
                        1, snapshot.checkpoints(), Map.of("wrong", finding), snapshot.audit())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("finding snapshot key mismatch");

    ResearchCheckpointFrame changedRationale =
        new ResearchCheckpointFrame(
            2,
            "different frame hash",
            List.of(
                new ResearchFindingDraft(
                    ResearchFindingKind.SHARP_OBSTRUCTION,
                    "trace obstruction",
                    "different rationale",
                    List.of(),
                    List.of(),
                    null,
                    null,
                    null,
                    null,
                    null)));
    assertThatThrownBy(
            () ->
                ledger.appendEnvelopeFrame(
                    "problem", "route", "bridge_lemma", "call", changedRationale))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("finding ID collision");
  }

  @Test
  void recordsAndSnapshotsRejectMalformedPersistedState() {
    assertThatThrownBy(() -> new ResearchCheckpointSnapshot(-1, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ResearchCheckpointSnapshot(2, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new ResearchCheckpointSnapshot(1, null, null, null))
        .isEqualTo(ResearchCheckpointSnapshot.empty());
    assertThatThrownBy(
            () ->
                new ResearchFindingAuditEvent(
                    -1, "finding", "action", ResearchFindingStatus.ACTIVE, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ResearchFindingAuditEvent(
                    0, " ", "action", ResearchFindingStatus.ACTIVE, null, null))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                new ResearchCheckpointRecord(
                    "checkpoint",
                    "problem",
                    "route",
                    "stage",
                    "call",
                    " ",
                    " ",
                    -1,
                    "hash",
                    null,
                    null,
                    null,
                    List.of(),
                    "reasoning_trace"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                checkpointRecord(0, -1, null, "reasoning_trace"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("marker offsets");
    assertThatThrownBy(() -> checkpointRecord(2, 1, null, "reasoning_trace"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("marker offsets");
    assertThatThrownBy(() -> checkpointRecord(null, null, null, "unknown"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source");

    assertThatThrownBy(
            () ->
                new ResearchFindingRecord(
                    "finding",
                    "problem",
                    "route",
                    "stage",
                    "call",
                    "checkpoint",
                    -1,
                    ResearchFindingKind.EXACT_EXAMPLE,
                    "statement",
                    "normalized",
                    "rationale",
                    null,
                    null,
                    " ",
                    ResearchFindingStatus.ACTIVE,
                    " ",
                    " ",
                    0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void traceSpanRejectsMalformedOffsetsAndQuoteInputs() {
    String trace =
        ResearchCheckpointTestFixtures.marker(
            ResearchCheckpointTestFixtures.frame(
                0,
                ResearchCheckpointTestFixtures.finding(
                    ResearchFindingKind.EXACT_EXAMPLE, "span example")));
    ResearchCheckpointTraceSpan span = new ResearchCheckpointFrameParser().parse(trace).getFirst();
    assertThat(span.validatesAgainst(trace)).isTrue();
    assertThat(
            ResearchCheckpointTraceSpan.validatesExactQuote(
                null, 0, 0, "", CanonicalJson.stableHash("")))
        .isFalse();
    assertThat(ResearchCheckpointTraceSpan.validatesExactQuote(trace, -1, 0, "", "hash"))
        .isFalse();
    assertThat(
            ResearchCheckpointTraceSpan.validatesExactQuote(
                trace, 0, trace.length() + 1, trace, CanonicalJson.stableHash(trace)))
        .isFalse();
    assertThat(
            ResearchCheckpointTraceSpan.validatesExactQuote(
                trace, 0, 1, "not exact", CanonicalJson.stableHash("not exact")))
        .isFalse();
    assertThatThrownBy(
            () ->
                new ResearchCheckpointTraceSpan(
                    span.frame(),
                    -1,
                    span.markerEnd(),
                    span.jsonStart(),
                    span.jsonEnd(),
                    span.frameJsonSha256(),
                    span.markerSha256(),
                    span.traceSha256(),
                    span.traceCharacters()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("offsets");
  }

  private static ResearchFindingDraft finding(
      ResearchFindingKind kind, String statement, String target) {
    return new ResearchFindingDraft(
        kind,
        statement,
        "bounded rationale",
        List.of("assumption"),
        List.of("scope"),
        target,
        null,
        null,
        null,
        null);
  }

  private static ResearchFindingDisposition disposition(
      ResearchFindingRecord finding,
      ResearchFindingDispositionAction action,
      String reason,
      String supersededBy) {
    return new ResearchFindingDisposition(finding.findingId(), action, reason, supersededBy);
  }

  private static ResearchFindingUpdateBatch updates(
      ResearchFindingDisposition... dispositions) {
    return new ResearchFindingUpdateBatch(List.of(dispositions));
  }

  private static ResearchCheckpointRecord checkpointRecord(
      Integer markerStart, Integer markerEnd, Integer ignored, String source) {
    return new ResearchCheckpointRecord(
        "checkpoint",
        "problem",
        "route",
        "stage",
        "call",
        null,
        null,
        0,
        "hash",
        null,
        markerStart,
        markerEnd,
        List.of(),
        source);
  }
}
