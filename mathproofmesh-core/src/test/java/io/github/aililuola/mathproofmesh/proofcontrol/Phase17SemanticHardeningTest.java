package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ProblemSemanticViewCandidate;
import java.util.List;
import org.junit.jupiter.api.Test;

class Phase17SemanticHardeningTest {

  @Test
  void semanticProfilesCoverLanguagesIntentsQuantifiersDomainsRelationsAndPolarity() {
    SemanticProfileService service = new SemanticProfileService();

    var unknown = service.extract(null);
    assertThat(unknown.language()).isEqualTo("unknown");
    assertThat(unknown.concepts()).isEmpty();
    assertThat(unknown.taskIntents()).isEmpty();
    assertThat(unknown.polarities()).isEmpty();

    var rich =
        service.extract(
            "Prove that for every positive integer sequence, an adjacent mapping preserves "
                + "distance and order if and only if the representation is bounded.");
    assertThat(rich.language()).isEqualTo("en");
    assertThat(rich.taskIntents()).contains("prove");
    assertThat(rich.quantifiers()).contains("universal");
    assertThat(rich.domains()).contains("positive_integer");
    assertThat(rich.logicalRelations()).contains("equivalence");
    assertThat(rich.concepts())
        .contains("sequence", "adjacency", "mapping", "distance", "order", "representation");

    var negative =
        service.extract(
            "Disprove: there exists exactly one real number that is not bounded and cannot "
                + "preserve the relation.");
    assertThat(negative.taskIntents()).contains("disprove");
    assertThat(negative.quantifiers()).contains("exists_unique", "existential");
    assertThat(negative.domains()).contains("real_number");
    assertThat(negative.polarities()).contains("negative");

    var cardinality =
        service.extract(
            "Find at least two and at most five natural numbers; construct a periodic "
                + "monotone sequence and compute its value.");
    assertThat(cardinality.quantifiers()).contains("at_least", "at_most");
    assertThat(cardinality.taskIntents()).contains("find", "construct", "compute");
    assertThat(cardinality.concepts()).contains("periodicity", "monotonicity", "sequence");

    assertThat(service.audit("", ""))
        .allSatisfy(finding -> assertThat(finding.status()).isEqualTo("not_applicable"));
    assertThat(
            service.audit(
                "Prove every integer sequence is bounded",
                "Disprove some real mapping is unbounded"))
        .anySatisfy(finding -> assertThat(finding.status()).isEqualTo("fail"));
    assertThat(
            service.audit(
                "If $x$ is an integer then $x+1$ is an integer",
                "If $x$ is an integer then $x+1$ is an integer"))
        .noneSatisfy(finding -> assertThat(finding.status()).isEqualTo("fail"));

    assertThat(service.conservativelyMatchesAcrossLanguages("plain English", "other English"))
        .isFalse();
    service.conservativelyMatchesAcrossLanguages(
        "\u8bc1\u660e\u6bcf\u4e2a\u6574\u6570\u5e8f\u5217\u4fdd\u6301\u8ddd\u79bb\u548c\u6b21\u5e8f",
        "Prove every integer sequence preserves distance and order");
  }

  @Test
  void semanticViewAcceptsOnlyAuditedTranslationsWithProtectedMathematics() {
    ProblemSemanticViewService service = new ProblemSemanticViewService();
    String source = "\u8bc1\u660e $x^2 >= 0$.";
    ProblemSemanticViewCandidate accepted =
        new ProblemSemanticViewCandidate(
            0.95d,
            "Prove $x^2 >= 0$.",
            List.of("independent translation"),
            true,
            true,
            true,
            true);
    var view = service.build(source, accepted);
    assertThat(view.authoritative()).isFalse();
    assertThat(view.sourceLanguage()).isEqualTo("zh");
    assertThat(view.protectedFragments()).isNotEmpty();
    assertThat(view.missingProtectedFragments()).isEmpty();
    assertThat(view.status()).isEqualTo("usable");

    ProblemSemanticViewCandidate changed =
        new ProblemSemanticViewCandidate(
            0.60d,
            "Disprove $y^3 < 1$.",
            List.of(),
            false,
            false,
            false,
            false);
    var rejected = service.build(source, changed);
    assertThat(rejected.status()).isEqualTo("rejected");
    assertThat(rejected.missingProtectedFragments()).isNotEmpty();
    assertThat(rejected.notes())
        .anyMatch(note -> note.contains("protected mathematical fragments"))
        .anyMatch(note -> note.contains("semantic preservation check"));

    assertThat(
            ProblemSemanticViewService.protectedMathFragments(
                "$x+1$ and \\(y_2\\) and \\[z^3\\] use \\alpha with 12.5"))
        .contains("x", "1", "y_2", "z^3", "\\alpha", "12.5");
    assertThat(ProblemSemanticViewService.protectedMathFragments("words only")).isEmpty();
    assertThat(ProblemSemanticViewService.mathBlocks("$ x + 1 $ and \\( y \\)"))
        .containsExactly("$x+1$", "\\(y\\)");
  }
}
