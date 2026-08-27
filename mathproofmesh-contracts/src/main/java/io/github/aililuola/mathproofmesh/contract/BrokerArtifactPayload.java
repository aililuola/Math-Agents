package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "payload_type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = VerifiedClaimPayload.class, name = "verified_claim"),
    @JsonSubTypes.Type(value = VerifiedCounterexamplePayload.class, name = "verified_counterexample"),
    @JsonSubTypes.Type(value = VerifiedNoGoPayload.class, name = "verified_no_go"),
    @JsonSubTypes.Type(value = ReviewedObstructionPayload.class, name = "reviewed_obstruction"),
    @JsonSubTypes.Type(value = ReusableConstructionPayload.class, name = "reusable_construction"),
    @JsonSubTypes.Type(value = ExactExamplePayload.class, name = "exact_example"),
    @JsonSubTypes.Type(value = FormalCertificatePayload.class, name = "formal_certificate"),
    @JsonSubTypes.Type(value = BoundedObservationPayload.class, name = "bounded_observation")
})
public sealed interface BrokerArtifactPayload permits VerifiedClaimPayload,
    VerifiedCounterexamplePayload, VerifiedNoGoPayload, ReviewedObstructionPayload,
    ReusableConstructionPayload, ExactExamplePayload, FormalCertificatePayload,
    BoundedObservationPayload {
}
