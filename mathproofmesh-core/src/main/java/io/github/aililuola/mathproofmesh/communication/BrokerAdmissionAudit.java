package io.github.aililuola.mathproofmesh.communication;

public record BrokerAdmissionAudit(
    String messageId,
    boolean accepted,
    int terminalGate,
    AdmissionRejection rejection,
    String reason) {}
