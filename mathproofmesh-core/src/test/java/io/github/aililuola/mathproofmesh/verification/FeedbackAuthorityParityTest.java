package io.github.aililuola.mathproofmesh.verification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FeedbackAuthorityParityTest {

  @Test
  void strategy_feedback_is_explicitly_open_and_not_premise_eligible() {
    FeedbackDirective directive =
        FeedbackDirective.open(
            "required_action",
            "meta_review:3",
            "Exclude d=1 and derive the eventual recurrence.");

    assertThat(directive.authorityInstruction())
        .contains("NON-AUTHORITATIVE REVIEW DIRECTIVE")
        .contains("\"status\":\"open\"")
        .contains("\"premise_eligible\":false")
        .contains("still requires proof");
  }

  @Test
  void path_feedback_cannot_extend_a_verified_checkpoint() {
    FeedbackDirective directive =
        new FeedbackDirective(
            "required_action",
            "open_or_rejected",
            "checkpoint_review:1",
            false,
            "Exclude d=1.");

    assertThat(directive.authorityInstruction())
        .contains("must not extend a verified checkpoint")
        .contains("\"status\":\"open_or_rejected\"")
        .contains("\"premise_eligible\":false");
  }

  @Test
  void feedback_source_tag_is_machine_readable() {
    FeedbackDirective directive =
        FeedbackDirective.open(
            "required_action",
            "meta_review:3",
            "  Strictly   exclude d=1. ");

    assertThat(directive.tagged())
        .isEqualTo(
            "[required_action][STATUS:open][SOURCE:meta_review:3]"
                + "[PREMISE_ELIGIBLE:false] Strictly exclude d=1.");
  }

  @Test
  void continuation_preserves_exact_tagged_feedback_authority() {
    FeedbackDirective directive =
        FeedbackDirective.open(
            "required_action", "meta_review:3", "Exclude d=1.");
    String tagged = directive.tagged();
    FeedbackDirective continued =
        FeedbackDirective.open(
            "required_action", "meta_review:3", "Exclude d=1.");

    assertThat(continued.tagged()).isEqualTo(tagged);
    assertThat(continued.authorityInstruction())
        .contains("\"source\":\"meta_review:3\"")
        .contains("\"kind\":\"required_action\"")
        .contains("\"status\":\"open\"")
        .contains("\"text\":\"Exclude d=1.\"");
  }
}
