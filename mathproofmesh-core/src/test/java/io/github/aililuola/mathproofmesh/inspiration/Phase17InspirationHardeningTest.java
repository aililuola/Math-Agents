package io.github.aililuola.mathproofmesh.inspiration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.InspirationContextMode;
import io.github.aililuola.mathproofmesh.contract.InspirationMechanism;
import io.github.aililuola.mathproofmesh.contract.InspirationProposal;
import io.github.aililuola.mathproofmesh.contract.InspirationReview;
import io.github.aililuola.mathproofmesh.contract.NoveltySignature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Phase17InspirationHardeningTest {

  @Test
  void composerEnforcesIndependentReviewConnectivityCostAndComplementarity() {
    InspirationProposal construction =
        proposal("construction", "agent-a", InspirationMechanism.AUXILIARY_CONSTRUCTION, 2, "o1");
    InspirationProposal analogy =
        proposal("analogy", "agent-b", InspirationMechanism.STRUCTURAL_ANALOGY, 3, "o1");
    InspirationProposal duplicateMechanism =
        proposal("same", "agent-c", InspirationMechanism.AUXILIARY_CONSTRUCTION, 1, "o1");
    InspirationProposal disconnected =
        proposal("disconnected", "agent-d", InspirationMechanism.REPRESENTATION_SWITCH, 1, "o9");

    assertThat(
            new InspirationComposer(new InspirationPolicy.ComposerRules(0, 2, 10, false))
                .compose(List.of(construction, analogy), Map.of(), Set.of("o1")))
        .isEmpty();
    InspirationComposer composer =
        new InspirationComposer(new InspirationPolicy.ComposerRules(6, 3, 10, false));
    assertThat(composer.compose(null, null, null)).isEmpty();
    assertThat(composer.compose(List.of(construction), Map.of(), Set.of("o1"))).isEmpty();
    assertThat(
            composer.compose(
                List.of(construction, analogy),
                Map.of(construction.proposalId(), accepted(construction, "agent-a")),
                Set.of("o1")))
        .isEmpty();
    assertThat(
            composer.compose(
                List.of(construction, duplicateMechanism),
                Map.of(
                    construction.proposalId(), accepted(construction, "reviewer-a"),
                    duplicateMechanism.proposalId(), accepted(duplicateMechanism, "reviewer-c")),
                Set.of("o1")))
        .isEmpty();
    assertThat(
            composer.compose(
                List.of(construction, disconnected),
                Map.of(
                    construction.proposalId(), accepted(construction, "reviewer-a"),
                    disconnected.proposalId(), accepted(disconnected, "reviewer-d")),
                Set.of("o1")))
        .isEmpty();

    Map<String, InspirationReview> accepted =
        Map.of(
            construction.proposalId(), accepted(construction, "reviewer-a"),
            analogy.proposalId(), accepted(analogy, "reviewer-b"));
    var composed =
        composer.compose(List.of(construction, analogy), accepted, Set.of("o1")).orElseThrow();
    assertThat(composed.sourceProposalIds())
        .containsExactly(construction.proposalId(), analogy.proposalId());
    assertThat(composed.combinedMechanism()).containsExactlyInAnyOrder(
        construction.mechanism().value(), analogy.mechanism().value());
    assertThat(composed.targetObligationIds()).contains("o1");
    assertThat(composed.compositionId()).startsWith("composition_");

    assertThat(
            new InspirationComposer(new InspirationPolicy.ComposerRules(6, 3, 4, false))
                .compose(List.of(construction, analogy), accepted, Set.of("o1")))
        .isEmpty();
    assertThat(
            new InspirationComposer(new InspirationPolicy.ComposerRules(6, 3, 10, true))
                .compose(List.of(construction, analogy), accepted, Set.of("o1")))
        .isEmpty();
  }

  @Test
  void composerRejectsEveryReviewFailureMode() {
    InspirationProposal first =
        proposal("first", "author", InspirationMechanism.AUXILIARY_CONSTRUCTION, 1, "o1");
    InspirationProposal second =
        proposal("second", "other", InspirationMechanism.STRUCTURAL_ANALOGY, 1, "o1");
    InspirationComposer composer =
        new InspirationComposer(new InspirationPolicy.ComposerRules(4, 2, 10, false));
    InspirationReview goodSecond = accepted(second, "reviewer");

    List<InspirationReview> invalid =
        List.of(
            review(first, "author", "completed", "store_insight", true, true, true),
            review(first, "reviewer", "deferred", "store_insight", true, true, true),
            review(first, "reviewer", "completed", "reject", true, true, true),
            review(first, "reviewer", "completed", "store_insight", false, true, true),
            review(first, "reviewer", "completed", "store_insight", true, false, true),
            review(first, "reviewer", "completed", "store_insight", true, true, false));
    for (InspirationReview bad : invalid) {
      assertThat(
              composer.compose(
                  List.of(first, second),
                  Map.of(first.proposalId(), bad, second.proposalId(), goodSecond),
                  Set.of("o1")))
          .isEmpty();
    }
  }

  @Test
  void localAnalogyLibraryLoadsRanksBlocksAndDiagnoses(@TempDir Path temp) throws Exception {
    Path disabledPath = temp.resolve("disabled.jsonl");
    LocalAnalogyLibrary disabled = new LocalAnalogyLibrary(disabledPath, false);
    assertThat(disabled.diagnostics()).isEmpty();
    assertThat(disabled.verifiedSize()).isZero();

    LocalAnalogyLibrary missing =
        new LocalAnalogyLibrary(temp.resolve("missing.jsonl"), true);
    assertThat(missing.diagnostics()).singleElement().asString().contains("not found");

    Path libraryPath = temp.resolve("library.jsonl");
    Files.writeString(
        libraryPath,
        "\n"
            + "[]\n"
            + "{\"record_id\":\"r1\",\"verified\":true,"
            + "\"problem_summary\":\"integer graph invariant\","
            + "\"proof_summary\":\"induction invariant\","
            + "\"object_tags\":[\"integer\",\"integer\",\"\"],"
            + "\"operation_tags\":[\"induction\"],"
            + "\"mechanism_tags\":[\"bridge\"],"
            + "\"representation_tags\":[\"graph\"],"
            + "\"proof_principles\":[\"invariant\"],"
            + "\"graph_tags\":[\"path\"],"
            + "\"obligation_kinds\":[\"lemma\"],"
            + "\"mechanism_chain\":[\"reduce\"],"
            + "\"obligation_graph_motif\":[\"diamond\"],"
            + "\"object_correspondence\":{\"n\":\"vertex\",\"empty\":\"\"},"
            + "\"operation_correspondence\":{\"step\":\"edge\"},"
            + "\"transferable_lemmas\":[\"closure\"],"
            + "\"non_transferable_conditions\":[\"finite only\"],"
            + "\"transfer_risks\":[\"boundary\"],"
            + "\"required_bridge_lemmas\":[\"bridge\"]}\n"
            + "{\"record_id\":\"n1\",\"negative\":true,\"problem_hash\":\"p1\","
            + "\"source_record_id\":\"r1\",\"failure_reason\":\"scope mismatch\"}\n");
    LocalAnalogyLibrary library = new LocalAnalogyLibrary(libraryPath, true);
    assertThat(library.verifiedSize()).isEqualTo(1);
    assertThat(library.negativeSize()).isEqualTo(1);
    assertThat(library.diagnostics()).isEmpty();

    LocalAnalogyLibrary.Query query =
        new LocalAnalogyLibrary.Query(
            "integer graph invariant",
            List.of("integer"),
            List.of("induction"),
            List.of("bridge"),
            List.of("path"),
            List.of("lemma"),
            List.of("reduce"),
            List.of("diamond"));
    assertThat(library.search(query, "p1", 5)).isEmpty();
    assertThat(library.search(query, null, 5)).isEmpty();
    assertThat(library.search(query, "p2", 0)).isEmpty();
    assertThat(library.search(query, "p2", 5))
        .extracting(LocalAnalogyLibrary.Record::recordId)
        .containsExactly("r1");
    assertThat(
            library.search(
                new LocalAnalogyLibrary.Query(
                    "unrelated",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of()),
                "p2",
                5))
        .isEmpty();

    assertThat(library.addVerified(record("r1", true))).isFalse();
    assertThat(library.addVerified(record("r2", true))).isTrue();
    assertThat(library.addNegative(new LocalAnalogyLibrary.NegativeRecord(
            "n1", "", "r2", "duplicate")))
        .isFalse();
    assertThat(library.addNegative(new LocalAnalogyLibrary.NegativeRecord(
            "n2", "", "r2", "scope mismatch")))
        .isTrue();
    assertThatThrownBy(() -> library.addVerified(record("bad", false)))
        .isInstanceOf(IllegalArgumentException.class);

    Files.writeString(libraryPath, "{not-json}\n");
    library.reload();
    assertThat(library.verifiedSize()).isZero();
    assertThat(library.negativeSize()).isZero();
    assertThat(library.diagnostics()).singleElement().asString().contains("unavailable");
  }

  private static InspirationProposal proposal(
      String id,
      String author,
      InspirationMechanism mechanism,
      int cost,
      String obligation) {
    return new InspirationProposal(
        null,
        null,
        null,
        InspirationContextMode.WARM,
        cost,
        EvidenceType.UNVERIFIED_IDEA,
        0.8d,
        List.of(obligation),
        null,
        mechanism,
        null,
        0.9d,
        new NoveltySignature(
            List.of("integer"),
            List.of(),
            List.of("reduce"),
            List.of(mechanism.value()),
            null,
            null,
            null,
            List.of("induction"),
            Map.of(),
            List.of("graph"),
            List.of(obligation)),
        "proposal-" + id,
        0,
        "audited bounded proposal",
        null,
        null,
        author,
        "establish the open obligation",
        List.of("route-a"),
        "task-a",
        "trigger-a");
  }

  private static InspirationReview accepted(
      InspirationProposal proposal, String reviewer) {
    return review(proposal, reviewer, "completed", "store_insight", true, true, true);
  }

  private static InspirationReview review(
      InspirationProposal proposal,
      String reviewer,
      String status,
      String recommendation,
      boolean distinct,
      boolean relevant,
      boolean coherent) {
    return new InspirationReview(
        0.9d,
        status.equals("deferred") ? "awaiting evidence" : "",
        List.of(),
        List.of(),
        coherent,
        proposal.proposalId(),
        recommendation,
        relevant,
        null,
        status,
        reviewer,
        distinct);
  }

  private static LocalAnalogyLibrary.Record record(String id, boolean verified) {
    return new LocalAnalogyLibrary.Record(
        id,
        verified,
        "integer graph invariant",
        "induction proof",
        List.of("integer"),
        List.of("induction"),
        List.of("bridge"),
        List.of("graph"),
        List.of("invariant"),
        List.of("path"),
        List.of("lemma"),
        List.of("reduce"),
        List.of("diamond"),
        Map.of("n", "vertex"),
        Map.of("step", "edge"),
        List.of("closure"),
        List.of("finite only"),
        List.of("boundary"),
        List.of("bridge"));
  }
}
