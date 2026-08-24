package io.github.aililuola.mathproofmesh.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.agent.CallLedger;
import io.github.aililuola.mathproofmesh.agent.CheckpointedPromptBundle;
import io.github.aililuola.mathproofmesh.agent.CheckpointedStructuredCallResult;
import io.github.aililuola.mathproofmesh.agent.PromptFactory;
import io.github.aililuola.mathproofmesh.agent.ResearchCheckpointCapture;
import io.github.aililuola.mathproofmesh.agent.ResearchCheckpointFallbackEvidence;
import io.github.aililuola.mathproofmesh.agent.ResearchCheckpointedPromptFactory;
import io.github.aililuola.mathproofmesh.agent.ReasoningBudgetExhaustedError;
import io.github.aililuola.mathproofmesh.agent.StructuredAgentRunner;
import io.github.aililuola.mathproofmesh.agent.StructuredCallResult;
import io.github.aililuola.mathproofmesh.agent.StructuredOutputError;
import io.github.aililuola.mathproofmesh.api.RunApiModels.RouteView;
import io.github.aililuola.mathproofmesh.api.RunExecutionBackend;
import io.github.aililuola.mathproofmesh.api.SolveRequest;
import io.github.aililuola.mathproofmesh.communication.InMemoryMessageRepository;
import io.github.aililuola.mathproofmesh.communication.MessageDelivery;
import io.github.aililuola.mathproofmesh.communication.MessageStoreSnapshot;
import io.github.aililuola.mathproofmesh.communication.PromptDeliveryBatch;
import io.github.aililuola.mathproofmesh.communication.RouteRegistry;
import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactCompilationRequest;
import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactDeliverySnapshot;
import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactCompiler;
import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactEffectObservation;
import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactInvalidationSnapshot;
import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactPromptBatch;
import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactPublicationSnapshot;
import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactPublishResult;
import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactReceiptSnapshot;
import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactRegistrySnapshot;
import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactSourceKind;
import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactUseSnapshot;
import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactUtilitySnapshot;
import io.github.aililuola.mathproofmesh.communication.artifact.MathematicalArtifactBroker;
import io.github.aililuola.mathproofmesh.communication.artifact.RouteMathematicalNeedProfile;
import io.github.aililuola.mathproofmesh.computation.ComputationBroker;
import io.github.aililuola.mathproofmesh.computation.ComputationBroker.ComputationAudit;
import io.github.aililuola.mathproofmesh.computation.ComputationContext;
import io.github.aililuola.mathproofmesh.computation.ComputationEvidenceGate;
import io.github.aililuola.mathproofmesh.computation.ComputationExecutionState;
import io.github.aililuola.mathproofmesh.computation.ComputationExecutionContext;
import io.github.aililuola.mathproofmesh.computation.ComputationExecutionOutcome;
import io.github.aililuola.mathproofmesh.computation.ComputationExecutionFailurePoint;
import io.github.aililuola.mathproofmesh.computation.ComputationExecutionRecord;
import io.github.aililuola.mathproofmesh.computation.ComputationExecutionStatus;
import io.github.aililuola.mathproofmesh.computation.ComputationTargetBinding;
import io.github.aililuola.mathproofmesh.computation.ContractsFunctions;
import io.github.aililuola.mathproofmesh.computation.ToolBroker;
import io.github.aililuola.mathproofmesh.config.BudgetConfig;
import io.github.aililuola.mathproofmesh.config.GoalPreflightService;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.contract.ActionKind;
import io.github.aililuola.mathproofmesh.contract.AttemptStatus;
import io.github.aililuola.mathproofmesh.contract.BlindReviewPacket;
import io.github.aililuola.mathproofmesh.contract.BlindVerificationReport;
import io.github.aililuola.mathproofmesh.contract.BrokerDecision;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactEnvelope;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactReceiptStatus;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactType;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import io.github.aililuola.mathproofmesh.contract.BrokerBlockedInference;
import io.github.aililuola.mathproofmesh.contract.BrokerClaimSemanticContext;
import io.github.aililuola.mathproofmesh.contract.BrokerReusableConsequence;
import io.github.aililuola.mathproofmesh.contract.ReviewedObstructionPayload;
import io.github.aililuola.mathproofmesh.contract.VerifiedClaimPayload;
import io.github.aililuola.mathproofmesh.contract.VerifiedCounterexamplePayload;
import io.github.aililuola.mathproofmesh.contract.BudgetAction;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.CandidateAssessment;
import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import io.github.aililuola.mathproofmesh.contract.ClaimBlindAdjudicationBatch;
import io.github.aililuola.mathproofmesh.contract.ClaimBlindAdjudicationDecision;
import io.github.aililuola.mathproofmesh.contract.ClaimBlindAdjudicationVerdict;
import io.github.aililuola.mathproofmesh.contract.ClaimCounterexampleWitnessReviewBatch;
import io.github.aililuola.mathproofmesh.contract.ClaimCounterexampleWitnessReviewDecision;
import io.github.aililuola.mathproofmesh.contract.ClaimCourtOutcome;
import io.github.aililuola.mathproofmesh.contract.ClaimEvidenceSemanticBinding;
import io.github.aililuola.mathproofmesh.contract.ClaimMinimalRepairBatch;
import io.github.aililuola.mathproofmesh.contract.ClaimMinimalRepairDecision;
import io.github.aililuola.mathproofmesh.contract.ClaimMinimalRepairDisposition;
import io.github.aililuola.mathproofmesh.contract.ClaimProofAuditBatch;
import io.github.aililuola.mathproofmesh.contract.ClaimProofAuditDecision;
import io.github.aililuola.mathproofmesh.contract.ClaimProofAuditVerdict;
import io.github.aililuola.mathproofmesh.contract.ClaimProofPatch;
import io.github.aililuola.mathproofmesh.contract.ClaimReviewBatch;
import io.github.aililuola.mathproofmesh.contract.ClaimReviewDecision;
import io.github.aililuola.mathproofmesh.contract.ClaimSemanticContextBinding;
import io.github.aililuola.mathproofmesh.contract.ClaimStatementAssessment;
import io.github.aililuola.mathproofmesh.contract.ClaimStatementFalsificationBatch;
import io.github.aililuola.mathproofmesh.contract.ClaimStatementFalsificationDecision;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import io.github.aililuola.mathproofmesh.contract.ComputationDecision;
import io.github.aililuola.mathproofmesh.contract.ComputationDecisionStatus;
import io.github.aililuola.mathproofmesh.contract.ComputationContractRepair;
import io.github.aililuola.mathproofmesh.contract.ComputationContractRepairAction;
import io.github.aililuola.mathproofmesh.contract.ComputationContractRepairStatus;
import io.github.aililuola.mathproofmesh.contract.ComputationAuthorityMutationReceipt;
import io.github.aililuola.mathproofmesh.contract.ComputationDecisionAction;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.CriticalClaim;
import io.github.aililuola.mathproofmesh.contract.CriticalClaimContextBinding;
import io.github.aililuola.mathproofmesh.contract.CriticalClaimPreflightPlan;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.EvidenceRef;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.ExperimentProgram;
import io.github.aililuola.mathproofmesh.contract.ExperimentResult;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import io.github.aililuola.mathproofmesh.contract.FailureLevel;
import io.github.aililuola.mathproofmesh.contract.FinalProof;
import io.github.aililuola.mathproofmesh.contract.FormalizationCoverageReport;
import io.github.aililuola.mathproofmesh.contract.GoalNormalizationAssessment;
import io.github.aililuola.mathproofmesh.contract.GraphEdgeType;
import io.github.aililuola.mathproofmesh.contract.InitialExplorationAction;
import io.github.aililuola.mathproofmesh.contract.InitialExplorationTurn;
import io.github.aililuola.mathproofmesh.contract.InspirationAssignmentPlan;
import io.github.aililuola.mathproofmesh.contract.InspirationContextMode;
import io.github.aililuola.mathproofmesh.contract.InspirationMechanism;
import io.github.aililuola.mathproofmesh.contract.InspirationOutcome;
import io.github.aililuola.mathproofmesh.contract.InspirationProposal;
import io.github.aililuola.mathproofmesh.contract.InspirationProposalAssignment;
import io.github.aililuola.mathproofmesh.contract.InspirationTask;
import io.github.aililuola.mathproofmesh.contract.InspirationTrigger;
import io.github.aililuola.mathproofmesh.contract.InspirationTriggerType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.MetaDirectiveAction;
import io.github.aililuola.mathproofmesh.contract.MetaReview;
import io.github.aililuola.mathproofmesh.contract.NoveltySignature;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProblemContract;
import io.github.aililuola.mathproofmesh.contract.ProblemKind;
import io.github.aililuola.mathproofmesh.contract.ProofAttempt;
import io.github.aililuola.mathproofmesh.contract.ProofAuditIssue;
import io.github.aililuola.mathproofmesh.contract.ProofGraphEdge;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import io.github.aililuola.mathproofmesh.contract.ProofStep;
import io.github.aililuola.mathproofmesh.contract.ProofRepairability;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingDispositionAction;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingKind;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingUpdateBatch;
import io.github.aililuola.mathproofmesh.contract.RouteDescriptor;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import io.github.aililuola.mathproofmesh.contract.RouteStatus;
import io.github.aililuola.mathproofmesh.contract.SemanticPivotProposal;
import io.github.aililuola.mathproofmesh.contract.SemanticPivotReviewBatch;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.StrategyPreflightPlan;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import io.github.aililuola.mathproofmesh.contract.StatementCounterexampleCandidate;
import io.github.aililuola.mathproofmesh.contract.StatementFalsificationDisposition;
import io.github.aililuola.mathproofmesh.contract.TaskRequirement;
import io.github.aililuola.mathproofmesh.contract.TriageResult;
import io.github.aililuola.mathproofmesh.contract.ToolAuditReport;
import io.github.aililuola.mathproofmesh.contract.ToolRequest;
import io.github.aililuola.mathproofmesh.contract.UsageRecord;
import io.github.aililuola.mathproofmesh.contract.VerificationReport;
import io.github.aililuola.mathproofmesh.concurrency.ResearchEpochLedger;
import io.github.aililuola.mathproofmesh.concurrency.ResearchEpochCommitter;
import io.github.aililuola.mathproofmesh.concurrency.ResearchEpochCommitResult;
import io.github.aililuola.mathproofmesh.concurrency.ResearchEpochCommitStateMachine;
import io.github.aililuola.mathproofmesh.concurrency.ResearchEpochRecord;
import io.github.aililuola.mathproofmesh.concurrency.ResearchEpochCommitProtocolMigration;
import io.github.aililuola.mathproofmesh.concurrency.ResearchEpochSnapshot;
import io.github.aililuola.mathproofmesh.concurrency.ResearchEpochStatus;
import io.github.aililuola.mathproofmesh.concurrency.FrozenResearchSnapshot;
import io.github.aililuola.mathproofmesh.concurrency.ResearchAuthorityAnchor;
import io.github.aililuola.mathproofmesh.concurrency.ResearchAuthorityCommitProtocol;
import io.github.aililuola.mathproofmesh.concurrency.ResearchAuthorityMutationLedger;
import io.github.aililuola.mathproofmesh.concurrency.ResearchAuthorityMutationReceipt;
import io.github.aililuola.mathproofmesh.concurrency.ResearchAuthorityMutationSnapshot;
import io.github.aililuola.mathproofmesh.concurrency.ResearchAuthorityMutationTransaction;
import io.github.aililuola.mathproofmesh.concurrency.ResearchMergeReceipt;
import io.github.aililuola.mathproofmesh.concurrency.ResearchMergePlan;
import io.github.aililuola.mathproofmesh.concurrency.ResearchResultLedger;
import io.github.aililuola.mathproofmesh.concurrency.ResearchResultSnapshot;
import io.github.aililuola.mathproofmesh.concurrency.ResearchTaskLedger;
import io.github.aililuola.mathproofmesh.concurrency.ResearchTaskSnapshot;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkConflictSet;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkItem;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkKind;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkReadSet;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkResultEnvelope;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkResultStatus;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkStatus;
import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseClass;
import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseRequest;
import io.github.aililuola.mathproofmesh.contract.VerificationStage;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import io.github.aililuola.mathproofmesh.inspiration.InspirationAssignmentPlanner;
import io.github.aililuola.mathproofmesh.inspiration.InspirationEngine;
import io.github.aililuola.mathproofmesh.inspiration.InspirationMechanismRegistry;
import io.github.aililuola.mathproofmesh.inspiration.InspirationOutcomeLedger;
import io.github.aililuola.mathproofmesh.inspiration.InspirationPolicy;
import io.github.aililuola.mathproofmesh.inspiration.InspirationReferee;
import io.github.aililuola.mathproofmesh.inspiration.InspirationSnapshot;
import io.github.aililuola.mathproofmesh.inspiration.MechanismContextProfile;
import io.github.aililuola.mathproofmesh.inspiration.MetaDirectiveController;
import io.github.aililuola.mathproofmesh.inspiration.PersistentMetaStrategist;
import io.github.aililuola.mathproofmesh.inspiration.TriggerPolicy;
import io.github.aililuola.mathproofmesh.memory.LemmaMemory;
import io.github.aililuola.mathproofmesh.memory.LemmaMemorySnapshot;
import io.github.aililuola.mathproofmesh.memory.GreedyGcdNegativeKnowledgeSeeds;
import io.github.aililuola.mathproofmesh.memory.MemoryPolicy;
import io.github.aililuola.mathproofmesh.memory.NegativeCandidateIntent;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeAdmissionGate;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeBlockedException;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeCandidate;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeDecision;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeKind;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeRegistry;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeRecord;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeSurface;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeTargetType;
import io.github.aililuola.mathproofmesh.memory.VerifiedCounterexampleAuthority;
import io.github.aililuola.mathproofmesh.memory.TypedMemory;
import io.github.aililuola.mathproofmesh.memory.TypedMemorySnapshot;
import io.github.aililuola.mathproofmesh.orchestration.AdaptiveBudgetManager;
import io.github.aililuola.mathproofmesh.orchestration.AttemptEvidence;
import io.github.aililuola.mathproofmesh.orchestration.BudgetActionCandidate;
import io.github.aililuola.mathproofmesh.orchestration.BudgetBucket;
import io.github.aililuola.mathproofmesh.orchestration.BudgetEnvelope;
import io.github.aililuola.mathproofmesh.orchestration.BudgetStateSnapshot;
import io.github.aililuola.mathproofmesh.orchestration.ContinuationFunctions;
import io.github.aililuola.mathproofmesh.orchestration.DeepExplorationRegistry;
import io.github.aililuola.mathproofmesh.orchestration.ExplorationAdmission;
import io.github.aililuola.mathproofmesh.orchestration.ExplorationEvidence;
import io.github.aililuola.mathproofmesh.orchestration.ExplorationModel;
import io.github.aililuola.mathproofmesh.orchestration.ExplorationOutcome;
import io.github.aililuola.mathproofmesh.orchestration.ExplorationSignature;
import io.github.aililuola.mathproofmesh.orchestration.EvidenceAwareBudgetDecision;
import io.github.aililuola.mathproofmesh.orchestration.PathBudgetStats;
import io.github.aililuola.mathproofmesh.orchestration.RoutePipelineFunctions;
import io.github.aililuola.mathproofmesh.orchestration.SynthesisPhaseService;
import io.github.aililuola.mathproofmesh.orchestration.TargetMechanismKey;
import io.github.aililuola.mathproofmesh.orchestration.teams.RiskAssessment;
import io.github.aililuola.mathproofmesh.orchestration.teams.RoleAssignment;
import io.github.aililuola.mathproofmesh.orchestration.teams.RoleRunner;
import io.github.aililuola.mathproofmesh.orchestration.teams.RouteTeam;
import io.github.aililuola.mathproofmesh.orchestration.teams.RouteTeamFactory;
import io.github.aililuola.mathproofmesh.orchestration.teams.RouteTeamPlan;
import io.github.aililuola.mathproofmesh.orchestration.teams.RouteTeamResult;
import io.github.aililuola.mathproofmesh.proofcontrol.DependencyResolver;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactHarvester;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactKind;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactLedger;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactRecord;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactSnapshot;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactStatus;
import io.github.aililuola.mathproofmesh.proofcontrol.ClaimLifecycleController;
import io.github.aililuola.mathproofmesh.proofcontrol.ClaimLifecycleSnapshot;
import io.github.aililuola.mathproofmesh.proofcontrol.ExactGoalContractChecker;
import io.github.aililuola.mathproofmesh.proofcontrol.FailureControlService;
import io.github.aililuola.mathproofmesh.proofcontrol.MetaPivotController;
import io.github.aililuola.mathproofmesh.proofcontrol.LocalRepairApplyReceipt;
import io.github.aililuola.mathproofmesh.proofcontrol.LocalRepairPlan;
import io.github.aililuola.mathproofmesh.proofcontrol.MathematicalObjectChange;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotAssumptionChange;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotAuthorityContext;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotClaimUsageAction;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotCompilationException;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotDelta;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotDeltaStatus;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotDirectionChange;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotEvidenceAuthority;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotObjectDisposition;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotObligationAction;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotObligationChange;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotObstructionRef;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotProposedClaimDraft;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotStructuralSignature;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotStructuralSignatureFactory;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotTransformationType;
import io.github.aililuola.mathproofmesh.proofcontrol.SemanticPivotApplyPlan;
import io.github.aililuola.mathproofmesh.proofcontrol.SemanticPivotApplyReceipt;
import io.github.aililuola.mathproofmesh.proofcontrol.SemanticPivotCompiler;
import io.github.aililuola.mathproofmesh.proofcontrol.SemanticPivotController;
import io.github.aililuola.mathproofmesh.proofcontrol.SemanticPivotRecord;
import io.github.aililuola.mathproofmesh.proofcontrol.SemanticPivotSnapshot;
import io.github.aililuola.mathproofmesh.proofcontrol.StrategyRevisionKind;
import io.github.aililuola.mathproofmesh.proofcontrol.NearMissLedger;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlFacade;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofIdentity;
import io.github.aililuola.mathproofmesh.proofcontrol.ProblemSemanticViewService;
import io.github.aililuola.mathproofmesh.proofcontrol.RootGoalContract;
import io.github.aililuola.mathproofmesh.runstate.RunStateAnchor;
import io.github.aililuola.mathproofmesh.proofcontrol.StrategyArchive;
import io.github.aililuola.mathproofmesh.proofcontrol.StrategyBlueprintCompiler;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimBlindReviewPacket;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimBlindReviewPacketFactory;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtConfig;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtLedger;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtRecord;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtRolePolicy;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtSemanticContextCompiler;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtSnapshot;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtStage;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtStageExecutionLedger;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtStageExecutionRecord;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtStageExecutionSnapshot;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtStageExecutionStatus;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimFreezeService;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimProofPatchValidator;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimProofRevisionLedger;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimProofRevisionRecord;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimProofRevisionStatus;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimProofRevisionSnapshot;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimRefutationEvidence;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimRefutationEvidenceType;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimStatementAuthorityService;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimTrustedEvidenceAuthority;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.FrozenClaimSemanticContext;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.FrozenClaimSnapshot;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.TrustedClaimEvidence;
import io.github.aililuola.mathproofmesh.proofgraph.BottleneckFamilyRecord;
import io.github.aililuola.mathproofmesh.proofgraph.BottleneckRelationType;
import io.github.aililuola.mathproofmesh.proofgraph.CanonicalObligationRecord;
import io.github.aililuola.mathproofmesh.proofgraph.CanonicalObligationStatus;
import io.github.aililuola.mathproofmesh.proofgraph.CanonicalizedObligationWriteResult;
import io.github.aililuola.mathproofmesh.proofgraph.CanonicalObligationSchedulingState;
import io.github.aililuola.mathproofmesh.proofgraph.CanonicalSchedulingTransitionCode;
import io.github.aililuola.mathproofmesh.proofgraph.DeferredExpansionLedger;
import io.github.aililuola.mathproofmesh.proofgraph.DeferredExpansionReactivationCandidate;
import io.github.aililuola.mathproofmesh.proofgraph.DeferredExpansionReactivationDecision;
import io.github.aililuola.mathproofmesh.proofgraph.DeferredExpansionReactivationOutcome;
import io.github.aililuola.mathproofmesh.proofgraph.DeferredExpansionReactivationPlanner;
import io.github.aililuola.mathproofmesh.proofgraph.DeferredExpansionRecord;
import io.github.aililuola.mathproofmesh.proofgraph.FocusedExpansionDecision;
import io.github.aililuola.mathproofmesh.proofgraph.FocusedRecoveryActionType;
import io.github.aililuola.mathproofmesh.proofgraph.FocusedRecoveryBrief;
import io.github.aililuola.mathproofmesh.proofgraph.FocusedRecoveryPlan;
import io.github.aililuola.mathproofmesh.proofgraph.ObligationCreationContext;
import io.github.aililuola.mathproofmesh.proofgraph.ObligationOccurrenceSchedulingState;
import io.github.aililuola.mathproofmesh.proofgraph.ObligationSourceType;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphControlMode;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphConvergenceConfig;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphConvergenceMonitor;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphPolicy;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphServices;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphStore;
import io.github.aililuola.mathproofmesh.proofgraph.ProofTaskScope;
import io.github.aililuola.mathproofmesh.research.ResearchCheckpointLedger;
import io.github.aililuola.mathproofmesh.research.ResearchCheckpointRecord;
import io.github.aililuola.mathproofmesh.research.ResearchCheckpointSnapshot;
import io.github.aililuola.mathproofmesh.research.ResearchFindingRecord;
import io.github.aililuola.mathproofmesh.research.ResearchFindingStatus;
import io.github.aililuola.mathproofmesh.provider.UsageTotals;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphSnapshot;
import io.github.aililuola.mathproofmesh.provider.AgentPool;
import io.github.aililuola.mathproofmesh.provider.AgentLease;
import io.github.aililuola.mathproofmesh.provider.AgentCallFailure;
import io.github.aililuola.mathproofmesh.provider.AgentRuntime;
import io.github.aililuola.mathproofmesh.provider.ProviderErrorKind;
import io.github.aililuola.mathproofmesh.topology.SparseTopologyRouter;
import io.github.aililuola.mathproofmesh.strategydiversity.CommonModeRiskRegistry;
import io.github.aililuola.mathproofmesh.strategydiversity.CriticalClaimContext;
import io.github.aililuola.mathproofmesh.strategydiversity.CriticalClaimContextCompiler;
import io.github.aililuola.mathproofmesh.strategydiversity.CriticalClaimKeyCompiler;
import io.github.aililuola.mathproofmesh.strategydiversity.CriticalClaimPreflightEvidence;
import io.github.aililuola.mathproofmesh.strategydiversity.CriticalClaimPreflightStatus;
import io.github.aililuola.mathproofmesh.strategydiversity.GenericStrategyGenerationPolicy;
import io.github.aililuola.mathproofmesh.strategydiversity.PortfolioReplenishmentLedger;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyCandidateLedger;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyCandidateStatus;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyCriticalClaimPreflight;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyDiversityConfig;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyFeasibilityCalibrator;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyMechanismAnalyzer;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyMechanismPrimitive;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyMechanismProfile;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyMechanismRegistry;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyMechanismSignature;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPortfolioApplyPlan;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPortfolioApplyReceipt;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPortfolioAuditEvent;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPortfolioCandidate;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPortfolioConstraint;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPortfolioDecision;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPortfolioOptimizer;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPortfolioRegistry;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPreflightRegistry;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPreflightExecutionRecord;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPreflightExecutionStatus;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPreflightPlanCompiler;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPreflightReport;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategySemanticNormalizer;
import io.github.aililuola.mathproofmesh.strategydiversity.TrustedStrategyPreflightEvidenceSource;
import io.github.aililuola.mathproofmesh.agent.StrategyPreflightPlanValidator;
import io.github.aililuola.mathproofmesh.verification.BlindReviewPacketFactory;
import io.github.aililuola.mathproofmesh.verification.EscalationPlan;
import io.github.aililuola.mathproofmesh.verification.FormalizationCoverage;
import io.github.aililuola.mathproofmesh.verification.ValidationEscalationPolicy;
import io.github.aililuola.mathproofmesh.verification.ValidationEscalator;
import io.github.aililuola.mathproofmesh.verification.ValidationEscalationExecutor;
import io.github.aililuola.mathproofmesh.verification.ValidationExecution;
import io.github.aililuola.mathproofmesh.verification.ValidationLevel;
import io.github.aililuola.mathproofmesh.verification.ValidationStepResult;
import io.github.aililuola.mathproofmesh.verification.VerificationPipeline;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Executes every migrated proof-control phase on the live desktop path. */
final class DesktopSolveCoordinator {
  private static final int MAX_REPORT_BYTES = 700_000;
  private static final int CHECKPOINT_MOVE_ATTEMPTS = 5;
  private static final long CHECKPOINT_MOVE_RETRY_MILLIS = 25L;
  private static final int STATE_SCHEMA_VERSION = DesktopSolveCheckpoint.CURRENT_SCHEMA_VERSION;
  private static final int INSPIRATION_NO_GAIN_ROUNDS = 2;
  private static final int INSPIRATION_COOLDOWN_ROUNDS = 2;
  private static final String MAIN_GOAL_ID = "main-goal";
  private static final String LEGACY_INCOMPLETE_SEMANTIC_CONTEXT =
      "LEGACY_INCOMPLETE_SEMANTIC_CONTEXT";
  private static final String CAMPAIGN_RESEARCH_ROUTE_ID = "campaign-research";
  private static final String CURSOR_FREEZE = "freeze_problem";
  private static final String CURSOR_TRIAGE = "triage";
  private static final String CURSOR_STRATEGY = "strategy_diversity";
  private static final String CURSOR_INITIAL_ROUTES = "initial_routes";
  private static final String CURSOR_EXPLORE = "isolated_exploration";
  private static final String CURSOR_INTEGRATE = "integrate_routes";
  private static final String CURSOR_BROKER = "cross_route_broker";
  private static final String CURSOR_SCHEDULER_INSPIRATION = "scheduler_inspiration";
  private static final String CURSOR_SCHEDULER_META = "scheduler_meta_review";
  private static final String CURSOR_SCHEDULER_DECISION = "scheduler_decision";
  private static final String CURSOR_SCHEDULER_EXPLORE = "scheduler_exploration";
  private static final String CURSOR_SCHEDULER_INTEGRATE = "scheduler_integration";
  private static final String CURSOR_SCHEDULER_BROKER = "scheduler_broker";
  private static final String CURSOR_SYNTHESIS = "synthesis";
  private static final String CURSOR_FINAL_REVIEW = "blind_final_review";
  private static final String CURSOR_TERMINAL = "terminal";

  private final SolveRequest request;
  private final String runId;
  private final Path runDirectory;
  private final DesktopLiveRuntimeFactory.PreparedRuntime runtime;
  private final SystemConfig config;
  private final StructuredAgentRunner runner;
  private final PromptFactory prompts;
  private final ResearchCheckpointedPromptFactory checkpointedPrompts =
      new ResearchCheckpointedPromptFactory();
  private final AgentPool pool;
  private final CallLedger ledger;
  private final ComputationBroker computation;
  private final boolean sandboxEnabled;
  private final RunExecutionBackend.ProgressSink progress;
  private final String problemHash;

  private final List<ComputationTrace> computationTraces =
      Collections.synchronizedList(new ArrayList<>());
  private final List<RouteState> routes = new ArrayList<>();
  private final List<InspirationProposal> inspirationProposals = new ArrayList<>();
  private final List<InspirationOutcome> inspirationOutcomes = new ArrayList<>();
  private final List<MetaPivotController.Pivot> metaPivots = new ArrayList<>();
  private final List<ComputationAudit> computationAudits = new ArrayList<>();
  private final List<Double> proofDebtHistory = new ArrayList<>();
  private final List<DesktopSolveCheckpoint.ScheduledProofTask> pendingProofTasks =
      new ArrayList<>();
  private final LinkedHashSet<String> completedStages = new LinkedHashSet<>();
  private final Map<String, StrategyBlueprintCompiler.Compilation> strategyBlueprints =
      new LinkedHashMap<>();
  private final Map<String, ProofControlModels.GoalLink> goalLinks = new LinkedHashMap<>();
  private StrategyDiversityConfig strategyDiversityConfig = StrategyDiversityConfig.defaults();
  private final StrategyMechanismAnalyzer strategyMechanismAnalyzer =
      new StrategyMechanismAnalyzer();
  private final CriticalClaimContextCompiler criticalClaimContextCompiler =
      new CriticalClaimContextCompiler();
  private final StrategyPortfolioOptimizer strategyPortfolioOptimizer =
      new StrategyPortfolioOptimizer();
  private final StrategyPreflightPlanCompiler strategyPreflightPlanCompiler =
      new StrategyPreflightPlanCompiler();
  private final StrategyPreflightPlanValidator strategyPreflightPlanValidator =
      new StrategyPreflightPlanValidator();
  private StrategyCandidateLedger strategyCandidates = new StrategyCandidateLedger();
  private StrategyMechanismRegistry strategyMechanisms = new StrategyMechanismRegistry();
  private StrategyPreflightRegistry strategyPreflights = new StrategyPreflightRegistry();
  private StrategyPortfolioRegistry strategyPortfolios = new StrategyPortfolioRegistry();
  private PortfolioReplenishmentLedger portfolioReplenishments =
      new PortfolioReplenishmentLedger();
  private StrategyPortfolioFailurePoint strategyPortfolioFailurePoint =
      StrategyPortfolioFailurePoint.NONE;
  private StrategyPortfolioFailurePoint strategyPortfolioHardCrashPoint =
      StrategyPortfolioFailurePoint.NONE;
  private StrategyPreflightFailurePoint strategyPreflightHardCrashPoint =
      StrategyPreflightFailurePoint.NONE;
  private final SemanticPivotCompiler semanticPivotCompiler = new SemanticPivotCompiler();
  private final PivotStructuralSignatureFactory pivotSignatures =
      new PivotStructuralSignatureFactory();
  private final AtomicInteger activitySequence = new AtomicInteger();

  private ProblemContract frozenProblem;
  private TriageResult triage;
  private StrategySet strategySet;
  private List<StrategyCard> admittedStrategies = List.of();
  private final AtomicInteger nextStrategyIndex = new AtomicInteger();
  private final AtomicInteger roundIndex = new AtomicInteger();
  private String workflowCursor = CURSOR_FREEZE;
  private DesktopSolveCheckpoint.InspirationRoundProgress inspirationProgress;
  private MetaReview pendingMetaReview;
  private RootGoalContract rootGoal;
  private DesktopSolveCheckpoint.SchedulerStop schedulerStop;
  private FinalProof finalProof;
  private VerificationReport finalReview;
  private final List<VerificationReport> finalReviewReports = new ArrayList<>();
  private volatile boolean finalValidationPassed;
  private ValidationExecution finalValidationExecution;
  private FormalizationCoverageReport formalizationCoverage;
  private String currentStage = "goal_preflight";
  private RunStateAnchor runStateAnchor = RunStateAnchor.empty();

  private final SparseTopologyRouter topology = new SparseTopologyRouter();
  private final ContinuationFunctions.CheckpointLedger checkpoints =
      new ContinuationFunctions.CheckpointLedger();
  private final DeepExplorationRegistry deepExploration = new DeepExplorationRegistry();
  private LemmaMemory lemmaMemory = new LemmaMemory();
  private final AttemptArtifactHarvester attemptArtifactHarvester =
      new AttemptArtifactHarvester();
  private AttemptArtifactLedger attemptArtifacts = new AttemptArtifactLedger();
  private final ClaimFreezeService claimFreezeService = new ClaimFreezeService();
  private final ClaimCourtSemanticContextCompiler claimCourtSemanticContexts =
      new ClaimCourtSemanticContextCompiler();
  private final ClaimStatementAuthorityService claimStatementAuthority =
      new ClaimStatementAuthorityService();
  private final ClaimCourtRolePolicy claimCourtRolePolicy = new ClaimCourtRolePolicy();
  private final ClaimBlindReviewPacketFactory claimBlindPackets =
      new ClaimBlindReviewPacketFactory();
  private final ClaimCourtConfig claimCourtConfig = ClaimCourtConfig.defaults();
  private ClaimProofRevisionLedger claimProofRevisions = new ClaimProofRevisionLedger();
  private ClaimCourtLedger claimCourt = new ClaimCourtLedger();
  private ClaimCourtStageExecutionLedger claimCourtExecutions =
      new ClaimCourtStageExecutionLedger();
  private ClaimCourtFailurePoint claimCourtFailurePoint = ClaimCourtFailurePoint.NONE;
  private ClaimCourtFailurePoint claimCourtHardCrashPoint = ClaimCourtFailurePoint.NONE;
  private ResearchCheckpointLedger researchCheckpoints = new ResearchCheckpointLedger();
  private final ResearchEpochLedger researchEpochs = new ResearchEpochLedger();
  private final ResearchTaskLedger researchTasks = new ResearchTaskLedger();
  private final ResearchResultLedger researchResults = new ResearchResultLedger();
  private final ResearchEpochCommitter researchEpochCommitter = new ResearchEpochCommitter();
  private final ResearchAuthorityMutationLedger researchAuthorityMutations =
      new ResearchAuthorityMutationLedger();
  private final ResearchEpochCommitStateMachine researchEpochCommitStateMachine =
      new ResearchEpochCommitStateMachine();
  private final Set<String> restorablePreparedEpochIds = new LinkedHashSet<>();
  private final ThreadLocal<ResearchWorkerContext> activeResearchWorker = new ThreadLocal<>();
  private final ThreadLocal<ClaimCourtWorkerContext> activeClaimCourtWorker = new ThreadLocal<>();
  private final ThreadLocal<EpochAuthorityCommitContext> activeEpochAuthorityCommit =
      new ThreadLocal<>();
  private final java.util.concurrent.atomic.AtomicLong directWorkerAuthorityMutations =
      new java.util.concurrent.atomic.AtomicLong();
  private AuthoritativeConcurrencyFailurePoint authoritativeConcurrencyFailurePoint =
      AuthoritativeConcurrencyFailurePoint.NONE;
  private TypedMemory typedMemory;
  private ProofGraphStore proofGraph;
  private ProofGraphConvergenceMonitor proofGraphConvergence =
      new ProofGraphConvergenceMonitor(ProofGraphConvergenceConfig.defaults());
  private DeferredExpansionLedger deferredExpansions = new DeferredExpansionLedger();
  private final DeferredExpansionReactivationPlanner deferredReactivationPlanner =
      new DeferredExpansionReactivationPlanner();
  private DeferredReactivationFailurePoint deferredReactivationFailurePoint =
      DeferredReactivationFailurePoint.NONE;
  private SemanticPivotFailurePoint semanticPivotFailurePoint = SemanticPivotFailurePoint.NONE;
  private SemanticPivotFailurePoint semanticPivotHardCrashPoint =
      SemanticPivotFailurePoint.NONE;
  private NegativeKnowledgeRegistry negativeKnowledgeRegistry;
  private NegativeKnowledgeAdmissionGate negativeKnowledgeGate;
  private final ProofControlFacade proofControl = ProofControlFacade.createDefault();
  private final SemanticPivotController semanticPivots = proofControl.semanticPivots();
  private final ExactGoalContractChecker exactGoalContractChecker =
      new ExactGoalContractChecker(proofControl.scopeGuard());
  private final ProblemSemanticViewService semanticViewService =
      new ProblemSemanticViewService(exactGoalContractChecker);
  private final FailureControlService failureControl = new FailureControlService();
  private final NearMissLedger nearMisses = new NearMissLedger();
  private final StrategyArchive strategyArchive = new StrategyArchive();
  private final SynthesisPhaseService synthesisPhase = new SynthesisPhaseService();
  private final RouteTeam routeTeam;
  private final RoleRunner roleRunner;
  private final RouteTeamFactory teamFactory;
  private RouteRegistry routeRegistry;
  private InMemoryMessageRepository messageRepository = new InMemoryMessageRepository();
  private final MathematicalArtifactBroker mathematicalArtifactBroker =
      new MathematicalArtifactBroker();
  private final BrokerArtifactCompiler brokerArtifactCompiler = new BrokerArtifactCompiler();
  private final DesktopBudgetRuntime budgetRuntime;
  private final BudgetHost budgetHost = new BudgetHost();
  private final DesktopBudgetScheduler budgetScheduler;
  private final AdaptiveBudgetManager adaptiveBudget;
  private final InspirationPolicy inspirationPolicy;
  private final InspirationMechanismRegistry inspirationRegistry;
  private final TriggerPolicy inspirationTriggers;
  private final InspirationAssignmentPlanner inspirationAssignments;
  private final InspirationOutcomeLedger inspirationLedger;
  private final InspirationEngine inspirationEngine;
  private final PersistentMetaStrategist metaStrategist;
  private final MetaDirectiveController metaDirectives;

  DesktopSolveCoordinator(
      SolveRequest request,
      String runId,
      Path runDirectory,
      DesktopLiveRuntimeFactory.PreparedRuntime runtime,
      StructuredAgentRunner runner,
      PromptFactory prompts,
      AgentPool pool,
      CallLedger ledger,
      ComputationBroker computation,
      boolean sandboxEnabled,
      RunExecutionBackend.ProgressSink progress,
      String problemHash) {
    this.request = Objects.requireNonNull(request, "request");
    this.runId = Objects.requireNonNull(runId, "runId");
    this.runDirectory = Objects.requireNonNull(runDirectory, "runDirectory");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.config = runtime.config();
    this.runner = Objects.requireNonNull(runner, "runner");
    this.prompts = Objects.requireNonNull(prompts, "prompts");
    this.pool = Objects.requireNonNull(pool, "pool");
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.computation = Objects.requireNonNull(computation, "computation");
    this.sandboxEnabled = sandboxEnabled;
    this.progress = Objects.requireNonNull(progress, "progress");
    this.problemHash = Objects.requireNonNull(problemHash, "problemHash");

    double factThreshold = config.topology().typedMemory().factPassThreshold();
    this.typedMemory =
        new TypedMemory(
            new MemoryPolicy(
                factThreshold,
                config.topology().typedMemory().maxFactContext(),
                config.topology().typedMemory().maxInsightContext(),
                config.topology().typedMemory().maxNegativeContext()));
    this.proofGraph = new ProofGraphStore(problemHash, ProofGraphPolicy.defaults());
    installNegativeKnowledgeRuntime();
    this.routeRegistry =
        new RouteRegistry(
            problemHash,
            config.topology().crossRoute().maxNeighborsPerRoute(),
            8,
            config.topology().strategySimilarityThreshold());
    this.routeTeam = new RouteTeam(config.topology().routeTeams().skepticRiskThreshold());
    this.roleRunner = new RoleRunner(roleCandidates(pool.agents()));
    this.teamFactory = new RouteTeamFactory(roleRunner);
    this.budgetRuntime = new DesktopBudgetRuntime(runId, config);
    this.budgetScheduler = new DesktopBudgetScheduler(runId, budgetRuntime, runner, budgetHost);
    this.adaptiveBudget = budgetRuntime.manager();
    this.runner.configureBudgetEnvelopeLedger(budgetRuntime::envelopes);
    this.runner.configureBudgetPricing(budgetRuntime.pricing());

    this.inspirationPolicy = inspirationPolicy(config);
    this.inspirationRegistry =
        new InspirationMechanismRegistry(inspirationPolicy.enabledMechanisms());
    this.inspirationTriggers =
        new TriggerPolicy(
            inspirationPolicy,
            inspirationRegistry,
            new TriggerPolicy.TriggerRules(
                config.topology().inspiration().stagnationRounds(),
                config.topology().inspiration().minimumVerifiedGain(),
                config.topology().inspiration().proofDebtMinReduction(),
                config.topology().inspiration().repeatedErrorThreshold(),
                config.topology().inspiration().routeRedundancyTrigger()));
    this.inspirationAssignments = new InspirationAssignmentPlanner(inspirationPolicy);
    this.inspirationLedger = new InspirationOutcomeLedger(inspirationPolicy.adaptive());
    this.inspirationEngine =
        new InspirationEngine(
            inspirationPolicy,
            inspirationRegistry,
            new InspirationReferee(inspirationPolicy),
            new MechanismContextProfile(inspirationPolicy.limits()));
    this.metaStrategist =
        new PersistentMetaStrategist(TriggerPolicy.TriggerRules.defaults());
    this.metaDirectives =
        new MetaDirectiveController(inspirationPolicy, routeControls(config.budget().maxPaths()));
  }

  List<ResearchWorkResultEnvelope> executeFrozenResearchEpoch(
      FrozenResearchSnapshot snapshot,
      List<ResearchWorkItem> workItems,
      DesktopResearchEpochExecutor.Worker worker) {
    DesktopResearchEpochExecutor executor =
        new DesktopResearchEpochExecutor(
            runId,
            pool,
            DesktopDurableBoundaryObserver.from(progress).maximumResearchInFlight(
                config.concurrency().maxInFlightTasks()),
            worker,
            researchEpochs,
            researchTasks,
            researchResults,
            ignored -> {},
            this::persistResearchBoundary);
    List<ResearchWorkResultEnvelope> settled = executor.execute(snapshot, workItems);
    ResearchMergePlan plan = executor.latestMergePlan();
    String frozenAuthorityHash = snapshot.authority().stableHash();
    ResearchEpochCommitResult commit =
        researchEpochCommitter.commit(
            snapshot,
            plan,
            snapshot::authority,
            new ResearchAuthorityMutationTransaction<String>() {
              @Override
              public String snapshot() {
                return frozenAuthorityHash;
              }

              @Override
              public ResearchAuthorityMutationReceipt apply(List<String> acceptedResultHashes) {
                return ResearchAuthorityMutationReceipt.create(
                    snapshot.epochId(),
                    plan.mergePlanHash(),
                    frozenAuthorityHash,
                    snapshot.authority().restoreStableHash(),
                    acceptedResultHashes,
                    List.of(),
                    List.of(),
                    List.of());
              }

              @Override
              public void restore(String ignored) {}
            });
    researchAuthorityMutations.recordAuthorityMutation(commit.authorityMutation());
    researchAuthorityMutations.recordMergeReceipt(commit.mergeReceipt());
    Set<String> accepted = Set.copyOf(commit.mergeReceipt().acceptedResultHashes());
    workItems.forEach(
        item -> {
          String resultHash = researchResults.require(item.workItemId()).envelope().resultHash();
          researchTasks.transition(
              item.workItemId(),
              accepted.contains(resultHash)
                  ? ResearchWorkStatus.MERGED
                  : ResearchWorkStatus.SUPERSEDED,
              null,
              null,
              null,
              null);
        });
    researchEpochs.commit(snapshot.epochId(), commit.authorityMutation().authorityHashAfter());
    return settled;
  }

  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification =
          "The epoch transaction restores the complete authority snapshot before deliberately"
              + " preserving the original mutation exception.")
  private AuthoritativeEpochRun executeAuthoritativeEpoch(
      String stageKey,
      List<AuthoritativeWorkSpec> workSpecs,
      DesktopResearchEpochExecutor.ManagedWorker worker,
      java.util.function.Consumer<List<ResearchWorkResultEnvelope>> stableCommit) {
    String stage = requireNonBlank(stageKey, "stageKey");
    List<AuthoritativeWorkSpec> orderedSpecs =
        workSpecs.stream()
            .sorted(
                java.util.Comparator.comparingInt(AuthoritativeWorkSpec::stableOrdinal)
                    .thenComparing(AuthoritativeWorkSpec::routeId)
                    .thenComparing(AuthoritativeWorkSpec::claimId)
                    .thenComparing(AuthoritativeWorkSpec::obligationId))
            .toList();
    Map<String, String> runProjectionBefore = authoritativeRunProjectionParts();
    ResearchAuthorityAnchor authorityAtStart = currentResearchAuthorityAnchor();
    Optional<ResearchEpochRecord> resumable = findRestorablePreparedEpoch(orderedSpecs);
    String epochId =
        resumable
            .map(ResearchEpochRecord::epochId)
            .orElseGet(
                () ->
                    "epoch-"
                        + CanonicalJson.stableHash(
                                List.of(
                                    runId,
                                    stage,
                                    roundIndex.get(),
                                    authorityAtStart.stableHash(),
                                    orderedSpecs.stream()
                                        .map(AuthoritativeWorkSpec::identity)
                                        .toList()))
                            .substring(0, 24));
    Map<String, String> publicInputRefs = new LinkedHashMap<>();
    for (AuthoritativeWorkSpec spec : orderedSpecs) {
      publicInputRefs.put(spec.identity(), spec.inputArtifactRef());
    }
    ResearchAuthorityAnchor frozenAuthority =
        resumable.map(ResearchEpochRecord::authority).orElse(authorityAtStart);
    FrozenResearchSnapshot frozen =
        new FrozenResearchSnapshot(epochId, frozenAuthority, publicInputRefs);
    var epochsBefore = researchEpochs.snapshot();
    var tasksBefore = researchTasks.snapshot();
    var resultsBefore = researchResults.snapshot();
    List<ResearchWorkItem> items =
        resumable
            .map(
                record ->
                    record.workItemIds().stream()
                        .map(id -> researchTasks.require(id).item())
                        .sorted(java.util.Comparator.comparingInt(ResearchWorkItem::stableOrdinal))
                        .toList())
            .orElseGet(() -> compileResearchWorkItems(epochId, frozen, orderedSpecs));
    DesktopResearchEpochExecutor executor =
        new DesktopResearchEpochExecutor(
            runId,
            pool,
            DesktopDurableBoundaryObserver.from(progress).maximumResearchInFlight(
                config.concurrency().maxInFlightTasks()),
            worker,
            researchEpochs,
            researchTasks,
            researchResults,
            this::persistResearchBoundary);
    List<ResearchWorkResultEnvelope> settled = executor.execute(frozen, items);
    persistUnchecked("research_epoch_all_settled", false);
    if (stage.startsWith("claim-court-")
        && settled.size() == 1
        && settled.getFirst().status() == ResearchWorkResultStatus.FAILED) {
      researchEpochs.restore(epochsBefore);
      researchTasks.restore(tasksBefore);
      researchResults.restore(resultsBefore);
      persistUnchecked("research_epoch_worker_failure_compensated", false);
      throw new IllegalStateException(
          Objects.toString(
              settled.getFirst().publicStructuredResult().get("failure_detail"),
              "Claim Court worker failed"));
    }
    failAuthoritativeConcurrencyAt(
        AuthoritativeConcurrencyFailurePoint.AFTER_RESULTS_DURABLE_BEFORE_COMMIT);
    if (researchEpochs.require(epochId).status() != ResearchEpochStatus.COMMITTED) {
      ResearchAuthorityAnchor authorityAtCommit = currentResearchAuthorityAnchor();
      if (!authorityAtCommit.stableHash().equals(authorityAtStart.stableHash())) {
        directWorkerAuthorityMutations.incrementAndGet();
        List<String> changed =
            changedAuthorityProjections(authorityAtStart, authorityAtCommit);
        if (changed.contains("run_authority")) {
          List<String> runChanges =
              changedProjectionParts(runProjectionBefore, authoritativeRunProjectionParts());
          changed =
              java.util.stream.Stream.concat(
                      changed.stream().filter(name -> !name.equals("run_authority")),
                      runChanges.stream().map(name -> "run_authority." + name))
                  .sorted()
                  .toList();
        }
        throw new IllegalStateException(
            "STALE_SNAPSHOT: " + changed);
      }
      ResearchEpochAuthorityMutationSnapshot authorityBefore =
          captureResearchEpochAuthorityMutation();
      activeEpochAuthorityCommit.set(new EpochAuthorityCommitContext(epochId));
      try {
        ResearchEpochCommitResult commit =
            researchEpochCommitter.commit(
                frozen,
                executor.latestMergePlan(),
                resumable.isPresent()
                    ? () -> frozen.authority()
                    : this::currentResearchAuthorityAnchor,
                new ResearchAuthorityMutationTransaction<
                    ResearchEpochAuthorityMutationSnapshot>() {
                  @Override
                  public ResearchEpochAuthorityMutationSnapshot snapshot() {
                    return authorityBefore;
                  }

                  @Override
                  public ResearchAuthorityMutationReceipt apply(
                      List<String> acceptedResultHashes) {
                    Set<String> accepted = Set.copyOf(acceptedResultHashes);
                    stableCommit.accept(
                        settled.stream()
                            .filter(result -> accepted.contains(result.resultHash()))
                            .toList());
                    return createResearchAuthorityMutationReceipt(
                        frozen,
                        executor.latestMergePlan().mergePlanHash(),
                        acceptedResultHashes,
                        authorityBefore);
                  }

                  @Override
                  public void restore(ResearchEpochAuthorityMutationSnapshot snapshot) {
                    restoreResearchEpochAuthorityMutation(snapshot);
                  }
                });
        researchAuthorityMutations.recordAuthorityMutation(commit.authorityMutation());
        researchAuthorityMutations.recordMergeReceipt(commit.mergeReceipt());
        failAuthoritativeConcurrencyAt(
            AuthoritativeConcurrencyFailurePoint
                .AFTER_ALL_AUTHORITY_RESULTS_APPLIED_BEFORE_EPOCH_COMMIT);
        executor.latestMergePlan().decisions().forEach(
            decision ->
                researchTasks.transition(
                    decision.workItemId(),
                    decision.accepted()
                        ? ResearchWorkStatus.MERGED
                        : ResearchWorkStatus.SUPERSEDED,
                    null,
                    null,
                    null,
                    null));
        researchEpochs.commit(epochId, commit.authorityMutation().authorityHashAfter());
        restorablePreparedEpochIds.remove(epochId);
        failAuthoritativeConcurrencyAt(
            AuthoritativeConcurrencyFailurePoint.AFTER_EPOCH_MARKED_COMMITTED_BEFORE_CHECKPOINT);
      } catch (RuntimeException exception) {
        restoreResearchEpochAuthorityMutation(authorityBefore);
        throw exception;
      } finally {
        activeEpochAuthorityCommit.remove();
      }
      persistUnchecked("research_epoch_committed", false);
    }
    return new AuthoritativeEpochRun(frozen, items, settled, executor.latestMergePlan());
  }

  private void persistResearchBoundary(DesktopDurableBoundary boundary) {
    persistUnchecked(boundary.researchCheckpointStage(), false);
    DesktopDurableBoundaryObserver.from(progress).afterDurableBoundary(boundary, statePath());
  }

  private List<ResearchWorkItem> compileResearchWorkItems(
      String epochId,
      FrozenResearchSnapshot frozen,
      List<AuthoritativeWorkSpec> orderedSpecs) {
    List<ResearchWorkItem> items = new ArrayList<>();
    for (AuthoritativeWorkSpec spec : orderedSpecs) {
      String workItemId =
          ResearchWorkItem.deterministicId(
              epochId,
              spec.kind(),
              spec.routeId(),
              spec.claimId(),
              spec.obligationId(),
              spec.stableOrdinal());
      items.add(
          new ResearchWorkItem(
              workItemId,
              epochId,
              frozen.snapshotHash(),
              spec.kind(),
              spec.routeId(),
              spec.claimId(),
              spec.obligationId(),
              spec.canonicalTargetId(),
              spec.requiredRole(),
              spec.leaseClass(),
              spec.excludedAgentIds(),
              spec.readSet(),
              spec.conflictSet(),
              spec.inputArtifactRef(),
              spec.expectedResultSchema(),
              spec.stableOrdinal()));
    }
    return List.copyOf(items);
  }

  private Optional<ResearchEpochRecord> findRestorablePreparedEpoch(
      List<AuthoritativeWorkSpec> orderedSpecs) {
    ResearchAuthorityAnchor current = currentResearchAuthorityAnchor();
    for (ResearchEpochRecord epoch : researchEpochs.snapshot().epochs()) {
      if (!restorablePreparedEpochIds.contains(epoch.epochId())
          || epoch.status() != ResearchEpochStatus.MERGE_PREPARED
          || epoch.authority() == null
          || epoch.workItemIds().size() != orderedSpecs.size()) {
        continue;
      }
      List<ResearchWorkItem> items;
      try {
        items =
            epoch.workItemIds().stream()
                .map(id -> researchTasks.require(id).item())
                .sorted(java.util.Comparator.comparingInt(ResearchWorkItem::stableOrdinal))
                .toList();
      } catch (IllegalArgumentException missingTask) {
        continue;
      }
      boolean sameWork = true;
      for (int index = 0; index < orderedSpecs.size(); index++) {
        if (!sameResearchWork(items.get(index), orderedSpecs.get(index))) {
          sameWork = false;
          break;
        }
      }
      if (!sameWork) {
        continue;
      }
      if (!authorityEquivalentAcrossRestore(epoch.authority(), current)) {
        throw new IllegalStateException(
            "STALE_RESTORED_EPOCH_AUTHORITY: "
                + changedAuthorityProjections(epoch.authority(), current));
      }
      return Optional.of(epoch);
    }
    return Optional.empty();
  }

  private void reconcileResearchEpochAuthorityCommitsAfterRestore() {
    restorablePreparedEpochIds.clear();
    for (ResearchEpochRecord epoch : researchEpochs.snapshot().epochs()) {
      Optional<ResearchAuthorityMutationReceipt> mutation =
          researchAuthorityMutations.authorityMutation(epoch.epochId());
      Optional<ResearchMergeReceipt> merge =
          researchAuthorityMutations.mergeReceipt(epoch.epochId());
      ResearchAuthorityAnchor current = currentResearchAuthorityAnchor();
      if (epoch.status() == ResearchEpochStatus.MERGE_PREPARED
          && epoch.authority() != null
          && mutation.isEmpty()
          && merge.isEmpty()
          && authorityEquivalentAcrossRestore(epoch.authority(), current)) {
        restorablePreparedEpochIds.add(epoch.epochId());
        continue;
      }
      ResearchAuthorityCommitProtocol protocol =
          Objects.requireNonNull(
              epoch.authorityCommitProtocol(), "restored epoch authority commit protocol");
      ResearchEpochCommitStateMachine.RecoveryDecision decision =
          researchEpochCommitStateMachine.reconcile(
              epoch,
              current.restoreStableHash(),
              mutation,
              merge,
              protocol == ResearchAuthorityCommitProtocol.RECEIPT_V1);
      if (decision.action()
          == ResearchEpochCommitStateMachine.RecoveryAction.ROLL_FORWARD_RECEIPTED) {
        rollForwardReceiptedResearchEpoch(epoch, mutation.orElseThrow(), merge);
      } else if (decision.action()
          == ResearchEpochCommitStateMachine.RecoveryAction.REPLAY_PREPARED) {
        restorablePreparedEpochIds.add(epoch.epochId());
      } else if (decision.quarantined()) {
        if (epoch.status() != ResearchEpochStatus.COMMITTED) {
          researchEpochs.transition(
              epoch.epochId(), ResearchEpochStatus.QUARANTINED, null, null);
        }
        throw new IllegalStateException(
            decision.code()
                + ": "
                + (epoch.authority() == null
                    ? "missing frozen epoch authority"
                    : changedAuthorityProjections(epoch.authority(), current)));
      }
    }
  }

  private void rollForwardReceiptedResearchEpoch(
      ResearchEpochRecord epoch,
      ResearchAuthorityMutationReceipt mutation,
      Optional<ResearchMergeReceipt> existingMerge) {
    Set<String> accepted = Set.copyOf(mutation.acceptedResultHashes());
    ResearchMergeReceipt merge =
        existingMerge.orElseGet(
            () ->
                new ResearchMergeReceipt(
                    epoch.epochId(),
                    mutation.mergePlanHash(),
                    mutation.acceptedResultHashes(),
                    epoch.workItemIds().stream()
                        .map(researchResults::require)
                        .map(artifact -> artifact.envelope().resultHash())
                        .filter(resultHash -> !accepted.contains(resultHash))
                        .toList(),
                    mutation.authorityHashAfter()));
    researchAuthorityMutations.recordMergeReceipt(merge);
    epoch.workItemIds().forEach(
        workItemId -> {
          String resultHash = researchResults.require(workItemId).envelope().resultHash();
          researchTasks.transition(
              workItemId,
              accepted.contains(resultHash)
                  ? ResearchWorkStatus.MERGED
                  : ResearchWorkStatus.SUPERSEDED,
              null,
              null,
              null,
              null);
        });
    researchEpochs.commit(epoch.epochId(), mutation.authorityHashAfter());
    restorablePreparedEpochIds.remove(epoch.epochId());
  }

  private static boolean sameResearchWork(
      ResearchWorkItem item, AuthoritativeWorkSpec spec) {
    return item.kind() == spec.kind()
        && item.routeId().equals(spec.routeId())
        && item.claimId().equals(spec.claimId())
        && item.obligationId().equals(spec.obligationId())
        && item.canonicalTargetId().equals(spec.canonicalTargetId())
        && item.requiredRole().equals(spec.requiredRole())
        && item.leaseClass() == spec.leaseClass()
        && item.excludedAgentIds().equals(spec.excludedAgentIds())
        && item.conflictSet().equals(spec.conflictSet())
        && item.inputArtifactRef().equals(spec.inputArtifactRef())
        && item.expectedResultSchema().equals(spec.expectedResultSchema())
        && item.stableOrdinal() == spec.stableOrdinal();
  }

  private static boolean authorityEquivalentAcrossRestore(
      ResearchAuthorityAnchor frozen, ResearchAuthorityAnchor restored) {
    return frozen.problemHash().equals(restored.problemHash())
        && frozen.rootGoalHash().equals(restored.rootGoalHash())
        && frozen.negativeRegistryHash().equals(restored.negativeRegistryHash())
        && frozen.attemptArtifactLedgerHash().equals(restored.attemptArtifactLedgerHash())
        && frozen.claimLifecycleHash().equals(restored.claimLifecycleHash())
        && frozen.researchCheckpointHash().equals(restored.researchCheckpointHash())
        && frozen.proofGraphHash().equals(restored.proofGraphHash())
        && frozen.convergenceHash().equals(restored.convergenceHash())
        && frozen.semanticPivotHash().equals(restored.semanticPivotHash())
        && frozen.strategyPortfolioHash().equals(restored.strategyPortfolioHash())
        && frozen.claimCourtHash().equals(restored.claimCourtHash())
        && frozen.brokerHash().equals(restored.brokerHash())
        && frozen.computationHash().equals(restored.computationHash());
  }

  private static List<String> changedAuthorityProjections(
      ResearchAuthorityAnchor frozen, ResearchAuthorityAnchor current) {
    Map<String, List<String>> projections =
        Map.ofEntries(
            Map.entry("problem", List.of(frozen.problemHash(), current.problemHash())),
            Map.entry("root_goal", List.of(frozen.rootGoalHash(), current.rootGoalHash())),
            Map.entry(
                "negative_registry",
                List.of(frozen.negativeRegistryHash(), current.negativeRegistryHash())),
            Map.entry(
                "attempt_artifacts",
                List.of(frozen.attemptArtifactLedgerHash(), current.attemptArtifactLedgerHash())),
            Map.entry(
                "claim_lifecycle",
                List.of(frozen.claimLifecycleHash(), current.claimLifecycleHash())),
            Map.entry(
                "research_checkpoints",
                List.of(frozen.researchCheckpointHash(), current.researchCheckpointHash())),
            Map.entry("proof_graph", List.of(frozen.proofGraphHash(), current.proofGraphHash())),
            Map.entry(
                "canonicalization",
                List.of(frozen.canonicalizationHash(), current.canonicalizationHash())),
            Map.entry(
                "convergence", List.of(frozen.convergenceHash(), current.convergenceHash())),
            Map.entry(
                "semantic_pivot",
                List.of(frozen.semanticPivotHash(), current.semanticPivotHash())),
            Map.entry(
                "strategy_portfolio",
                List.of(frozen.strategyPortfolioHash(), current.strategyPortfolioHash())),
            Map.entry("claim_court", List.of(frozen.claimCourtHash(), current.claimCourtHash())),
            Map.entry("broker", List.of(frozen.brokerHash(), current.brokerHash())),
            Map.entry(
                "computation", List.of(frozen.computationHash(), current.computationHash())),
            Map.entry(
                "run_authority", List.of(frozen.runAuthorityHash(), current.runAuthorityHash())));
    return projections.entrySet().stream()
        .filter(entry -> !entry.getValue().get(0).equals(entry.getValue().get(1)))
        .map(Map.Entry::getKey)
        .sorted()
        .toList();
  }

  private ResearchAuthorityAnchor currentResearchAuthorityAnchor() {
    String rootHash =
        rootGoal == null
            ? frozenProblem == null ? problemHash : frozenProblem.integrityHash()
            : rootGoal.sourceStatementHash();
    return new ResearchAuthorityAnchor(
        problemHash,
        rootHash,
        negativeKnowledgeRegistry == null ? "" : negativeKnowledgeRegistry.registryHash(),
        attemptArtifacts.ledgerHash(),
        CanonicalJson.stableHash(proofControl.claims().snapshot()),
        researchCheckpointLedger().ledgerHash(),
        proofGraphAuthorityHash(),
        CanonicalJson.stableHash(
            List.of(
                proofGraph.allCanonicalTargets(),
                proofGraph.allBottleneckFamilies(),
                proofGraph.rawObligationOccurrences())),
        proofGraphConvergence.stableHash(),
        CanonicalJson.stableHash(semanticPivots.ledger().snapshot()),
        CanonicalJson.stableHash(
            List.of(
                strategyCandidates.snapshot(),
                strategyMechanisms.snapshot(),
                strategyPreflights.snapshot(),
                strategyPortfolios.snapshot(),
                portfolioReplenishments.snapshot())),
        claimCourt.stableHash(),
        CanonicalJson.stableHash(
            List.of(
                mathematicalArtifactBroker.registrySnapshot(),
                mathematicalArtifactBroker.publicationSnapshot(),
                mathematicalArtifactBroker.receiptSnapshot(),
                mathematicalArtifactBroker.useSnapshot())),
        CanonicalJson.stableHash(List.copyOf(computationTraces)),
        authoritativeRunProjectionHash());
  }

  private ResearchEpochAuthorityMutationSnapshot captureResearchEpochAuthorityMutation() {
    return new ResearchEpochAuthorityMutationSnapshot(
        currentResearchAuthorityAnchor(),
        attemptArtifacts.snapshot(),
        lemmaMemory.snapshot(),
        proofControl.claims().snapshot(),
        claimProofRevisions.snapshot(),
        claimCourt.snapshot(),
        claimCourtExecutions.snapshot(),
        typedMemory.snapshot(),
        proofGraph.snapshot(),
        checkpoints.snapshot(),
        List.copyOf(pendingProofTasks),
        researchCheckpoints.snapshot(),
        researchEpochs.snapshot(),
        researchTasks.snapshot(),
        researchResults.snapshot(),
        researchAuthorityMutations.snapshot(),
        routes.stream().map(DesktopSolveCoordinator::copyRouteState).toList(),
        List.copyOf(computationTraces),
        List.copyOf(computationAudits),
        computation.snapshot(),
        messageRepository.snapshot(),
        mathematicalArtifactBroker.registrySnapshot(),
        mathematicalArtifactBroker.publicationSnapshot(),
        mathematicalArtifactBroker.deliverySnapshot(),
        mathematicalArtifactBroker.receiptSnapshot(),
        mathematicalArtifactBroker.useSnapshot(),
        mathematicalArtifactBroker.utilitySnapshot(),
        mathematicalArtifactBroker.invalidationSnapshot(),
        Set.copyOf(restorablePreparedEpochIds));
  }

  private void restoreResearchEpochAuthorityMutation(
      ResearchEpochAuthorityMutationSnapshot snapshot) {
    attemptArtifacts = AttemptArtifactLedger.restore(snapshot.attemptArtifacts());
    lemmaMemory = LemmaMemory.restore(snapshot.lemmaMemory());
    proofControl.claims().load(snapshot.claimLifecycle());
    claimProofRevisions.restore(snapshot.claimProofRevisions());
    claimCourt.restore(snapshot.claimCourt());
    claimCourtExecutions.restore(snapshot.claimCourtExecutions());
    typedMemory = TypedMemory.restore(snapshot.typedMemory(), memoryPolicy());
    proofGraph = ProofGraphStore.restore(snapshot.proofGraph(), ProofGraphPolicy.defaults());
    checkpoints.restore(snapshot.checkpoints());
    pendingProofTasks.clear();
    pendingProofTasks.addAll(snapshot.pendingProofTasks());
    researchCheckpoints = ResearchCheckpointLedger.restore(snapshot.researchCheckpoints());
    researchEpochs.restore(snapshot.researchEpochs());
    researchTasks.restore(snapshot.researchTasks());
    researchResults.restore(snapshot.researchResults());
    researchAuthorityMutations.restore(snapshot.researchAuthorityMutations());
    routes.clear();
    snapshot.routes().stream()
        .map(DesktopSolveCoordinator::copyRouteState)
        .forEach(routes::add);
    computationTraces.clear();
    computationTraces.addAll(snapshot.computationTraces());
    computationAudits.clear();
    computationAudits.addAll(snapshot.computationAudits());
    computation.restore(snapshot.computation());
    messageRepository = new InMemoryMessageRepository(snapshot.messageStore());
    mathematicalArtifactBroker.restore(
        snapshot.brokerArtifactRegistry(),
        snapshot.brokerArtifactPublications(),
        snapshot.brokerArtifactDeliveries(),
        snapshot.brokerArtifactReceipts(),
        snapshot.brokerArtifactUses(),
        snapshot.brokerArtifactUtilities(),
        snapshot.brokerArtifactInvalidations());
    restorablePreparedEpochIds.clear();
    restorablePreparedEpochIds.addAll(snapshot.restorablePreparedEpochIds());
    installNegativeKnowledgeRuntime();
    resetRouteRuntimeRegistry();
    ResearchAuthorityAnchor restored = currentResearchAuthorityAnchor();
    if (!authorityEquivalentAcrossRestore(snapshot.authority(), restored)) {
      throw new IllegalStateException(
          "epoch authority rollback did not restore the frozen projection: "
              + changedAuthorityProjections(snapshot.authority(), restored));
    }
  }

  private ResearchAuthorityMutationReceipt createResearchAuthorityMutationReceipt(
      FrozenResearchSnapshot frozen,
      String mergePlanHash,
      List<String> acceptedResultHashes,
      ResearchEpochAuthorityMutationSnapshot before) {
    ClaimLifecycleSnapshot claimsAfter = proofControl.claims().snapshot();
    List<String> projectedClaimIds =
        claimsAfter.entries().entrySet().stream()
            .filter(
                entry ->
                    !Objects.equals(
                        before.claimLifecycle().entries().get(entry.getKey()), entry.getValue()))
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
    Set<String> factsBefore = factMessageIds(before.typedMemory());
    List<String> factMessageIds =
        factMessageIds(typedMemory.snapshot()).stream()
            .filter(id -> !factsBefore.contains(id))
            .sorted()
            .toList();
    Set<String> refutedBefore = refutedObligationIds(before.proofGraph());
    List<String> refutedObligationIds =
        refutedObligationIds(proofGraph.snapshot()).stream()
            .filter(id -> !refutedBefore.contains(id))
            .sorted()
            .toList();
    return ResearchAuthorityMutationReceipt.create(
        frozen.epochId(),
        mergePlanHash,
        frozen.authority().stableHash(),
        currentResearchAuthorityAnchor().restoreStableHash(),
        acceptedResultHashes,
        projectedClaimIds,
        factMessageIds,
        refutedObligationIds);
  }

  private static Set<String> factMessageIds(TypedMemorySnapshot snapshot) {
    return snapshot.tiers().entrySet().stream()
        .filter(entry -> entry.getValue() == MemoryTier.FACT)
        .map(Map.Entry::getKey)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private static Set<String> refutedObligationIds(ProofGraphSnapshot snapshot) {
    return snapshot.obligations().entrySet().stream()
        .filter(entry -> "refuted".equals(entry.getValue().status()))
        .map(Map.Entry::getKey)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private String authoritativeRunProjectionHash() {
    return CanonicalJson.stableHash(authoritativeRunProjectionParts());
  }

  private Map<String, String> authoritativeRunProjectionParts() {
    List<Map<String, Object>> routeProjection =
        routes.stream()
            .sorted(java.util.Comparator.comparing(route -> route.routeId))
            .map(
                route ->
                    Map.<String, Object>ofEntries(
                        Map.entry("route_id", route.routeId),
                        Map.entry("strategy_id", route.strategy.strategyId()),
                        Map.entry("status", route.status),
                        Map.entry("attempt", route.attempt == null ? "" : route.attempt.attemptId()),
                        Map.entry("review_complete", route.reviewComplete),
                        Map.entry("checkpoint_processed", route.checkpointProcessed),
                        Map.entry("integrated", route.integrated),
                        Map.entry("claims", List.copyOf(route.claimIds)),
                        Map.entry("court_cases", List.copyOf(route.courtCaseIds))))
            .toList();
    return Map.of(
        "routes", CanonicalJson.stableHash(routeProjection),
        "typed_memory", typedMemoryAuthorityHash(),
        "lemma_memory", CanonicalJson.stableHash(lemmaMemory.snapshot()),
        "claim_revisions", CanonicalJson.stableHash(claimProofRevisions.snapshot()),
        "checkpoint_ledger", CanonicalJson.stableHash(checkpoints.snapshot()),
        "pending_tasks", CanonicalJson.stableHash(List.copyOf(pendingProofTasks)));
  }

  String typedMemoryAuthorityHash() {
    var snapshot = typedMemory.snapshot();
    Map<String, String> messageContentHashes = new java.util.TreeMap<>();
    snapshot
        .messages()
        .forEach((messageId, message) -> messageContentHashes.put(messageId, message.contentHash()));
    return CanonicalJson.stableHash(
        Map.ofEntries(
            Map.entry("messages", messageContentHashes),
            Map.entry("tiers", snapshot.tiers()),
            Map.entry("content_index", snapshot.contentIndex()),
            Map.entry("provenance", snapshot.provenance()),
            Map.entry("invalidations", snapshot.invalidations()),
            Map.entry("counterexample_batches", snapshot.counterexampleBatches()),
            Map.entry("versions", snapshot.versions()),
            Map.entry("audit", snapshot.audit()),
            Map.entry("negative_knowledge", snapshot.negativeKnowledge())));
  }

  String proofGraphAuthorityHash() {
    var snapshot = proofGraph.snapshot();
    Map<String, String> claimContentHashes = new java.util.TreeMap<>();
    snapshot
        .claimNodes()
        .forEach((claimId, claim) -> claimContentHashes.put(claimId, claim.contentHash()));
    return CanonicalJson.stableHash(
        Map.ofEntries(
            Map.entry("problem_hash", snapshot.problemHash()),
            Map.entry("frozen", snapshot.frozen()),
            Map.entry("obligations", snapshot.obligations()),
            Map.entry("claim_nodes", claimContentHashes),
            Map.entry("edges", snapshot.edges()),
            Map.entry("aliases", snapshot.aliases()),
            Map.entry("needs_reverify", snapshot.needsReverify()),
            Map.entry("versions", snapshot.versions()),
            Map.entry("audit", snapshot.audit()),
            Map.entry("canonicalization", snapshot.canonicalization())));
  }

  private static List<String> changedProjectionParts(
      Map<String, String> frozen, Map<String, String> current) {
    return java.util.stream.Stream.concat(frozen.keySet().stream(), current.keySet().stream())
        .distinct()
        .filter(name -> !Objects.equals(frozen.get(name), current.get(name)))
        .sorted()
        .toList();
  }

  private static String requireNonBlank(String value, String label) {
    String normalized = Objects.requireNonNull(value, label).strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return normalized;
  }

  void setAuthoritativeConcurrencyFailurePointForTest(
      AuthoritativeConcurrencyFailurePoint point) {
    authoritativeConcurrencyFailurePoint = Objects.requireNonNull(point, "point");
  }

  AuthoritativeConcurrencyDiagnostics authoritativeConcurrencyDiagnosticsForTest() {
    var epochSnapshot = researchEpochs.snapshot();
    var taskSnapshot = researchTasks.snapshot();
    var resultSnapshot = researchResults.snapshot();
    return new AuthoritativeConcurrencyDiagnostics(
        epochSnapshot.epochs().size(),
        taskSnapshot.tasks().size(),
        resultSnapshot.artifacts().size(),
        epochSnapshot.epochs().stream()
            .filter(epoch -> epoch.status() == ResearchEpochStatus.COMMITTED)
            .count(),
        researchAuthorityMutations.snapshot().mergeReceipts().size(),
        directWorkerAuthorityMutations.get(),
        taskSnapshot.tasks().stream()
            .map(record -> record.item().kind())
            .collect(
                java.util.stream.Collectors.toCollection(
                    () -> java.util.EnumSet.noneOf(ResearchWorkKind.class))));
  }

  ResearchAuthorityAnchor currentResearchAuthorityAnchorForTest() {
    return currentResearchAuthorityAnchor();
  }

  private void failAuthoritativeConcurrencyAt(AuthoritativeConcurrencyFailurePoint point) {
    if (authoritativeConcurrencyFailurePoint == point) {
      authoritativeConcurrencyFailurePoint = AuthoritativeConcurrencyFailurePoint.NONE;
      throw new SimulatedAuthoritativeConcurrencyProcessTermination(point);
    }
  }

  RunExecutionBackend.RunExecutionResult execute(boolean resumeRequested) throws IOException {
    Optional<DesktopSolveCheckpoint> restored =
        resumeRequested ? readCheckpoint() : Optional.empty();
    if (restored.isPresent()) {
      DesktopSolveCheckpoint checkpoint = restored.orElseThrow();
      restore(checkpoint);
      event(
          "checkpoint_resumed",
          "committed_checkpoint",
          null,
          "completed",
          "Resumed from the latest committed semantic state",
          statePath().toString());
      boolean repairedLegacyAdmission = repairLegacyDomainObjectAdmission(checkpoint);
      if (checkpoint.schemaVersion() < STATE_SCHEMA_VERSION) {
        persist(currentStage, checkpoint.terminal() && !repairedLegacyAdmission);
      }
      if (checkpoint.terminal() && !repairedLegacyAdmission) {
        return resultFromCurrentState();
      }
    }
    computation.setStatePersister(
        (reason, state) -> persistUnchecked(reason, false));
    while (true) {
      switch (workflowCursor) {
        case CURSOR_FREEZE -> freezeProblem();
        case CURSOR_TRIAGE -> runTriage();
        case CURSOR_STRATEGY -> {
          generateAndAdmitStrategies();
          if (admittedStrategies.isEmpty()) {
            workflowCursor = CURSOR_TERMINAL;
            persist("route_admission_and_team", true);
            return resultFromCurrentState();
          }
        }
        case CURSOR_INITIAL_ROUTES -> {
          ensureInitialRoutes();
          workflowCursor = CURSOR_EXPLORE;
          persist("initial_routes", false);
        }
        case CURSOR_EXPLORE -> {
          if (!reserveInitialExplorationBudget()) {
            String stopCode = "STOP_BUDGET_EXHAUSTED";
            recordSchedulerStop(normalizedLowerCaseCode(stopCode),
                "initial route exploration is not affordable before ready-queue admission");
            workflowCursor = CURSOR_TERMINAL;
            persist("initial_exploration_budget_rejected", true);
            return resultFromCurrentState();
          }
          exploreUnstartedRoutes(true);
          workflowCursor = CURSOR_INTEGRATE;
          persist("isolated_exploration", false);
        }
        case CURSOR_INTEGRATE -> {
          integrateCommittedRoutes();
          finishActiveSchedulerBudgetEnvelope();
          workflowCursor = CURSOR_BROKER;
          persist("claim_memory_graph", false);
        }
        case CURSOR_BROKER -> {
          distributeVerifiedClaims();
          workflowCursor = CURSOR_SCHEDULER_INSPIRATION;
          persist("cross_route_broker", false);
        }
        case CURSOR_SCHEDULER_INSPIRATION,
            CURSOR_SCHEDULER_META,
            CURSOR_SCHEDULER_DECISION,
            CURSOR_SCHEDULER_EXPLORE,
            CURSOR_SCHEDULER_INTEGRATE,
            CURSOR_SCHEDULER_BROKER -> {
          SchedulerExit schedulerExit = runScheduler();
          if (schedulerExit == SchedulerExit.STOPPED || verifiedRoutes().isEmpty()) {
            workflowCursor = CURSOR_TERMINAL;
            persist("scheduler_stopped", true);
            return resultFromCurrentState();
          }
        }
        case CURSOR_SYNTHESIS, CURSOR_FINAL_REVIEW -> {
          synthesizeAndVerify();
          workflowCursor = CURSOR_TERMINAL;
          persist("blind_final_review", true);
          return resultFromCurrentState();
        }
        case CURSOR_TERMINAL -> {
          return resultFromCurrentState();
        }
        default -> throw new IllegalStateException("unknown workflow cursor: " + workflowCursor);
      }
    }
  }

  private void freezeProblem() {
    stage(RoutePipelineFunctions.RunStage.FREEZE_PROBLEM, "Freezing the submitted problem");
    AgentRuntime planner =
        pool.select("planner", Set.of(), List.of("problem_decomposition"), null, true);
    GoalPreflightService preflight = new GoalPreflightService();
    GoalPreflightService.GoalContext context =
        new GoalPreflightService.GoalContext(
            ProblemKind.PROOF,
            config.runtime().outputLanguage(),
            List.of("a complete proof"),
            List.of("preserve every hypothesis and quantifier"),
            allowedComputationMethods(sandboxEnabled),
            List.of(),
            List.of(TaskRequirement.PROOF));
    final StructuredCallResult<?>[] normalizationCall = new StructuredCallResult<?>[1];
    GoalPreflightService.GoalNormalizer normalizer =
        new GoalPreflightService.GoalNormalizer() {
          @Override
          public GoalNormalizationAssessment normalize(
              String statement,
              io.github.aililuola.mathproofmesh.contract.LocalGoalPrecheck precheck,
              int maxOutputTokens,
              boolean thinkingEnabled) {
            StructuredCallResult<GoalNormalizationAssessment> call =
                callStage(
                    "goal-normalization",
                    "goal_normalization",
                    GoalNormalizationAssessment.class,
                    Map.of(
                        "original_statement", statement,
                        "deterministic_precheck", precheck,
                        "preservation_rule", "Do not strengthen, weaken, or drop quantifiers."),
                    planner,
                    "breadth",
                    "Normalizing an ambiguous goal without changing its meaning");
            normalizationCall[0] = call;
            return call.value();
          }

          @Override
          public String agentId() {
            return planner.id();
          }

          @Override
          public String rawReference() {
            return normalizationCall[0] == null
                ? null
                : normalizationCall[0].responseArtifactRef();
          }
        };
    GoalPreflightService.GoalPreflightOutcome outcome =
        preflight.prepare(
            request.problem(),
            context,
            normalizer,
            clarification ->
                new io.github.aililuola.mathproofmesh.contract.GoalClarificationDecision(
                    clarification.assessment().recommendedStatement(),
                    clarification.requestId(),
                    0,
                    "auto_assumed"));
    frozenProblem = outcome.problem();
    rootGoal = RootGoalContract.freeze(frozenProblem.exactStatement(), exactGoalContractChecker);
    event(
        "problem_frozen",
        "freeze_problem",
        planner.id(),
        "completed",
        "Frozen raw input and semantic problem contract with separate integrity hashes",
        "problem://" + frozenProblem.integrityHash());
    addMainGoalObligation();
    seedProblemSpecificReasoningGuardrails();
    complete(RoutePipelineFunctions.RunStage.FREEZE_PROBLEM);
    workflowCursor = CURSOR_TRIAGE;
    persistUnchecked("freeze_problem", false);
  }

  private void runTriage() {
    stage(RoutePipelineFunctions.RunStage.TRIAGE, "Classifying the frozen problem");
    AgentRuntime planner =
        pool.select("planner", Set.of(), List.of("problem_decomposition"), null, true);
    triage =
        callStage(
                "triage",
                "triage",
                TriageResult.class,
                Map.of(
                    "immutable_problem", frozenProblem,
                    "problem_hash", problemHash,
                    "live_model", "deepseek-v4-pro",
                    "reasoning_effort", "max"),
                planner,
                "breadth",
                "Classifying the problem")
            .value();
    if (triage.semanticViewCandidate() != null) {
      ProblemSemanticViewService.Attachment attachment =
          semanticViewService.attach(
              rootGoal(), frozenProblem, triage.semanticViewCandidate());
      frozenProblem = attachment.authoritativeProblem();
      event(
          "semantic_view_audited",
          "triage",
          planner.id(),
          attachment.auditedView().status(),
          attachment.candidateAttached()
              ? "Attached a deterministically audited non-authoritative English sidecar"
              : "Rejected the English sidecar and retained the authoritative root goal",
          "problem://" + rootGoal().sourceStatementHash());
    }
    complete(RoutePipelineFunctions.RunStage.TRIAGE);
    workflowCursor = CURSOR_STRATEGY;
    persistUnchecked("triage", false);
  }

  private void generateAndAdmitStrategies() {
    BudgetConfig budget = config.budget();
    boolean initialBatchGeneratedHere = strategySet == null;
    if (strategySet == null) {
      stage(
          RoutePipelineFunctions.RunStage.STRATEGY_DIVERSITY,
          "Generating and selecting genuinely diverse proof strategies");
      AgentRuntime planner =
          pool.select("planner", Set.of(), List.of("problem_decomposition"), null, true);
      strategySet =
          callStage(
                  "strategy-generation",
                  "strategy_generation",
                  StrategySet.class,
                  Map.of(
                      "immutable_problem", frozenProblem,
                      "problem_hash", problemHash,
                      "triage", triage,
                      "strategies_requested", budget.strategiesToGenerate(),
                      "registered_computation_contracts",
                          ContractsFunctions.experimentToolCatalog(Set.of()),
                      "sandbox_available", sandboxEnabled,
                      "migration_parity_requirements", strategyGenerationGuidance(),
                      "diversity_gate",
                          "Compare required claims and dependency graphs, not titles or labels. "
                              + "Routes sharing the same unresolved load-bearing lemma count as one mechanism."),
                  planner,
                  "breadth",
                  "Generating independent proof strategies")
              .value();
      complete(RoutePipelineFunctions.RunStage.STRATEGY_DIVERSITY);
    } else {
      currentStage = RoutePipelineFunctions.RunStage.STRATEGY_DIVERSITY.name().toLowerCase(Locale.ROOT);
      event(
          "strategy_set_reused",
          currentStage,
          null,
          "completed",
          "Reusing the committed strategy set without another provider call",
          statePath().toString());
    }
    stage(
        RoutePipelineFunctions.RunStage.ROUTE_ADMISSION_AND_TEAM,
        "Compiling and atomically admitting an evidence-grounded mechanism portfolio");
    String episodeId = strategyPortfolioEpisodeId();
    if (strategyPortfolios.receipt(episodeId).isEmpty()) {
      StrategyPortfolioPreparation preparation = prepareStrategyPortfolio(episodeId, strategySet);
      int effectiveMinimumPortfolioSize =
          Math.min(
              strategyDiversityConfig.minPortfolioSize(),
              Math.min(budget.strategiesToGenerate(), budget.maxPaths()));
      int requestedPortfolioSize =
          Math.min(budget.strategiesToGenerate(), budget.maxPaths());
      int qualifiedPortfolioTarget =
          Math.min(requestedPortfolioSize, distinctStrategies(strategySet.strategies()).size());
      boolean qualifiedPortfolioShortfall =
          preparation.decision().selectedStrategyIds().size() < qualifiedPortfolioTarget;
      if ((preparation.decision().selectedStrategyIds().size()
                  < effectiveMinimumPortfolioSize
              || qualifiedPortfolioShortfall)
          && strategySet.strategies().size() >= effectiveMinimumPortfolioSize
          && initialBatchGeneratedHere
          && portfolioReplenishments.mayRequest(episodeId)) {
        strategySet = replenishStrategyPortfolioOnce(episodeId, strategySet, preparation);
        preparation = prepareStrategyPortfolio(episodeId, strategySet);
      }
      StrategyPortfolioDecision decision = strategyPortfolios.record(preparation.decision());
      finalizeCandidateDecisions(preparation.candidates(), decision);
      persistUnchecked("strategy_portfolio_prepared", false);
      applyStrategyPortfolioAtomically(episodeId, preparation.withDecision(decision));
    } else {
      auditUnseenStrategyCandidatesAfterPortfolioCommit(episodeId, strategySet);
    }
    complete(RoutePipelineFunctions.RunStage.ROUTE_ADMISSION_AND_TEAM);
    workflowCursor = CURSOR_INITIAL_ROUTES;
    persistUnchecked("route_admission_and_team", false);
  }

  private void auditUnseenStrategyCandidatesAfterPortfolioCommit(
      String episodeId, StrategySet source) {
    List<StrategyCard> unseen =
        source.strategies().stream()
            .filter(strategy -> strategyCandidates.find(strategy.strategyId()).isEmpty())
            .toList();
    if (unseen.isEmpty()) {
      return;
    }
    StrategyPortfolioPreparation audit =
        prepareStrategyPortfolio(
            episodeId,
            new StrategySet(
                "Late candidates audited against the committed portfolio.", List.of(), unseen));
    audit.candidates().stream()
        .map(candidate -> candidate.strategy().strategyId())
        .forEach(
            strategyId ->
                strategyCandidates
                    .find(strategyId)
                    .filter(record -> !terminalStrategyCandidateStatus(record.status()))
                    .ifPresent(
                        ignored ->
                            strategyCandidates.transition(
                                strategyId,
                                StrategyCandidateStatus.NOT_SELECTED,
                                "",
                                "",
                                null,
                                "PORTFOLIO_ALREADY_COMMITTED")));
    persistUnchecked("strategy_portfolio_late_candidates_audited", false);
  }

  private StrategyPortfolioPreparation prepareStrategyPortfolio(
      String episodeId, StrategySet source) {
    var candidatesBefore = strategyCandidates.snapshot();
    var mechanismsBefore = strategyMechanisms.snapshot();
    var preflightsBefore = strategyPreflights.snapshot();
    boolean completed = false;
    try {
      StrategyPortfolioPreparation prepared = prepareStrategyPortfolioUnchecked(episodeId, source);
      completed = true;
      return prepared;
    } finally {
      if (!completed) {
        var durablePreflights = strategyPreflights.snapshot();
        strategyCandidates = StrategyCandidateLedger.restore(candidatesBefore);
        strategyMechanisms = StrategyMechanismRegistry.restore(mechanismsBefore);
        strategyPreflights = StrategyPreflightRegistry.restore(preflightsBefore);
        strategyPreflights.mergeDurable(durablePreflights);
      }
    }
  }

  private StrategyPortfolioPreparation prepareStrategyPortfolioUnchecked(
      String episodeId, StrategySet source) {
    List<StrategyCard> raw = distinctStrategies(source.strategies());
    Map<String, PreparedStrategyCandidate> prepared = new LinkedHashMap<>();
    ProofControlModels.Obligation goal = controlGoal();
    ProofControlModels.ScopeSignature scope =
        proofControl
            .scopeGuard()
            .extract("goal-scope", rootGoal().sourceStatement(), List.of(), 1.0d);
    ProofControlModels.Mode mode = proofControlMode();
    CommonModeRiskRegistry commonMode = new CommonModeRiskRegistry();
    int captureOrder = 0;
    for (StrategyCard strategy : raw) {
      boolean replenishment =
          portfolioReplenishments
              .find(episodeId)
              .map(record -> record.candidateIds().contains(strategy.strategyId()))
              .orElse(false);
      if (strategyCandidates.find(strategy.strategyId()).isEmpty()) {
        strategyCandidates.capture(
            episodeId, strategy.strategyId(), captureOrder, replenishment);
      }
      captureOrder++;
      failStrategyPortfolioAt(StrategyPortfolioFailurePoint.AFTER_CANDIDATE_LEDGER);
      String routeId = "candidate-route-" + strategy.strategyId();
      ProofControlModels.Strategy controlStrategy = controlStrategy(strategy, routeId);
      StrategyBlueprintCompiler.Compilation blueprint;
      ProofControlModels.GoalLink link;
      try {
        blueprint = proofControl.blueprintCompiler().compile(problemHash, controlStrategy, goal);
        link =
            proofControl
                .goalAlignment()
                .assess(
                    controlStrategy.id(),
                    rootGoal().sourceStatement(),
                    scope,
                    goal,
                    scope,
                    proofControl.scopeGuard(),
                    String::equals);
        if (strategyCandidates
            .find(strategy.strategyId())
            .filter(record -> record.status() == StrategyCandidateStatus.CAPTURED)
            .isPresent()) {
          strategyCandidates.transition(
              strategy.strategyId(),
              StrategyCandidateStatus.BLUEPRINT_COMPILED,
              "",
              "",
              null,
              "BLUEPRINT_COMPILED");
        }
      } catch (RuntimeException exception) {
        rejectStrategyCandidate(
            strategy, StrategyCandidateStatus.REJECTED_INVALID, exception.getClass().getSimpleName());
        continue;
      }
      Map<String, CriticalClaimContext> claimContexts;
      StrategyMechanismSignature signature;
      StrategyMechanismProfile profile;
      try {
        claimContexts = criticalClaimContexts(strategy, blueprint, scope);
        signature =
            strategyMechanisms
                .signature(strategy.strategyId())
                .orElseGet(
                    () ->
                        strategyMechanismAnalyzer.signature(
                            problemHash,
                            rootGoal().sourceStatementHash(),
                            strategy,
                            controlStrategy,
                            blueprint,
                            claimContexts,
                            strategyCanonicalTargetIds()));
        profile =
            strategyMechanisms
                .profile(strategy.strategyId())
                .orElseGet(() -> strategyMechanismAnalyzer.profile(strategy, blueprint));
        strategyMechanisms.register(strategy.strategyId(), signature, profile, false);
      } catch (RuntimeException exception) {
        rejectStrategyCandidate(
            strategy,
            StrategyCandidateStatus.REJECTED_INVALID,
            "INVALID_TYPED_MECHANISM_OR_CLAIM_CONTEXT:" + exception.getMessage());
        continue;
      }
      if (!signature.operationGraphKnown()) {
        strategyCandidates.transition(
            strategy.strategyId(),
            StrategyCandidateStatus.QUARANTINED_MECHANISM_UNRESOLVED,
            signature.structuralSignatureHash(),
            "",
            0.0d,
            "QUARANTINED_MECHANISM_UNRESOLVED");
        continue;
      }
      StrategyPreflightReport report = strategyPreflights.find(strategy.strategyId()).orElse(null);
      if (report == null) {
        try {
          StrategyPreflightPlan plan = prepareStrategyPreflightPlan(strategy, claimContexts);
          Map<String, CriticalClaimPreflightEvidence> computationEvidence =
              executeRegisteredStrategyPreflight(strategy, plan, claimContexts);
          report =
              strategyPreflights.record(
                  trustedStrategyPreflight(computationEvidence)
                      .evaluate(problemHash, strategy, claimContexts, plan));
        } catch (IllegalArgumentException exception) {
          rejectStrategyCandidate(
              strategy,
              StrategyCandidateStatus.REJECTED_INVALID,
              "INVALID_STRATEGY_PREFLIGHT_CONTRACT:" + exception.getMessage());
          continue;
        }
      }
      StrategyPreflightReport resolvedReport = report;
      StrategyCandidateStatus preflightStatus = preflightStatus(resolvedReport);
      if (preflightStatus != StrategyCandidateStatus.PREFLIGHTED) {
        strategyCandidates.transition(
            strategy.strategyId(),
            preflightStatus,
            signature.structuralSignatureHash(),
            resolvedReport.reportHash(),
            null,
            preflightDetail(resolvedReport));
        continue;
      }
      try {
        negativeKnowledgeGate.requireAllAllowed(
            negativeKnowledgeCandidates(
                strategy, blueprint, NegativeKnowledgeSurface.STRATEGY_ADMISSION),
            roundIndex.get());
      } catch (NegativeKnowledgeBlockedException exception) {
        rejectStrategyCandidate(
            strategy, StrategyCandidateStatus.REJECTED_NEGATIVE, exception.getMessage());
        recordNegativeKnowledgeRejection(
            "route_rejected",
            "route_admission_and_team",
            strategy.title(),
            exception);
        continue;
      }
      var baseDecision =
          proofControl
              .routeAdmission()
              .evaluate(mode, controlStrategy, blueprint, link, false, false);
      if (baseDecision.blocksRuntime(mode)) {
        rejectStrategyCandidate(
            strategy,
            StrategyCandidateStatus.REJECTED_INVALID,
            String.join(";", baseDecision.reasons()));
        continue;
      }
      strategyCandidates
          .find(strategy.strategyId())
          .filter(record -> !terminalStrategyCandidateStatus(record.status()))
          .ifPresent(
              ignored -> {
                strategyCandidates.transition(
                    strategy.strategyId(),
                    StrategyCandidateStatus.BASE_VALIDATED,
                    signature.structuralSignatureHash(),
                    resolvedReport.reportHash(),
                    null,
                    "BASE_VALIDATED");
                strategyCandidates.transition(
                    strategy.strategyId(),
                    StrategyCandidateStatus.PREFLIGHTED,
                    signature.structuralSignatureHash(),
                    resolvedReport.reportHash(),
                    null,
                    preflightDetail(resolvedReport));
              });
      commonMode.observe(resolvedReport);
      prepared.put(
          strategy.strategyId(),
          new PreparedStrategyCandidate(
              strategy,
              controlStrategy,
              blueprint,
              link,
              signature,
              profile,
              resolvedReport));
    }
    failStrategyPortfolioAt(StrategyPortfolioFailurePoint.AFTER_PREFLIGHT);

    List<StrategyPortfolioCandidate> candidates = new ArrayList<>();
    StrategyFeasibilityCalibrator calibrator =
        new StrategyFeasibilityCalibrator(strategyDiversityConfig);
    for (PreparedStrategyCandidate candidate : prepared.values()) {
      int commonGroups = commonMode.groupsFor(candidate.strategy().strategyId()).size();
      var score =
          calibrator.calibrate(
              candidate.strategy(),
              candidate.blueprint(),
              candidate.preflight(),
              candidate.goalLink().confidence(),
              1.0d,
              1.0d,
              commonGroups == 0 ? 0.0d : 1.0d);
      strategyCandidates
          .find(candidate.strategy().strategyId())
          .filter(record -> record.calibratedScore() == null)
          .filter(record -> record.status() == StrategyCandidateStatus.PREFLIGHTED)
          .ifPresent(
              ignored ->
                  strategyCandidates.transition(
                      candidate.strategy().strategyId(),
                      StrategyCandidateStatus.PREFLIGHTED,
                      candidate.signature().structuralSignatureHash(),
                      candidate.preflight().reportHash(),
                      score.total(),
                      "SERVER_CALIBRATED"));
      candidates.add(
          new StrategyPortfolioCandidate(
              candidate.strategy(),
              candidate.blueprint(),
              candidate.signature(),
              candidate.profile(),
              candidate.preflight(),
              score));
    }
    int requested =
        Math.min(config.budget().strategiesToGenerate(), config.budget().maxPaths());
    StrategyPortfolioDecision decision =
        strategyPortfolioOptimizer.optimize(
            episodeId,
            candidates,
            new StrategyPortfolioConstraint(
                requested,
                Math.min(strategyDiversityConfig.minPortfolioSize(), requested),
                strategyDiversityConfig.maxExactPortfolioCandidates(),
                Set.of(),
                Set.of(),
                strategyDiversityConfig.minimumAdmissibleFeasibility(),
                strategyDiversityConfig.minimumBlueprintCompleteness(),
                strategyDiversityConfig.minimumRequiredClaimEvidenceForPrimaryRoute()));
    failStrategyPortfolioAt(StrategyPortfolioFailurePoint.AFTER_PORTFOLIO_SELECTION);
    return new StrategyPortfolioPreparation(candidates, prepared, decision);
  }

  private StrategySet replenishStrategyPortfolioOnce(
      String episodeId, StrategySet source, StrategyPortfolioPreparation preparation) {
    List<String> selectedSignatures =
        preparation.decision().selectedStrategyIds().stream()
            .map(preparation.prepared()::get)
            .filter(Objects::nonNull)
            .map(candidate -> candidate.signature().structuralSignatureHash())
            .sorted()
            .toList();
    List<String> rejectedClaimKeys =
        preparation.candidates().stream()
            .flatMap(candidate -> candidate.preflight().claims().stream())
            .filter(
                claim ->
                    claim.status() == CriticalClaimPreflightStatus.VERIFIED_REFUTED
                        || claim.status() == CriticalClaimPreflightStatus.PERMANENTLY_BLOCKED)
            .map(claim -> claim.key().semanticKey())
            .distinct()
            .sorted()
            .toList();
    List<String> commonModeGroups =
        preparation.candidates().stream()
            .flatMap(candidate -> candidate.preflight().unresolvedRequiredClaimKeys().stream())
            .collect(java.util.stream.Collectors.groupingBy(value -> value, java.util.stream.Collectors.counting()))
            .entrySet()
            .stream()
            .filter(entry -> entry.getValue() > 1L)
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
    Set<StrategyMechanismPrimitive> coveredProfiles =
        preparation.decision().selectedStrategyIds().stream()
            .map(preparation.prepared()::get)
            .filter(Objects::nonNull)
            .flatMap(candidate -> candidate.profile().primitives().stream())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    List<String> missingProfiles =
        java.util.Arrays.stream(StrategyMechanismPrimitive.values())
            .filter(primitive -> primitive != StrategyMechanismPrimitive.UNKNOWN)
            .filter(primitive -> !coveredProfiles.contains(primitive))
            .map(Enum::name)
            .toList();
    int needed =
        Math.max(
            1,
            Math.min(config.budget().strategiesToGenerate(), config.budget().maxPaths())
                - preparation.decision().selectedStrategyIds().size());
    Map<String, Object> gap = new LinkedHashMap<>();
    gap.put("episode_id", episodeId);
    gap.put("selected_hard_mechanism_signatures", selectedSignatures);
    gap.put("rejected_required_claim_keys", rejectedClaimKeys);
    gap.put("missing_soft_mechanism_profiles", missingProfiles);
    gap.put("unresolved_common_mode_groups", commonModeGroups);
    gap.put("forbidden_structural_signatures", selectedSignatures);
    gap.putAll(
        StrategyPortfolioGapFeedback.invalidContractErrors(
            episodeId, source, strategyCandidates.snapshot()));
    gap.put("strategies_requested", needed);
    String requestHash = CanonicalJson.stableHash(gap);
    portfolioReplenishments.begin(episodeId, requestHash);
    persistUnchecked("portfolio_gap_replenishment_requested", false);

    AgentRuntime planner =
        pool.select("planner", Set.of(), List.of("problem_decomposition"), null, true);
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("immutable_problem", frozenProblem);
    context.put("problem_hash", problemHash);
    context.put("triage", triage);
    context.put("generation_mode", "portfolio_gap_replenishment");
    context.putAll(gap);
    context.put("registered_computation_contracts", ContractsFunctions.experimentToolCatalog(Set.of()));
    context.put("migration_parity_requirements", strategyGenerationGuidance());
    StrategySet supplement;
    try {
      supplement =
          callStage(
                  "portfolio-gap-replenishment",
                  "strategy_generation",
                  StrategySet.class,
                  context,
                  planner,
                  "breadth",
                  "Filling structural gaps in the strategy portfolio")
              .value();
    } catch (StructuredOutputError failure) {
      portfolioReplenishments.complete(episodeId, List.of());
      event(
          "strategy_portfolio_replenishment_rejected",
          "strategy_generation",
          planner.id(),
          "rejected",
          "Retained the prepared source portfolio after invalid optional replenishment output",
          "portfolio-replenishment://" + episodeId);
      strategySet = source;
      persistUnchecked("portfolio_gap_replenishment_rejected", false);
      return source;
    }
    Set<String> existingIds =
        source.strategies().stream()
            .map(StrategyCard::strategyId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    List<StrategyCard> additions =
        supplement.strategies().stream()
            .filter(candidate -> !existingIds.contains(candidate.strategyId()))
            .toList();
    portfolioReplenishments.complete(
        episodeId, additions.stream().map(StrategyCard::strategyId).toList());
    List<StrategyCard> combined = new ArrayList<>(source.strategies());
    combined.addAll(additions);
    StrategySet result =
        new StrategySet(
            source.coverageNotes() + " | one-shot portfolio gap replenishment",
            distinctStrings(source.omittedDirections(), supplement.omittedDirections()),
            combined);
    strategySet = result;
    persistUnchecked("portfolio_gap_replenishment_completed", false);
    return result;
  }

  private StrategyCriticalClaimPreflight trustedStrategyPreflight() {
    return trustedStrategyPreflight(Map.of());
  }

  private StrategyCriticalClaimPreflight trustedStrategyPreflight(
      Map<String, CriticalClaimPreflightEvidence> computationEvidence) {
    Map<String, CriticalClaimPreflightEvidence> safeEvidence =
        computationEvidence == null ? Map.of() : Map.copyOf(computationEvidence);
    return new StrategyCriticalClaimPreflight(
        new CriticalClaimKeyCompiler(),
        List.of(
            new TrustedStrategyPreflightEvidenceSource(
                problemHash,
                negativeKnowledgeGate,
                lemmaMemory.verified(),
                typedMemory.facts(),
                roundIndex.get()),
            (key, spec) -> Optional.ofNullable(safeEvidence.get(spec.claim().claimId()))));
  }

  private StrategyPreflightPlan prepareStrategyPreflightPlan(
      StrategyCard strategy, Map<String, CriticalClaimContext> claimContexts) {
    Optional<StrategyPreflightPlan> existing = strategyPreflights.plan(strategy.strategyId());
    if (existing.isPresent()) {
      return validateStrategyPreflightPlan(strategy, existing.get());
    }
    StrategyPreflightPlan serverBinding =
        strategyPreflightPlanCompiler.compile(problemHash, strategy);
    StrategyPreflightPlan plan = serverBinding;
    if (StrategyPreflightAuthorityGate.hasBoundClaim(serverBinding)) {
      AgentRuntime planner =
          pool.select("planner", Set.of(), List.of("problem_decomposition"), null, true);
      plan =
          callStage(
                  "strategy-preflight-plan-" + strategy.strategyId(),
                  "strategy_preflight_plan",
                  StrategyPreflightPlan.class,
                  Map.of(
                      "immutable_problem", frozenProblem,
                      "problem_hash", problemHash,
                      "strategy_candidate", strategy,
                      "critical_claim_contexts", claimContexts,
                      "registered_computation_contracts", strategy.calculationChecks(),
                      "server_binding_candidates", serverBinding),
                  planner,
                  "verification",
                  "Binding load-bearing claims to registered computation contracts")
              .value();
      StrategyPreflightAuthorityGate.requireExact(serverBinding, plan);
    }
    validateStrategyPreflightPlan(strategy, plan);
    return strategyPreflights.recordPlan(plan);
  }

  private StrategyPreflightPlan validateStrategyPreflightPlan(
      StrategyCard strategy, StrategyPreflightPlan plan) {
    Set<String> claimIds =
        strategy.criticalClaims().stream()
            .map(CriticalClaim::claimId)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    return strategyPreflightPlanValidator.validate(
        plan,
        problemHash,
        strategy.strategyId(),
        claimIds,
        strategyPreflightPlanCompiler.registeredContractIds(strategy));
  }

  private Map<String, CriticalClaimContext> criticalClaimContexts(
      StrategyCard strategy,
      StrategyBlueprintCompiler.Compilation blueprint,
      ProofControlModels.ScopeSignature rootScope) {
    return criticalClaimContexts(strategy, blueprint, rootScope, true);
  }

  private Map<String, CriticalClaimContext> criticalClaimContexts(
      StrategyCard strategy,
      StrategyBlueprintCompiler.Compilation blueprint,
      ProofControlModels.ScopeSignature rootScope,
      boolean requireExplicitBindings) {
    CriticalClaimContext rootContext =
        rootCriticalClaimContext(strategy, blueprint, rootScope);
    return requireExplicitBindings
        ? criticalClaimContextCompiler.compileNewCandidate(strategy, blueprint, rootContext)
        : criticalClaimContextCompiler.compile(strategy, blueprint, rootContext);
  }

  private CriticalClaimContext rootCriticalClaimContext(
      StrategyCard strategy,
      StrategyBlueprintCompiler.Compilation blueprint,
      ProofControlModels.ScopeSignature rootScope) {
    LinkedHashSet<String> assumptions = new LinkedHashSet<>(controlGoal().assumptions());
    assumptions.addAll(strategy.prerequisites());
    List<QuantifierSpec> quantifiers = new ArrayList<>();
    List<VariableBinding> bindings = new ArrayList<>();
    int order = 0;
    for (ExactGoalContractChecker.QuantifierAtom atom :
        rootGoal().signature().quantifierSkeleton()) {
      for (String variable : atom.variables()) {
        String variableId = "root-q" + order;
        quantifiers.add(
            new QuantifierSpec(
                variable,
                "root-goal quantified domain",
                atom.kind(),
                order,
                List.of(),
                variableId));
        bindings.add(
            new VariableBinding(
                List.of(variable),
                variable,
                "root-goal quantified domain",
                "root-goal",
                variableId));
        order++;
      }
    }
    List<String> scope = new ArrayList<>();
    if (rootScope.indexScope() != ProofControlModels.IndexScope.UNKNOWN) {
      scope.add("index_scope=" + rootScope.indexScope().name().toLowerCase(Locale.ROOT));
    }
    if (rootScope.uniformity() != ProofControlModels.UniformityScope.UNKNOWN) {
      scope.add("uniformity=" + rootScope.uniformity().name().toLowerCase(Locale.ROOT));
    }
    if (rootScope.objectScope() != ProofControlModels.ObjectScope.UNKNOWN) {
      scope.add("object_scope=" + rootScope.objectScope().name().toLowerCase(Locale.ROOT));
    }
    scope.addAll(rootScope.domainConstraints());
    scope.addAll(rootScope.exceptionalCases());
    if (!blueprint.blueprint().completePathToMainGoal()) {
      scope.add("incomplete_blueprint");
    }
    return new CriticalClaimContext(
        List.copyOf(assumptions), quantifiers, scope, bindings, "positive");
  }

  private Map<String, CriticalClaimPreflightEvidence> executeRegisteredStrategyPreflight(
      StrategyCard strategy,
      StrategyPreflightPlan plan,
      Map<String, CriticalClaimContext> contexts) {
    String planHash = CanonicalJson.stableHash(plan);
    Map<String, CriticalClaim> claims =
        strategy.criticalClaims().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    CriticalClaim::claimId,
                    value -> value,
                    (left, right) -> left,
                    LinkedHashMap::new));
    Map<String, CriticalClaimPreflightEvidence> evidence = new LinkedHashMap<>();
    for (CriticalClaimPreflightPlan claimPlan : plan.claimPlans()) {
      if (claimPlan.computationContractId() == null
          || claimPlan.computationContractId().isBlank()) {
        continue;
      }
      CriticalClaim claim = claims.get(claimPlan.claimId());
      if (claim == null) {
        continue;
      }
      String executionId =
          StrategyPreflightRegistry.executionId(
              problemHash, strategy.strategyId(), claim.claimId(), planHash);
      ToolRequest request = strategyPreflightPlanCompiler.request(strategy, claimPlan);
      String actionKey =
          "registered-strategy-preflight:"
              + (request == null ? claimPlan.computationContractId() : request.kind());
      String typedInputHash =
          CanonicalJson.stableHash(request == null ? claimPlan : request);
      Optional<StrategyPreflightExecutionRecord> existing =
          strategyPreflights.execution(executionId);
      if (existing.isPresent()) {
        StrategyPreflightExecutionRecord frontier = existing.get();
        boolean legacyCompleted =
            frontier.completed()
                && frontier.actionKey().startsWith("legacy-preflight-action:");
        if (legacyCompleted) {
          evidence.put(claim.claimId(), frontier.evidence());
          continue;
        }
        if (!frontier.actionKey().equals(actionKey)
            || !StrategySemanticNormalizer.hashEquals(
                frontier.typedInputHash(), typedInputHash)) {
          evidence.put(
              claim.claimId(),
              quarantinedPreflight(executionId, "PREFLIGHT_EXECUTION_BINDING_CHANGED"));
          continue;
        }
        if (frontier.completed()) {
          evidence.put(claim.claimId(), frontier.evidence());
          continue;
        }
        if (frontier.status() == StrategyPreflightExecutionStatus.RESULT_DURABLE) {
          strategyPreflights.completeExecution(
              executionId, frontier.evidence(), roundIndex.get());
          persistUnchecked("strategy_preflight_result_rolled_forward", false);
          evidence.put(claim.claimId(), frontier.evidence());
          continue;
        }
        if (frontier.status() == StrategyPreflightExecutionStatus.RUNNING
            || frontier.status() == StrategyPreflightExecutionStatus.ABORTED) {
          evidence.put(
              claim.claimId(),
              quarantinedPreflight(executionId, "PREFLIGHT_EXECUTION_STATE_UNCERTAIN"));
          continue;
        }
      }
      StrategyPreflightExecutionRecord reservation =
          existing.orElseGet(
              () ->
                  strategyPreflights.reserveExecution(
                      problemHash,
                      strategy.strategyId(),
                      claim.claimId(),
                      planHash,
                      actionKey,
                      typedInputHash,
                      roundIndex.get()));
      if (existing.isEmpty()) {
        persistUnchecked("strategy_preflight_execution_reserved", false);
        failStrategyPreflightAt(StrategyPreflightFailurePoint.AFTER_RESERVATION);
      }
      strategyPreflights.startExecution(reservation.executionId());
      persistUnchecked("strategy_preflight_execution_running", false);
      CriticalClaimPreflightEvidence result =
          executeRegisteredClaimPreflight(
              strategy,
              claim,
              claimPlan,
              contexts.getOrDefault(claim.claimId(), CriticalClaimContext.empty()),
              reservation.executionId());
      String resultArtifactRef =
          result.evidenceRefs().stream()
              .reduce((left, right) -> right)
              .orElse("preflight-result:" + reservation.executionId());
      String replayHash = CanonicalJson.stableHash(result);
      strategyPreflights.recordDurableResult(
          reservation.executionId(),
          result,
          resultArtifactRef,
          replayHash,
          roundIndex.get());
      persistUnchecked("strategy_preflight_result_durable", false);
      failStrategyPreflightAt(
          StrategyPreflightFailurePoint.AFTER_RESULT_DURABLE_BEFORE_COMPLETION);
      strategyPreflights.completeExecution(
          reservation.executionId(), result, roundIndex.get());
      persistUnchecked("strategy_preflight_execution_completed", false);
      evidence.put(claim.claimId(), result);
    }
    return Map.copyOf(evidence);
  }

  private static CriticalClaimPreflightEvidence quarantinedPreflight(
      String executionId, String detail) {
    return new CriticalClaimPreflightEvidence(
        CriticalClaimPreflightStatus.EXECUTION_QUARANTINED,
        "registered-computation-preflight-frontier",
        List.of(executionId),
        detail);
  }

  private CriticalClaimPreflightEvidence executeRegisteredClaimPreflight(
      StrategyCard strategy,
      CriticalClaim claim,
      CriticalClaimPreflightPlan claimPlan,
      CriticalClaimContext context,
      String executionId) {
    ToolRequest request = strategyPreflightPlanCompiler.request(strategy, claimPlan);
    if (request == null) {
      return new CriticalClaimPreflightEvidence(
          CriticalClaimPreflightStatus.UNTESTABLE,
          "registered-computation-preflight",
          List.of(executionId),
          "UNREGISTERED_COMPUTATION_CONTRACT");
    }
    try {
      ExperimentSpec compiled = new ToolBroker(computation).compile(request);
      ExperimentSpec spec =
          new ExperimentSpec(
              compiled.arguments(),
              context.assumptions(),
              false,
              "Retain only evidence authorized by the computation evidence gate.",
              "Reject this strategy before any route mutation.",
              compiled.domains(),
              compiled.exactArithmetic(),
              null,
              executionId,
              compiled.maxCases(),
              compiled.method(),
              "Leave the claim unresolved and use another proof route.",
              null,
              "strategy-preflight:" + strategy.strategyId(),
              ComputationPurpose.FALSIFY_CLAIM,
              "A load-bearing strategy claim has an exact registered typed check.",
              null,
              "deterministic-strategy-preflight",
              JsonNodeFactory.instance.objectNode(),
              compiled.seed(),
              claim.statement(),
              null,
              "Refute a load-bearing claim before route admission.");
      ComputationBroker.PreparedDecision prepared =
          computation.decide(
              spec,
              ComputationContext.initial(
                  "strategy-preflight:" + strategy.strategyId(),
                  safeInt(ledger.remainingCalls())));
      if (prepared.decision().decision() != ComputationDecisionStatus.ALLOW) {
        return new CriticalClaimPreflightEvidence(
            CriticalClaimPreflightStatus.ERROR,
            "registered-computation-preflight",
            List.of(executionId),
            "COMPUTATION_POLICY_REJECTED:" + prepared.decision().reason());
      }
      ExperimentResult result =
          computation.runExperiment(prepared.spec(), prepared.decision());
      ComputationAudit audit =
          computation.auditExperiment(prepared.spec(), prepared.decision(), null, result);
      ComputationEvidenceGate.EvidenceAuthority authority =
          computation
              .executionService()
              .lastOutcome(prepared.spec().experimentId())
              .map(ComputationExecutionOutcome::authority)
              .orElse(ComputationEvidenceGate.EvidenceAuthority.INCONCLUSIVE);
      computationTraces.add(
          new ComputationTrace(
              "strategy-preflight:" + strategy.strategyId(),
              prepared.spec(),
              prepared.decision(),
              null,
              result,
              null,
              authority,
              audit.valid()));
      computationAudits.add(audit);
      List<String> refs = List.of(executionId, result.resultHash());
      if (!audit.valid()) {
        return new CriticalClaimPreflightEvidence(
            CriticalClaimPreflightStatus.ERROR,
            "registered-computation-replay",
            refs,
            "INDEPENDENT_REPLAY_FAILED:" + audit.diagnostic());
      }
      return switch (authority) {
        case REFUTED ->
            new CriticalClaimPreflightEvidence(
                CriticalClaimPreflightStatus.VERIFIED_REFUTED,
                "registered-computation-replay",
                refs,
                "INDEPENDENTLY_REPLAYED_COUNTEREXAMPLE");
        case NOT_REFUTED ->
            new CriticalClaimPreflightEvidence(
                CriticalClaimPreflightStatus.NOT_REFUTED_IN_BOUNDED_SCOPE,
                "registered-computation-replay",
                refs,
                "BOUNDED_SEARCH_DID_NOT_REFUTE");
        case VERIFIED, VERIFIED_BOUNDED ->
            new CriticalClaimPreflightEvidence(
                CriticalClaimPreflightStatus.VERIFIED_SUPPORTED,
                "registered-computation-replay",
                refs,
                "COMPLETE_REGISTERED_CERTIFICATE");
        case INCONCLUSIVE ->
            new CriticalClaimPreflightEvidence(
                CriticalClaimPreflightStatus.ERROR,
                "registered-computation-replay",
                refs,
                "INCONCLUSIVE_REGISTERED_COMPUTATION");
      };
    } catch (RuntimeException exception) {
      return new CriticalClaimPreflightEvidence(
          CriticalClaimPreflightStatus.ERROR,
          "registered-computation-preflight",
          List.of(executionId),
          "PREFLIGHT_EXECUTION_ERROR:" + exception.getClass().getSimpleName());
    }
  }

  private void rejectStrategyCandidate(
      StrategyCard strategy, StrategyCandidateStatus status, String detail) {
    strategyCandidates
        .find(strategy.strategyId())
        .filter(record -> !terminalStrategyCandidateStatus(record.status()))
        .ifPresent(
            ignored ->
                strategyCandidates.transition(
                    strategy.strategyId(), status, "", "", 0.0d, detail));
    event(
        "route_rejected",
        "route_admission_and_team",
        null,
        "rejected",
        "Rejected " + strategy.title() + (detail == null || detail.isBlank() ? "" : ": " + detail),
        "strategy://" + strategy.strategyId());
  }

  private static StrategyCandidateStatus preflightStatus(StrategyPreflightReport report) {
    if (report.claims().stream()
        .anyMatch(
            claim ->
                claim.status() == CriticalClaimPreflightStatus.ERROR
                    || claim.status()
                        == CriticalClaimPreflightStatus.EXECUTION_QUARANTINED)) {
      return StrategyCandidateStatus.QUARANTINED_PREFLIGHT_ERROR;
    }
    if (report.claims().stream()
        .anyMatch(
            claim ->
                "required".equals(claim.necessity())
                    && claim.status() == CriticalClaimPreflightStatus.VERIFIED_REFUTED)) {
      return StrategyCandidateStatus.REJECTED_REFUTED_REQUIRED_CLAIM;
    }
    if (report.claims().stream()
        .anyMatch(
            claim ->
                "required".equals(claim.necessity())
                    && claim.status() == CriticalClaimPreflightStatus.PERMANENTLY_BLOCKED)) {
      return StrategyCandidateStatus.REJECTED_NEGATIVE;
    }
    if (report.requiresRegeneration()) {
      return StrategyCandidateStatus.QUARANTINED_PREFLIGHT_ERROR;
    }
    return StrategyCandidateStatus.PREFLIGHTED;
  }

  private static String preflightDetail(StrategyPreflightReport report) {
    if (report.claims().stream()
        .anyMatch(
            claim ->
                claim.status() == CriticalClaimPreflightStatus.EXECUTION_QUARANTINED)) {
      return "PREFLIGHT_EXECUTION_FRONTIER_QUARANTINED";
    }
    if (report.hardRejected()) {
      return "REQUIRED_CLAIM_REFUTED_OR_BLOCKED";
    }
    if (report.requiresRegeneration()) {
      return "SUPPORTING_CLAIM_REQUIRES_REGENERATION";
    }
    return "TRUSTED_PREFLIGHT_COMPLETE";
  }

  private void finalizeCandidateDecisions(
      List<StrategyPortfolioCandidate> candidates, StrategyPortfolioDecision decision) {
    Set<String> selected = Set.copyOf(decision.selectedStrategyIds());
    for (StrategyPortfolioCandidate candidate : candidates) {
      String strategyId = candidate.strategy().strategyId();
      strategyCandidates
          .find(strategyId)
          .filter(record -> !terminalStrategyCandidateStatus(record.status()))
          .ifPresent(
              ignored -> {
                String reason = decision.nonSelectionReasons().getOrDefault(strategyId, "SELECTED");
                StrategyCandidateStatus status =
                    selected.contains(strategyId)
                        ? StrategyCandidateStatus.SELECTED
                        : switch (reason) {
                          case "SAME_STRUCTURAL_MECHANISM" ->
                              StrategyCandidateStatus.SHADOW_DUPLICATE;
                          case "SHARED_UNRESOLVED_REQUIRED_CLAIM" ->
                              StrategyCandidateStatus.QUARANTINED_COMMON_MODE;
                          default -> StrategyCandidateStatus.NOT_SELECTED;
                        };
                strategyCandidates.transition(
                    strategyId,
                    status,
                    candidate.signature().structuralSignatureHash(),
                    candidate.preflight().reportHash(),
                    candidate.feasibility().total(),
                    reason);
              });
    }
  }

  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification = "All active strategy and route projections are restored before failure propagation.")
  private StrategyPortfolioApplyReceipt applyStrategyPortfolioAtomically(
      String episodeId, StrategyPortfolioPreparation preparation) {
    var candidatesBefore = strategyCandidates.snapshot();
    var mechanismsBefore = strategyMechanisms.snapshot();
    var preflightsBefore = strategyPreflights.snapshot();
    var portfoliosBefore = strategyPortfolios.snapshot();
    var replenishmentsBefore = portfolioReplenishments.snapshot();
    StrategyArchive.Snapshot archiveBefore = strategyArchive.snapshot();
    Map<String, StrategyBlueprintCompiler.Compilation> blueprintsBefore =
        Map.copyOf(strategyBlueprints);
    Map<String, ProofControlModels.GoalLink> goalLinksBefore = Map.copyOf(goalLinks);
    List<StrategyCard> admittedBefore = admittedStrategies;
    List<RouteState> routesBefore = List.copyOf(routes);
    ProofGraphSnapshot graphBefore = proofGraph.snapshot();
    var convergenceBefore = proofGraphConvergence.snapshot();
    var deferredBefore = deferredExpansions.snapshot();
    List<DesktopSolveCheckpoint.ScheduledProofTask> tasksBefore = List.copyOf(pendingProofTasks);
    ContinuationFunctions.CheckpointLedgerSnapshot checkpointsBefore = checkpoints.snapshot();
    int nextBefore = nextStrategyIndex.get();
    String stageBefore = currentStage;
    boolean persistAttempted = false;
    try {
      List<String> selectedIds = preparation.decision().selectedStrategyIds();
      StrategyPortfolioApplyPlan plan =
          new StrategyPortfolioApplyPlan(
              "strategy-portfolio-plan-" + preparation.decision().decisionHash().substring(0, 20),
              episodeId,
              problemHash,
              rootGoal().sourceStatementHash(),
              selectedIds,
              preparation.decision().decisionHash());
      List<StrategyCard> selected =
          selectedIds.stream()
              .map(preparation.prepared()::get)
              .filter(Objects::nonNull)
              .map(PreparedStrategyCandidate::strategy)
              .toList();
      for (StrategyCard strategy : selected) {
        PreparedStrategyCandidate candidate = preparation.prepared().get(strategy.strategyId());
        strategyArchive.archive(
            candidate.controlStrategy(), "strategy://" + strategy.strategyId(), roundIndex.get());
        strategyBlueprints.put(strategy.strategyId(), candidate.blueprint());
        goalLinks.put(strategy.strategyId(), candidate.goalLink());
      }
      admittedStrategies = List.copyOf(selected);
      failStrategyPortfolioAt(StrategyPortfolioFailurePoint.AFTER_ARCHIVE);

      nextStrategyIndex.set(0);
      int initial = Math.min(config.budget().initialPaths(), admittedStrategies.size());
      for (int index = 0; index < initial; index++) {
        addRoute(
            admittedStrategies.get(nextStrategyIndex.getAndIncrement()),
            0,
            NegativeKnowledgeSurface.STRATEGY_ADMISSION);
      }
      recomputeNeighbors();
      failStrategyPortfolioAt(StrategyPortfolioFailurePoint.AFTER_ROUTE_CREATION);
      String activeHash =
          CanonicalJson.stableHash(
              Map.of(
                  "admitted",
                  admittedStrategies.stream().map(StrategyCard::strategyId).toList(),
                  "routes",
                  routes.stream().map(route -> route.routeId).toList(),
                  "archive",
                  strategyArchive.snapshot()));
      StrategyPortfolioApplyReceipt receipt =
          new StrategyPortfolioApplyReceipt(
              "strategy-portfolio-receipt-" + activeHash.substring(0, 20),
              plan.planId(),
              selectedIds,
              routes.stream().map(route -> route.routeId).toList(),
              activeHash);
      strategyPortfolios.recordReceipt(episodeId, receipt);
      failStrategyPortfolioAt(StrategyPortfolioFailurePoint.DURING_CHECKPOINT_PERSIST);
      persistAttempted = true;
      persistUnchecked("strategy_portfolio_apply", false);
      selected.forEach(
          strategy ->
              event(
                  "route_admitted",
                  "route_admission_and_team",
                  null,
                  "completed",
                  "Admitted independent mechanism " + strategy.title(),
                  "strategy://" + strategy.strategyId()));
      return receipt;
    } catch (RuntimeException exception) {
      strategyCandidates = StrategyCandidateLedger.restore(candidatesBefore);
      strategyMechanisms = StrategyMechanismRegistry.restore(mechanismsBefore);
      strategyPreflights = StrategyPreflightRegistry.restore(preflightsBefore);
      strategyPortfolios = StrategyPortfolioRegistry.restore(portfoliosBefore);
      portfolioReplenishments = PortfolioReplenishmentLedger.restore(replenishmentsBefore);
      strategyArchive.restore(archiveBefore);
      strategyBlueprints.clear();
      strategyBlueprints.putAll(blueprintsBefore);
      goalLinks.clear();
      goalLinks.putAll(goalLinksBefore);
      admittedStrategies = admittedBefore;
      routes.clear();
      routes.addAll(routesBefore);
      proofGraph = ProofGraphStore.restore(graphBefore, ProofGraphPolicy.defaults());
      proofGraphConvergence =
          ProofGraphConvergenceMonitor.restore(
              ProofGraphConvergenceConfig.defaults(), convergenceBefore);
      deferredExpansions = DeferredExpansionLedger.restore(deferredBefore);
      pendingProofTasks.clear();
      pendingProofTasks.addAll(tasksBefore);
      checkpoints.restore(checkpointsBefore);
      nextStrategyIndex.set(nextBefore);
      installNegativeKnowledgeRuntime();
      resetRouteRuntimeRegistry();
      if (persistAttempted) {
        try {
          persistUnchecked(stageBefore, false);
        } catch (RuntimeException rollbackFailure) {
          exception.addSuppressed(rollbackFailure);
        }
      } else {
        currentStage = stageBefore;
      }
      throw exception;
    }
  }

  private String strategyPortfolioEpisodeId() {
    return "initial-portfolio-"
        + CanonicalJson.stableHash(
                Map.of(
                    "problem_hash", problemHash,
                    "root_goal_hash", rootGoal().sourceStatementHash()))
            .substring(0, 20);
  }

  private void failStrategyPortfolioAt(StrategyPortfolioFailurePoint point) {
    if (strategyPortfolioHardCrashPoint == point) {
      strategyPortfolioHardCrashPoint = StrategyPortfolioFailurePoint.NONE;
      throw new SimulatedStrategyPortfolioProcessTermination(point);
    }
    if (strategyPortfolioFailurePoint == point) {
      strategyPortfolioFailurePoint = StrategyPortfolioFailurePoint.NONE;
      throw new IllegalStateException("injected strategy portfolio failure at " + point);
    }
  }

  void setStrategyPortfolioFailurePointForTest(StrategyPortfolioFailurePoint point) {
    strategyPortfolioFailurePoint =
        point == null ? StrategyPortfolioFailurePoint.NONE : point;
  }

  void setStrategyPortfolioHardCrashPointForTest(StrategyPortfolioFailurePoint point) {
    strategyPortfolioHardCrashPoint =
        point == null ? StrategyPortfolioFailurePoint.NONE : point;
  }

  private void failStrategyPreflightAt(StrategyPreflightFailurePoint point) {
    if (strategyPreflightHardCrashPoint == point) {
      strategyPreflightHardCrashPoint = StrategyPreflightFailurePoint.NONE;
      throw new SimulatedStrategyPreflightProcessTermination(point);
    }
  }

  void setStrategyPreflightHardCrashPointForTest(StrategyPreflightFailurePoint point) {
    strategyPreflightHardCrashPoint =
        point == null ? StrategyPreflightFailurePoint.NONE : point;
  }

  private static boolean terminalStrategyCandidateStatus(StrategyCandidateStatus status) {
    return switch (status) {
      case SELECTED,
          NOT_SELECTED,
          REJECTED_INVALID,
          REJECTED_NEGATIVE,
          REJECTED_REFUTED_REQUIRED_CLAIM,
          QUARANTINED_PREFLIGHT_ERROR,
          QUARANTINED_MECHANISM_UNRESOLVED,
          QUARANTINED_COMMON_MODE,
          SHADOW_DUPLICATE,
          LEGACY_ACTIVE -> true;
      default -> false;
    };
  }

  private static List<StrategyCard> distinctStrategies(List<StrategyCard> candidates) {
    LinkedHashMap<String, StrategyCard> distinct = new LinkedHashMap<>();
    candidates.forEach(candidate -> distinct.putIfAbsent(candidate.strategyId(), candidate));
    return List.copyOf(distinct.values());
  }

  private static List<String> distinctStrings(List<String> first, List<String> second) {
    LinkedHashSet<String> values = new LinkedHashSet<>(first);
    values.addAll(second);
    return List.copyOf(values);
  }

  private void ensureInitialRoutes() {
    if (!routes.isEmpty()) {
      rebuildRouteRegistry();
      return;
    }
    int initial = Math.min(config.budget().initialPaths(), admittedStrategies.size());
    for (int index = 0; index < initial; index++) {
      StrategyCard candidate = admittedStrategies.get(nextStrategyIndex.getAndIncrement());
      try {
        addRoute(candidate, 0);
      } catch (NegativeKnowledgeBlockedException exception) {
        recordNegativeKnowledgeRejection(
            "initial_route_rejected",
            "initial_routes",
            candidate.title(),
            exception);
      }
    }
    recomputeNeighbors();
    persistUnchecked("route_admission_and_team", false);
  }

  private void addRoute(StrategyCard strategy, int revisionCount) {
    addRoute(strategy, revisionCount, NegativeKnowledgeSurface.STRATEGY_ADMISSION);
  }

  private void addRoute(
      StrategyCard strategy,
      int revisionCount,
      NegativeKnowledgeSurface surface) {
    negativeKnowledgeGate.requireAllAllowed(
        negativeKnowledgeCandidates(
            strategy, strategyBlueprints.get(strategy.strategyId()), surface),
        roundIndex.get());
    String routeId = "route-" + (routes.size() + 1);
    List<AgentRuntime> explorers =
        pool.agents().stream().filter(agent -> agent.supportsRole("explorer")).toList();
    if (explorers.isEmpty()) {
      throw new IllegalStateException("live profile has no exploration agent");
    }
    AgentRuntime author = explorers.get(routes.size() % explorers.size());
    RiskAssessment baseline =
        routeTeam.classifyRisk(
            new RouteTeam.RiskSignals(
                !strategy.criticalClaims().isEmpty(),
                false,
                true,
                !strategy.computationHints().isEmpty(),
                false,
                false,
                false,
                false,
                true,
                !strategy.computationHints().isEmpty()));
    RouteTeamPlan plan = teamFactory.plan(routeId, author.id(), baseline);
    RouteState state = new RouteState(routeId, author, strategy, plan, revisionCount);
    routes.add(state);
    registerRoute(state);
    String obligationId = routeObligationId(routeId);
    if (proofGraph.obligations().stream()
        .noneMatch(obligation -> obligation.obligationId().equals(obligationId))) {
      ProofObligation routeObligation =
          new ProofObligation(
              List.of(),
              0.7d,
              "",
              List.of(),
              List.of(),
              List.of(),
              null,
              ObligationKind.SUBGOAL,
              topology.mathNormalize(strategy.bottleneck()),
              obligationId,
              0.8d,
              problemHash,
              List.of(),
              List.of(routeId),
              strategy.bottleneck(),
              "open");
      addControlledObligation(
          routeObligation,
          obligationContext(
              state,
              ObligationSourceType.ROUTE_BOTTLENECK,
              "strategy://" + strategy.strategyId(),
              topology.mathNormalize(strategy.bottleneck()),
              strategy.bottleneck()),
          FocusedRecoveryActionType.NEW_STRATEGY);
    }
    addBlueprintObligations(state);
    event(
        "proof_role_assigned",
        "route_admission_and_team",
        author.id(),
        "completed",
        "Assigned Prover, Skeptic, Tool Specialist, and independent Referee for " + routeId,
        "strategy://" + strategy.strategyId());
  }

  private void registerRoute(RouteState route) {
    if (!routeRegistry.exists(route.routeId)) {
      routeRegistry.register(routeDescriptor(route));
    }
    assign(route, route.plan.prover());
    assign(route, route.plan.skeptic());
    assign(route, route.plan.toolSpecialist());
    assign(route, route.plan.referee());
  }

  private void assign(RouteState route, RoleAssignment assignment) {
    if (assignment != null && assignment.assigned()) {
      routeRegistry.assignMember(
          route.routeId, assignment.agentId(), assignment.role(), roundIndex.get());
    }
  }

  private void rebuildRouteRegistry() {
    for (RouteState route : routes) {
      registerRoute(route);
    }
    recomputeNeighbors();
  }

  private void resetRouteRuntimeRegistry() {
    routeRegistry =
        new RouteRegistry(
            problemHash,
            config.topology().crossRoute().maxNeighborsPerRoute(),
            8,
            config.topology().strategySimilarityThreshold());
    rebuildRouteRegistry();
  }

  private void recomputeNeighbors() {
    Map<String, String> texts = new LinkedHashMap<>();
    routes.forEach(route -> texts.put(route.routeId, topology.strategyText(route.strategy)));
    Map<String, List<String>> neighbors =
        topology.selectSparseRouteNeighbors(texts, config.topology().crossRoute().maxNeighborsPerRoute());
    neighbors.forEach(routeRegistry::setNeighbors);
  }

  private void exploreUnstartedRoutes(boolean initialPass) {
    List<RouteState> pending =
        routes.stream()
            .filter(route -> route.attempt == null)
            .filter(route -> !"abandoned".equals(route.status))
            .filter(this::routeEligibleForWork)
            .toList();
    if (pending.isEmpty()) {
      return;
    }
    stage(
        RoutePipelineFunctions.RunStage.ISOLATED_EXPLORATION,
        initialPass
            ? "Exploring admitted routes concurrently in isolated contexts"
            : "Continuing selected routes from committed checkpoints");
    runFrozenExplorationEpochs(pending);
    complete(RoutePipelineFunctions.RunStage.ISOLATED_EXPLORATION);
    persistUnchecked("isolated_exploration", false);
  }

  private void runFrozenExplorationEpochs(List<RouteState> pending) {
    int maximumPathSegments =
        config.continuation().enabled() ? config.continuation().maxSegmentsPerPath() : 1;
    int maximumSegments =
        config.continuation().enabled()
            ? Math.min(
                config.continuation().segmentsPerExploreCall(), maximumPathSegments)
            : 1;
    Map<String, InitialExplorationTurn> previousByRoute = new LinkedHashMap<>();
    Map<String, ComputationTrace> computationByRoute = new LinkedHashMap<>();
    Map<String, ExplorationAdmission> admissionByRoute = new LinkedHashMap<>();
    Map<String, ExplorationSignature> signatureByRoute = new LinkedHashMap<>();
    Map<String, Integer> callsBeforeByRoute = new LinkedHashMap<>();
    for (RouteState route : pending) {
      ensureSeedCheckpoint(route);
      ExplorationSignature signature =
          new ExplorationSignature(
              triage == null ? "unknown" : triage.problemKind().value(),
              route.strategy.title(),
              route.strategy.tags());
      ExplorationModel tier =
          route.revisionCount > 0 ? ExplorationModel.BOUNDED_REPAIR : ExplorationModel.DEEP_96K;
      ExplorationAdmission admission =
          deepExploration.admit(
              signature,
              tier,
              new ExplorationEvidence(
                  route.attempt != null,
                  route.revisionCount > 0,
                  route.attempt != null,
                  route.revisionCount > 0,
                  safeInt(ledger.remainingCalls()),
                  config.scheduler().finishTransitionBufferCalls(),
                  route.revisionCount));
      if (!admission.accepted()) {
        route.status = "waiting";
        route.failureReason = admission.reason();
        event(
            "deep_exploration_rejected",
            "isolated_exploration",
            route.author.id(),
            "warning",
            admission.reason(),
            route.checkpoint.checkpointId());
      } else {
        admissionByRoute.put(route.routeId, admission);
        signatureByRoute.put(route.routeId, signature);
        callsBeforeByRoute.put(route.routeId, safeInt(ledger.totals().calls()));
      }
    }

    for (int segment = 0; segment < maximumSegments && ledger.remainingCalls() > 0; segment++) {
      List<RouteState> active =
          pending.stream()
              .filter(route -> admissionByRoute.containsKey(route.routeId))
              .filter(route -> route.attempt == null)
              .filter(route -> route.segmentCount < maximumPathSegments)
              .filter(route -> !Set.of("abandoned", "partial", "failed").contains(route.status))
              .sorted(java.util.Comparator.comparing(route -> route.routeId))
              .toList();
      if (active.isEmpty()) {
        break;
      }
      Map<String, BrokerArtifactPromptBatch> brokerByRoute = new LinkedHashMap<>();
      Map<String, Map<String, Object>> contextByRoute = new LinkedHashMap<>();
      for (RouteState route : active) {
        BrokerArtifactPromptBatch brokerBatch = consumeBrokerContext(route);
        brokerByRoute.put(route.routeId, brokerBatch);
        Map<String, Object> context = new LinkedHashMap<>(baseRouteContext(route));
        InitialExplorationTurn previous = previousByRoute.get(route.routeId);
        ComputationTrace priorComputation = computationByRoute.get(route.routeId);
        context.put("segment_index", route.checkpoint.segmentIndex() + segment + 1);
        context.put("committed_checkpoint", route.checkpoint);
        context.put("previous_public_turn", previous == null ? "none" : previous);
        context.put(
            "previous_computation",
            priorComputation == null ? "none" : priorComputation.publicView());
        context.put(
            "computation_decision",
            priorComputation == null ? "none" : priorComputation.decision());
        context.put(
            "computation_result",
            priorComputation == null || priorComputation.result() == null
                ? "not_executed"
                : priorComputation.result());
        context.put("broker_artifacts", brokerBatch.artifacts());
        context.put("broker_artifact_provider_request_id", brokerBatch.providerRequestId());
        context.put("broker_artifact_usage_contract", brokerBatch.usageInstruction());
        context.put("verified_facts", typedMemory.factsForRoute(route.routeId));
        context.put("negative_memory", typedMemory.negativesForRoute(route.routeId));
        context.put(
            "active_research_findings", researchCheckpoints.activeFindings(route.routeId));
        context.put(
            "completed_checkpoint_frames", researchCheckpoints.checkpointsForRoute(route.routeId));
        context.put(
            "finding_accounting_rule",
            "Every active research finding remains active unless explicitly deferred, promoted to "
                + "an issue-003 attempt candidate, rejected with a reason, or superseded.");
        context.put(
            "continuation_rule",
            "Continue only from the committed checkpoint. Return a complete auditable attempt, "
                + "request one bounded computation, or explicitly abandon; never invent a result.");
        contextByRoute.put(
            route.routeId,
            java.util.Collections.unmodifiableMap(new LinkedHashMap<>(context)));
      }
      if (brokerByRoute.values().stream().anyMatch(batch -> !batch.deliveries().isEmpty())) {
        persistUnchecked("broker_prompt_consumption", false);
      }

      List<AuthoritativeWorkSpec> specs = new ArrayList<>();
      Map<String, RouteState> routeById = new LinkedHashMap<>();
      int workOrdinal = 0;
      for (RouteState route : active) {
        routeById.put(route.routeId, route);
        List<ResearchWorkKind> kinds =
            route.focusedCanonicalTargetId.isBlank()
                ? List.of(ResearchWorkKind.ROUTE_EXPLORATION)
                : List.of(
                    ResearchWorkKind.FOCUSED_PROVER,
                    ResearchWorkKind.FOCUSED_FALSIFIER,
                    ResearchWorkKind.FOCUSED_REPROVER,
                    ResearchWorkKind.DEPENDENCY_AUDITOR);
        for (ResearchWorkKind kind : kinds) {
          specs.add(
              new AuthoritativeWorkSpec(
                  kind,
                  route.routeId,
                  "",
                  route.focusObligationId,
                  route.focusedCanonicalTargetId,
                  focusedResearchRole(kind),
                  AgentLeaseClass.RESEARCH,
                  Set.of(),
                  new ResearchWorkReadSet(
                      Set.of(currentResearchAuthorityAnchor().stableHash()),
                      Set.of("route-checkpoint://" + route.checkpoint.checkpointId())),
                  new ResearchWorkConflictSet(
                      Set.of(route.routeId + "#" + kind.name()),
                      Set.of(),
                      Set.of(),
                      Set.of(),
                      Set.of()),
                  "route-checkpoint://"
                      + route.checkpoint.checkpointId()
                      + "#"
                      + kind.name().toLowerCase(Locale.ROOT),
                  ExplorationTurnDraft.class.getName(),
                  workOrdinal++));
        }
      }
      int segmentIndex = segment;
      AuthoritativeEpochRun epochRun =
          executeAuthoritativeEpoch(
              "route-exploration-r" + roundIndex.get() + "-s" + segment,
              specs,
              (frozen, item) ->
                  executeExplorationTurnAgainstFrozenSnapshot(
                      frozen, item, routeById, contextByRoute, segmentIndex),
              results ->
                  commitExplorationResultsInStableOrder(
                      results,
                      routeById,
                      brokerByRoute,
                      previousByRoute,
                      computationByRoute,
                      maximumSegments,
                      segmentIndex));
      epochRun.results().stream()
          .filter(result -> result.status() == ResearchWorkResultStatus.FAILED)
          .map(result -> researchTasks.require(result.workItemId()).item().routeId())
          .distinct()
          .map(routeById::get)
          .filter(Objects::nonNull)
          .forEach(
              route ->
                  failIsolatedRoute(
                      route, new IllegalStateException("research epoch worker failed")));
    }

    for (RouteState route : pending) {
      ExplorationAdmission admission = admissionByRoute.get(route.routeId);
      if (admission == null) {
        continue;
      }
      if (route.attempt == null
          && !Set.of("abandoned", "failed", "waiting").contains(route.status)) {
        route.status = "partial";
        route.failureReason = "continuation segment limit reached without a complete attempt";
      }
      int charged =
          Math.max(
              0,
              safeInt(ledger.totals().calls())
                  - callsBeforeByRoute.getOrDefault(route.routeId, safeInt(ledger.totals().calls())));
      boolean produced = route.attempt != null;
      deepExploration.complete(
          admission,
          signatureByRoute.get(route.routeId),
          new ExplorationOutcome(
              produced,
              produced,
              false,
              produced ? ExplorationOutcome.Failure.NONE : ExplorationOutcome.Failure.NO_ARTIFACT,
              charged,
              produced ? "proof attempt produced" : route.failureReason));
    }
  }

  private static String focusedResearchRole(ResearchWorkKind kind) {
    return switch (kind) {
      case FOCUSED_FALSIFIER -> "counterexample_hunter";
      case FOCUSED_REPROVER -> "bridge_prover";
      case DEPENDENCY_AUDITOR -> "route_referee";
      default -> "explorer";
    };
  }

  private void failIsolatedRoute(RouteState route, RuntimeException failure) {
    route.status = "failed";
    route.failureReason = failure.getClass().getSimpleName();
    event(
        "agent_failed",
        "isolated_exploration",
        route.author.id(),
        "failed",
        "Isolated route stopped without contaminating sibling routes: "
            + failure.getClass().getSimpleName(),
        null);
  }

  private ResearchWorkResultEnvelope executeExplorationTurnAgainstFrozenSnapshot(
      FrozenResearchSnapshot frozen,
      ResearchWorkItem item,
      Map<String, RouteState> routeById,
      Map<String, Map<String, Object>> contextByRoute,
      int segment) {
    RouteState source = Objects.requireNonNull(routeById.get(item.routeId()), "route");
    RouteState draftRoute = copyRouteState(source);
    Map<String, Object> workerContext =
        new LinkedHashMap<>(Objects.requireNonNull(contextByRoute.get(source.routeId), "context"));
    workerContext.put("frozen_work_kind", item.kind().name());
    workerContext.put("focused_matrix_role", item.requiredRole());
    workerContext.put(
        "focused_matrix_authority_rule",
        "Return one immutable perspective artifact. Only the stable single-writer commit may mutate the route.");
    ResearchWorkerContext worker =
        new ResearchWorkerContext(
            ResearchCheckpointLedger.restore(researchCheckpoints.snapshot()),
            draftRoute,
            item.requiredRole(),
            false);
    activeResearchWorker.set(worker);
    try {
      StructuredCallResult<InitialExplorationTurn> call =
          callStage(
              "explore-"
                  + source.routeId
                  + "-r"
                  + roundIndex.get()
                  + "-s"
                  + segment
                  + (item.kind() == ResearchWorkKind.ROUTE_EXPLORATION
                      ? ""
                      : "-" + item.kind().name().toLowerCase(Locale.ROOT)),
              "independent_exploration",
              InitialExplorationTurn.class,
              java.util.Collections.unmodifiableMap(new LinkedHashMap<>(workerContext)),
              source.author,
              "depth",
              "Exploring " + source.routeId + " segment " + (segment + 1));
      ExplorationTurnDraft draft =
          new ExplorationTurnDraft(
              call.value(),
              call.runId(),
              call.callId(),
              call.agentId(),
              call.provider(),
              call.model(),
              call.promptArtifactRef(),
              call.responseArtifactRef(),
              call.usage(),
              call.repaired(),
              call.attemptedAgents(),
              worker.researchCheckpoints.snapshot(),
              draftRoute.latestResearchCheckpointId,
              draftRoute.lastCheckpointedProviderCallId,
              draftRoute.activeResearchFindingIds,
              draftRoute.checkpointRecoveryCount);
      return new ResearchWorkResultEnvelope(
          item.workItemId(),
          frozen.epochId(),
          frozen.snapshotHash(),
          call.agentId(),
          call.callId(),
          ResearchWorkResultStatus.SUCCEEDED,
          Map.of("draft", ContractObjectMapper.toTree(draft)),
          List.of(draft.latestResearchCheckpointId()),
          List.of(),
          List.of(call.callId()));
    } finally {
      activeResearchWorker.remove();
    }
  }

  private void commitExplorationResultsInStableOrder(
      List<ResearchWorkResultEnvelope> results,
      Map<String, RouteState> routeById,
      Map<String, BrokerArtifactPromptBatch> brokerByRoute,
      Map<String, InitialExplorationTurn> previousByRoute,
      Map<String, ComputationTrace> computationByRoute,
      int maximumSegments,
      int segment) {
    Map<String, List<ResearchWorkResultEnvelope>> resultsByRoute = new LinkedHashMap<>();
    for (ResearchWorkResultEnvelope result : results) {
      ResearchWorkItem item = researchTasks.require(result.workItemId()).item();
      resultsByRoute.computeIfAbsent(item.routeId(), ignored -> new ArrayList<>()).add(result);
    }
    for (Map.Entry<String, List<ResearchWorkResultEnvelope>> routeResults :
        resultsByRoute.entrySet()) {
      RouteState route = Objects.requireNonNull(routeById.get(routeResults.getKey()), "route");
      List<ExplorationTurnDraft> drafts =
          routeResults.getValue().stream().map(this::explorationDraft).toList();
      for (ExplorationTurnDraft workerDraft : drafts) {
        mergeResearchCheckpointDraft(route.routeId, workerDraft.researchCheckpoints());
      }
      ExplorationTurnDraft draft =
          routeResults.getValue().stream()
              .min(
                  java.util.Comparator.comparingInt(
                      result ->
                          explorationCommitPriority(
                              researchTasks.require(result.workItemId()).item().kind())))
              .map(this::explorationDraft)
              .orElseThrow();
      route.latestResearchCheckpointId = draft.latestResearchCheckpointId();
      route.lastCheckpointedProviderCallId = draft.lastCheckpointedProviderCallId();
      route.checkpointRecoveryCount =
          drafts.stream()
              .mapToInt(ExplorationTurnDraft::checkpointRecoveryCount)
              .max()
              .orElse(route.checkpointRecoveryCount);
      refreshRouteResearchProjection(route);
      InitialExplorationTurn turn = draft.turn();
      BrokerArtifactPromptBatch brokerBatch = brokerByRoute.get(route.routeId);
      if (turn.brokerArtifactUseManifest() != null) {
        if (brokerBatch == null
            || !brokerBatch.providerRequestId()
                .equals(turn.brokerArtifactUseManifest().providerRequestId())) {
          throw new IllegalArgumentException("ARTIFACT_USE_MANIFEST_PROVIDER_REQUEST_MISMATCH");
        }
        mathematicalArtifactBroker.stageUseManifest(turn.brokerArtifactUseManifest());
      }
      previousByRoute.put(route.routeId, turn);
      route.segmentCount++;
      StructuredCallResult<InitialExplorationTurn> call = draft.callResult();
      if (turn.action() == InitialExplorationAction.SUBMIT_ATTEMPT && turn.attempt() != null) {
        route.attempt =
            bindAttempt(
                turn.attempt(),
                call,
                route.author,
                route.routeId,
                route.strategy.strategyId(),
                problemHash,
                roundIndex.get());
        route.pendingFindingReconciliation =
            !researchCheckpoints.activeFindings(route.routeId).isEmpty();
        reconcileSubmittedAttemptFindings(route);
        attachPendingPivotProposedClaims(route);
        route.status = "submitted";
        continue;
      }
      if (turn.action() == InitialExplorationAction.REQUEST_COMPUTATION
          && turn.experimentSpec() != null) {
        ComputationTrace prior = runComputation(route, turn.experimentSpec(), segment);
        computationByRoute.put(route.routeId, prior);
        boolean verifiedGain =
            prior.result() != null && prior.result().independentlyVerified();
        ProofControlModels.GateVerdict verdict =
            proofControl
                .gates()
                .continueDeepening(
                    proofControlMode(),
                    route.routeId,
                    route.segmentCount,
                    route.noProgressSegments,
                    false,
                    false,
                    false,
                    verifiedGain,
                    Math.max(1, maximumSegments - 1))
                .verdict();
        route.noProgressSegments = verifiedGain ? 0 : route.noProgressSegments + 1;
        if (!verifiedGain || verdict == ProofControlModels.GateVerdict.BLOCK) {
          route.status = "partial";
          route.failureReason =
              "continuation stopped because the preceding segment had no certified gain";
        }
        continue;
      }
      researchCheckpoints.deferRouteEnd(route.routeId);
      refreshRouteResearchProjection(route);
      route.status = "abandoned";
      route.failureReason = "prover abandoned the current strategy after bounded exploration";
    }
  }

  private ExplorationTurnDraft explorationDraft(ResearchWorkResultEnvelope envelope) {
    return ContractObjectMapper.read(
        ContractObjectMapper.toTree(envelope.publicStructuredResult().get("draft")),
        ExplorationTurnDraft.class);
  }

  private static int explorationCommitPriority(ResearchWorkKind kind) {
    return switch (kind) {
      case ROUTE_EXPLORATION, FOCUSED_PROVER -> 0;
      case FOCUSED_REPROVER -> 1;
      case DEPENDENCY_AUDITOR -> 2;
      case FOCUSED_FALSIFIER -> 3;
      default -> 4;
    };
  }

  private void mergeResearchCheckpointDraft(
      String routeId,
      io.github.aililuola.mathproofmesh.research.ResearchCheckpointSnapshot worker) {
    io.github.aililuola.mathproofmesh.research.ResearchCheckpointSnapshot current =
        researchCheckpoints.snapshot();
    Map<String, ResearchCheckpointRecord> checkpointsById =
        new LinkedHashMap<>(current.checkpoints());
    worker.checkpoints().values().stream()
        .filter(record -> record.routeId().equals(routeId))
        .forEach(
            record -> {
              ResearchCheckpointRecord prior =
                  checkpointsById.putIfAbsent(record.checkpointId(), record);
              if (prior != null && !prior.equals(record)) {
                throw new IllegalStateException("research checkpoint worker result conflicted");
              }
            });
    Map<String, ResearchFindingRecord> findingsById = new LinkedHashMap<>(current.findings());
    Set<String> changedFindings = new LinkedHashSet<>();
    worker.findings().values().stream()
        .filter(record -> record.routeId().equals(routeId))
        .forEach(
            record -> {
              ResearchFindingRecord prior = findingsById.get(record.findingId());
              if (prior == null || prior.version() < record.version()) {
                findingsById.put(record.findingId(), record);
                changedFindings.add(record.findingId());
              } else if (prior.version() == record.version() && !prior.equals(record)) {
                throw new IllegalStateException("research finding worker result conflicted");
              }
            });
    List<io.github.aililuola.mathproofmesh.research.ResearchFindingAuditEvent> audit =
        new ArrayList<>(current.audit());
    Set<String> auditHashes =
        audit.stream().map(CanonicalJson::stableHash).collect(java.util.stream.Collectors.toSet());
    worker.audit().stream()
        .filter(event -> ResearchFindingUpdateBoundary.mergeable(event, changedFindings))
        .filter(event -> !auditHashes.contains(CanonicalJson.stableHash(event)))
        .forEach(
            event ->
                audit.add(
                    new io.github.aililuola.mathproofmesh.research.ResearchFindingAuditEvent(
                        audit.size(),
                        event.findingId(),
                        event.action(),
                        event.priorStatus(),
                        event.nextStatus(),
                        event.reason())));
    researchCheckpoints =
        ResearchCheckpointLedger.restore(
            new io.github.aililuola.mathproofmesh.research.ResearchCheckpointSnapshot(
                io.github.aililuola.mathproofmesh.research.ResearchCheckpointSnapshot
                    .CURRENT_SCHEMA_VERSION,
                checkpointsById,
                findingsById,
                audit));
  }

  private Map<String, Object> baseRouteContext(RouteState route) {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("immutable_problem", frozenProblem);
    context.put("problem_hash", problemHash);
    context.put(
        "campaign_research_findings",
        researchCheckpointLedger().activeFindings(CAMPAIGN_RESEARCH_ROUTE_ID));
    context.put(
        "campaign_checkpoint_frames",
        researchCheckpointLedger().checkpointsForRoute(CAMPAIGN_RESEARCH_ROUTE_ID));
    context.put(
        "campaign_research_authority_rule",
        "campaign_research_findings are global non-authoritative candidates; "
            + "active_research_findings are route-specific non-authoritative candidates. Neither "
            + "may become a Claim or Fact without explicit adoption and issue-003 Claim Review. "
            + "Do not place campaign finding IDs in finding_updates: leaving them active is implicit, "
            + "and adoption requires a new route-local proposed candidate.");
    context.put("route_id", route.routeId);
    context.put("assigned_agent_id", route.author.id());
    context.put("assigned_strategy", route.strategy);
    SemanticPivotRecord activePivot =
        route.activeSemanticPivotId.isBlank()
            ? null
            : semanticPivots.ledger().get(route.activeSemanticPivotId);
    context.put("active_semantic_pivot_delta", activePivot == null ? Map.of() : activePivot.delta());
    context.put("active_mathematical_object_ids", List.copyOf(route.activeMathematicalObjectIds));
    context.put("active_direction_signature", route.activeDirectionSignature);
    context.put(
        "retained_verified_claim_ids",
        activePivot == null
            ? List.of()
            : activePivot.delta().claimUseChanges().stream()
                .filter(change -> change.action() == PivotClaimUsageAction.RETAIN_AS_VERIFIED_FACT)
                .map(io.github.aililuola.mathproofmesh.proofcontrol.PivotClaimUseChange::claimId)
                .toList());
    context.put(
        "semantic_pivot_authority_rule",
        "The active PivotDelta changes only strategy state. Retained verified facts keep their "
            + "existing authority; retired targets remain historically present and keep their "
            + "mathematical status.");
    context.put("migration_parity_requirements", strategyGenerationGuidance());
    context.put(
        "focused_obligation",
        findObligation(route.focusObligationId).map(ProofObligation::statement).orElse("none"));
    context.put("focused_obligation_id", route.focusObligationId);
    Optional<CanonicalObligationRecord> focusedCanonical =
        route.focusedCanonicalTargetId.isBlank()
            ? proofGraph.canonicalTargetForObligation(route.focusObligationId)
            : proofGraph.allCanonicalTargets().stream()
                .filter(
                    target ->
                        target.canonicalTargetId().equals(route.focusedCanonicalTargetId))
                .findFirst();
    Optional<BottleneckFamilyRecord> focusedFamily =
        route.focusedBottleneckFamilyId.isBlank()
            ? focusedCanonical.flatMap(
                target ->
                    proofGraph.bottleneckFamilyForCanonical(target.canonicalTargetId()))
            : proofGraph.allBottleneckFamilies().stream()
                .filter(family -> family.familyId().equals(route.focusedBottleneckFamilyId))
                .findFirst();
    context.put(
        "focused_raw_obligation",
        findObligation(route.focusObligationId)
            .map(
                obligation ->
                    Map.of(
                        "obligation_id", obligation.obligationId(),
                        "statement", obligation.statement(),
                        "status", obligation.status()))
            .orElse(Map.of()));
    context.put(
        "focused_canonical_target",
        focusedCanonical.map(DesktopSolveCoordinator::canonicalPromptView).orElse(Map.of()));
    context.put(
        "focused_dependency_plan",
        proofGraph.rawObligationOccurrences().stream()
            .filter(occurrence -> occurrence.obligationId().equals(route.focusObligationId))
            .map(
                occurrence ->
                    Map.of(
                        "dependency_plan_signature", occurrence.dependencyPlanSignature(),
                        "source_type", occurrence.sourceType().name()))
            .findFirst()
            .orElse(Map.of()));
    context.put(
        "focused_bottleneck_family",
        focusedFamily.map(DesktopSolveCoordinator::familyPromptView).orElse(Map.of()));
    context.put(
        "canonical_open_targets",
        proofGraph.canonicalOpenTargets(route.routeId).stream()
            .limit(8)
            .map(DesktopSolveCoordinator::canonicalPromptView)
            .toList());
    context.put(
        "bottleneck_family_summary",
        proofGraph.activeBottleneckFamilies().stream()
            .filter(
                family ->
                    family.canonicalTargetIds().stream()
                        .anyMatch(
                            id ->
                                proofGraph.allCanonicalTargets().stream()
                                    .filter(target -> target.canonicalTargetId().equals(id))
                                    .anyMatch(target -> target.routeIds().contains(route.routeId))))
            .limit(6)
            .map(DesktopSolveCoordinator::familyPromptView)
            .toList());
    context.put(
        "canonicalization_authority_rule",
        "Canonical targets deduplicate scheduling only. Bottleneck families are research "
            + "focus groups, never mathematical equivalence, Claim, Fact, proof, refutation, "
            + "or authority to propagate status between raw obligations.");
    context.put("proof_graph_control_mode", proofGraphConvergence.controlMode().name());
    Optional<FocusedRecoveryBrief> recoveryBrief = focusedRecoveryBrief(route);
    context.put(
        "focused_recovery_brief", recoveryBrief.map(value -> (Object) value).orElse(Map.of()));
    context.put(
        "selected_bottleneck_family",
        recoveryBrief.map(FocusedRecoveryBrief::selectedFamilyId).orElse(""));
    context.put(
        "selected_canonical_targets",
        recoveryBrief.map(FocusedRecoveryBrief::canonicalMemberIds).orElse(List.of()));
    context.put(
        "allowed_recovery_actions",
        recoveryBrief.map(FocusedRecoveryBrief::allowedActions).orElse(List.of()));
    context.put(
        "blocked_generic_actions",
        recoveryBrief.map(FocusedRecoveryBrief::blockedGenericActions).orElse(List.of()));
    context.put(
        "new_target_quota_remaining",
        recoveryBrief.map(FocusedRecoveryBrief::newTargetQuotaRemaining).orElse(0));
    context.put(
        "focused_recovery_authority_rule",
        "Do not treat family members as equivalent. Do not close or refute sibling targets. "
            + "Do not introduce unrelated high-level strategies during focused recovery.");
    context.put("focus_source", route.focusSource);
    context.put(
        "strategy_blueprint",
        strategyBlueprints.getOrDefault(route.strategy.strategyId(), null));
    context.put("route_team", route.plan);
    context.put(
        "registered_computation_contracts",
        ContractsFunctions.experimentToolCatalog(
            new LinkedHashSet<>(frozenProblem.allowedTools())));
    context.put(
        "falsification_contract",
        proofControl
            .falsification()
            .compile(
                route.strategy.falsificationTest(),
                routeObligationId(route.routeId),
                config.computation().maxCasesPerExperiment()));
    context.put("sandbox_available", sandboxEnabled);
    context.put(
        "computation_evidence_contract",
        Map.of(
            "target_obligation_id",
            route.focusObligationId.isBlank()
                ? routeObligationId(route.routeId)
                : route.focusObligationId,
            "required_scope",
            "Finite typed domains with explicit bounds and one target proposition",
            "counterexample_output",
            "Return the falsifying assignment, exact observed value, and deterministic replay inputs",
            "authority",
            "refuted closes dependent routes; not_refuted is Insight only; verified_bounded proves only the stated finite domain; inconclusive has no supporting authority"));
    context.put(
        "action_rule",
        "Return submit_attempt, request_computation, or abandon. Computation is bounded evidence, never proof by itself.");
    return context;
  }

  private Optional<FocusedRecoveryBrief> focusedRecoveryBrief(RouteState route) {
    FocusedRecoveryPlan plan = proofGraphConvergence.focusedRecoveryPlan().orElse(null);
    if (plan == null || proofGraphConvergence.controlMode() == ProofGraphControlMode.NORMAL_EXPANSION) {
      return Optional.empty();
    }
    List<String> members = plan.selectedCanonicalTargetIds().stream().sorted().toList();
    Map<String, String> statements = new LinkedHashMap<>();
    Map<String, Set<String>> dependencyPlans = new LinkedHashMap<>();
    proofGraph.allCanonicalTargets().stream()
        .filter(target -> plan.selectedCanonicalTargetIds().contains(target.canonicalTargetId()))
        .forEach(
            target -> {
              statements.put(
                  target.canonicalTargetId(),
                  proofGraph.representativeStatement(target.canonicalTargetId()));
              dependencyPlans.put(
                  target.canonicalTargetId(), target.dependencyPlanSignatures());
            });
    BottleneckFamilyRecord family =
        plan.selectedFamilyId().isBlank()
            ? null
            : proofGraph.allBottleneckFamilies().stream()
                .filter(item -> item.familyId().equals(plan.selectedFamilyId()))
                .findFirst()
                .orElse(null);
    List<String> findings =
        java.util.stream.Stream.concat(
                researchCheckpointLedger().activeFindings(CAMPAIGN_RESEARCH_ROUTE_ID).stream(),
                researchCheckpointLedger().activeFindings(route.routeId).stream())
            .map(io.github.aililuola.mathproofmesh.research.ResearchFindingRecord::statement)
            .distinct()
            .limit(12)
            .toList();
    String sharpObstruction =
        java.util.stream.Stream.concat(
                researchCheckpointLedger().activeFindings(CAMPAIGN_RESEARCH_ROUTE_ID).stream(),
                researchCheckpointLedger().activeFindings(route.routeId).stream())
            .filter(item -> "SHARP_OBSTRUCTION".equals(item.kind().name()))
            .map(io.github.aililuola.mathproofmesh.research.ResearchFindingRecord::statement)
            .findFirst()
            .orElse("");
    List<String> permanentNegatives =
        negativeKnowledgeRegistry.records().stream()
            .filter(item -> item.problemHash().equals(problemHash) && item.permanent())
            .map(io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeRecord::statement)
            .distinct()
            .limit(12)
            .toList();
    List<String> exactCounterexamples =
        negativeKnowledgeRegistry.records().stream()
            .filter(item -> item.problemHash().equals(problemHash) && item.permanent())
            .filter(
                item ->
                    item.kinds().stream()
                        .anyMatch(kind -> "VERIFIED_COUNTEREXAMPLE".equals(kind.name())))
            .map(io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeRecord::statement)
            .distinct()
            .limit(12)
            .toList();
    List<FocusedRecoveryActionType> allowed =
        java.util.Arrays.stream(FocusedRecoveryActionType.values())
            .filter(FocusedRecoveryActionType::recoveryAction)
            .toList();
    List<FocusedRecoveryActionType> blocked =
        java.util.Arrays.stream(FocusedRecoveryActionType.values())
            .filter(action -> !action.recoveryAction())
            .toList();
    return Optional.of(
        new FocusedRecoveryBrief(
            immutableRootGoalHash(),
            plan.selectedFamilyId(),
            family == null ? "single canonical target" : family.label(),
            members,
            statements,
            dependencyPlans,
            typedMemory.facts().stream()
                .map(MessageEnvelope::statement)
                .distinct()
                .limit(12)
                .toList(),
            exactCounterexamples,
            permanentNegatives,
            findings,
            sharpObstruction,
            allowed,
            blocked,
            plan.quotaRemaining()));
  }

  private static Map<String, Object> canonicalPromptView(CanonicalObligationRecord target) {
    return Map.of(
        "canonical_target_id", target.canonicalTargetId(),
        "statement", target.signature().normalizedStatement(),
        "route_count", target.routeIds().size(),
        "dependency_plan_count", target.dependencyPlanSignatures().size(),
        "scheduling_state", target.schedulingState().name());
  }

  private static Map<String, Object> familyPromptView(BottleneckFamilyRecord family) {
    return Map.of(
        "family_id", family.familyId(),
        "label", family.label(),
        "representative_canonical_target_id", family.representativeCanonicalTargetId(),
        "target_count", family.canonicalTargetIds().size(),
        "scheduling_state", family.schedulingState().name());
  }

  private ObligationCreationContext obligationContext(
      RouteState route,
      ObligationSourceType sourceType,
      String sourceArtifactRef,
      String bottleneckKey,
      String bottleneckLabel) {
    return new ObligationCreationContext(
        problemHash,
        route.routeId,
        route.strategy.strategyId(),
        sourceType,
        sourceArtifactRef,
        List.of(),
        "",
        Map.of(),
        bottleneckKey,
        bottleneckLabel,
        BottleneckRelationType.SHARES_UPSTREAM_BOTTLENECK,
        ObligationOccurrenceSchedulingState.ACTIVE,
        roundIndex.get());
  }

  private ControlledObligationWrite addControlledObligation(
      ProofObligation obligation,
      ObligationCreationContext context,
      FocusedRecoveryActionType actionType) {
    ObligationControlAdmission admission =
        previewObligationControl(obligation, context, actionType);
    return addObligationThroughControl(obligation, admission);
  }

  private ObligationControlAdmission previewObligationControl(
      ProofObligation obligation,
      ObligationCreationContext context,
      FocusedRecoveryActionType actionType) {
    Optional<String> existingCanonical =
        proofGraph.existingCanonicalTargetId(obligation, context);
    String canonicalTargetId = existingCanonical.orElse("");
    String familyId =
        existingCanonical
            .flatMap(proofGraph::bottleneckFamilyForCanonical)
            .map(BottleneckFamilyRecord::familyId)
            .orElse("");
    FocusedExpansionDecision decision =
        proofGraphConvergence.decideExpansion(
            actionType,
            existingCanonical.isPresent(),
            proofGraph.activeCanonicalTargetCount(context.routeId()),
            proofGraph.activeCanonicalTargetCount(),
            familyId,
            canonicalTargetId);
    proofGraphConvergence.recordExpansionDecision(
        actionType, decision.allowed(), familyId, canonicalTargetId);
    return new ObligationControlAdmission(
        context.withSchedulingState(decision.schedulingState()),
        actionType,
        decision,
        existingCanonical.isPresent());
  }

  private ControlledObligationWrite addObligationThroughControl(
      ProofObligation obligation, ObligationControlAdmission admission) {
    CanonicalizedObligationWriteResult result =
        proofGraph.addObligationCanonicalized(obligation, admission.context());
    if (admission.decision().deferred()) {
      deferredExpansions.record(
          problemHash,
          roundIndex.get(),
          admission.context().routeId(),
          obligation.obligationId(),
          result.canonicalTarget().canonicalTargetId(),
          admission.actionType(),
          admission.decision());
      event(
          "proof_obligation_deferred_by_graph_control",
          "proof_control",
          null,
          "warning",
          admission.decision().code(),
          obligation.obligationId());
    } else if (!admission.existingCanonicalTarget()
        && proofGraphConvergence.controlMode() == ProofGraphControlMode.FOCUSED_RECOVERY) {
      proofGraphConvergence.recordFocusedNewTarget();
    }
    return new ControlledObligationWrite(result, admission.decision());
  }

  private void addBlueprintObligations(RouteState route) {
    StrategyBlueprintCompiler.Compilation compilation =
        strategyBlueprints.get(route.strategy.strategyId());
    if (compilation == null) {
      return;
    }
    StrategyBlueprintCompiler.Blueprint blueprint = compilation.blueprint();
    Set<String> obligationProposalIds = Set.copyOf(compilation.obligationProposals());
    Set<String> created = new LinkedHashSet<>();
    for (StrategyBlueprintCompiler.Node node : blueprint.nodes()) {
      if (node.kind() == ProofControlModels.BlueprintNodeKind.GIVEN
          || node.kind() == ProofControlModels.BlueprintNodeKind.TARGET
          || ("critical_claim".equals(node.sourceField())
              && !obligationProposalIds.contains(node.id()))) {
        continue;
      }
      if (proofGraph.obligations().stream()
          .noneMatch(obligation -> obligation.obligationId().equals(node.id()))) {
        ObligationKind kind =
            node.kind() == ProofControlModels.BlueprintNodeKind.CONSTRUCTION
                ? ObligationKind.CONSTRUCTION
                : node.kind() == ProofControlModels.BlueprintNodeKind.COMPUTATION_TASK
                    ? ObligationKind.COMPUTATION_QUESTION
                    : ObligationKind.LEMMA;
        ProofObligation blueprintObligation =
            new ProofObligation(
                List.of(),
                0.65d,
                "",
                List.of(),
                List.of(),
                List.of(),
                null,
                kind,
                topology.mathNormalize(node.statement()),
                node.id(),
                0.75d,
                problemHash,
                List.of(),
                List.of(route.routeId),
                node.statement(),
                "open");
        addControlledObligation(
            blueprintObligation,
            obligationContext(
                route,
                ObligationSourceType.STRATEGY_BLUEPRINT,
                "blueprint://" + blueprint.id() + "/" + node.id(),
                topology.mathNormalize(route.strategy.bottleneck()),
                route.strategy.bottleneck()),
            FocusedRecoveryActionType.NEW_STRATEGY);
      }
      created.add(node.id());
    }
    for (StrategyBlueprintCompiler.Edge edge : blueprint.edges()) {
      String source =
          edge.sourceId().equals(blueprint.mainGoalNodeId()) ? MAIN_GOAL_ID : edge.sourceId();
      String target =
          edge.targetId().equals(blueprint.mainGoalNodeId()) ? MAIN_GOAL_ID : edge.targetId();
      if ((!created.contains(source) && !MAIN_GOAL_ID.equals(source))
          || (!created.contains(target) && !MAIN_GOAL_ID.equals(target))) {
        continue;
      }
      if (source.equals(target)) {
        continue;
      }
      proofGraph.addEdge(
          new ProofGraphEdge(
              edge.id(),
              "depends_on".equals(edge.relation())
                  ? GraphEdgeType.DEPENDS_ON
                  : GraphEdgeType.IMPLIES,
              null,
              route.routeId,
              source,
              target));
    }
  }

  private BrokerArtifactPromptBatch consumeBrokerContext(RouteState route) {
    String providerRequestId =
        "artifact-prompt-" + route.routeId + "-" + roundIndex.get() + "-" + route.segmentCount;
    Set<String> openTargets =
        proofGraph.obligations().stream()
            .filter(obligation -> obligation.routeIds().contains(route.routeId))
            .filter(obligation -> "open".equals(obligation.status()))
            .map(ProofObligation::obligationId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    Set<String> verifiedClaims =
        typedMemory.facts().stream()
            .map(MessageEnvelope::messageId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    Set<String> refutedClaims = refutedClaimIdsForRoute(route);
    Set<String> committedSteps =
        route.attempt == null
            ? Set.of()
            : route.attempt.proofSteps().stream()
                .map(ProofStep::stepId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    Set<String> computationPlanIds =
        computationTracesForRoute(route.routeId).stream()
            .map(trace -> trace.spec().experimentId())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    BrokerArtifactPromptBatch batch =
        mathematicalArtifactBroker.consumeForPrompt(
            route.routeId,
            providerRequestId,
            roundIndex.get(),
            config.topology().crossRoute().maxMessagesPerRoutePerRound(),
            Math.max(0.0d, proofGraph.canonicalProofDebt(route.routeId)),
            openTargets,
            verifiedClaims,
            refutedClaims,
            committedSteps,
            route.retiredActiveClaimIds,
            mathematicalArtifactBroker.boundEffectIdsForRoute(
                route.routeId, BrokerArtifactUseKind.TRIGGERS_LOCAL_REPAIR),
            route.semanticPivotIds,
            computationPlanIds,
            route.activeStrategyEpochId,
            route.focusedCanonicalTargetId);
    if (!batch.artifacts().isEmpty()) {
      event(
          "broker_artifacts_consumed",
          "cross_route_broker",
          route.author.id(),
          "completed",
          "Consumed " + batch.artifacts().size() + " relevant typed mathematical artifacts",
          batch.providerRequestId());
    }
    return batch;
  }

  private ComputationTrace runComputation(
      RouteState route, ExperimentSpec proposed, int segment) {
    ExperimentSpec spec = bindExperiment(proposed, route.routeId, route.author.id());
    ComputationBroker.PreparedDecision prepared =
        computation.decide(
            spec,
            ComputationContext.initial(route.routeId, safeInt(ledger.remainingCalls())));
    if (repairableComputationDecision(prepared.decision())) {
      prepared = repairComputationContract(route, prepared, segment);
    }
    ComputationTargetBinding targetBinding = bindComputationTarget(route, spec);
    ComputationTrace trace =
        executePreparedComputation(route, prepared, segment, targetBinding, 0);
    ComputationAudit audit = auditComputation(trace);
    if (audit != null && !audit.valid()) {
      event(
          "computation_replay_repair_requested",
          "computation_contract_repair",
          route.author.id(),
          "warning",
          "Independent replay failed once; one semantics-preserving contract repair is allowed",
          audit.recordedResultHash());
      ComputationBroker.PreparedDecision repaired =
          repairComputationContract(
              route,
              replayFailurePrepared(trace.spec(), trace.decision(), audit.diagnostic()),
              segment);
      if (repaired.decision().decision() == ComputationDecisionStatus.ALLOW) {
        ComputationTrace retry =
            executePreparedComputation(route, repaired, segment, targetBinding, 1);
        ComputationAudit retryAudit = auditComputation(retry);
        trace = retry;
        audit = retryAudit;
      }
    }
    ComputationExecutionOutcome executionOutcome =
        computation
            .executionService()
            .lastOutcome(trace.spec().experimentId())
            .orElse(null);
    boolean replayValid =
        audit != null
            && audit.valid()
            && executionOutcome != null
            && executionOutcome.verificationReceipt().valid();
    ComputationEvidenceGate.EvidenceAuthority authority =
        replayValid
            ? executionOutcome.authority()
            : ComputationEvidenceGate.EvidenceAuthority.INCONCLUSIVE;
    trace =
        new ComputationTrace(
            trace.routeId(),
            trace.spec(),
            trace.decision(),
            trace.program(),
            trace.result(),
            targetBinding,
            authority,
            replayValid);
    upsertComputationTrace(trace);
    mathematicalArtifactBroker.bindEffectTarget(
        route.routeId,
        BrokerArtifactUseKind.SUPPORTS_COMPUTATION_PLAN,
        trace.spec().experimentId(),
        Set.of(),
        Set.of(targetBinding.obligationId()));
    if (executionOutcome != null) {
      applyComputationAuthorityAtomically(route, trace, executionOutcome);
    }
    event(
        "computation",
        "working_delta",
        route.author.id(),
        trace.decision().decision() == ComputationDecisionStatus.ALLOW ? "completed" : "rejected",
        trace.decision().reason() + "; authority=" + authority.name().toLowerCase(Locale.ROOT),
        trace.result() == null ? null : trace.result().experimentId());
    return trace;
  }

  private ComputationTargetBinding bindComputationTarget(
      RouteState route, ExperimentSpec spec) {
    Optional<ComputationTargetBinding> exact = exactComputationTarget(route, spec);
    if (exact.isPresent()) {
      return exact.orElseThrow();
    }
    String id = "computation-obligation-" + spec.requestHash().substring(0, 16);
    if (findObligation(id).isEmpty()) {
      ProofObligation computationObligation =
          new ProofObligation(
              spec.assumptions(),
              0.45d,
              "",
              List.of(),
              List.of(),
              List.of(),
              null,
              ObligationKind.COMPUTATION_QUESTION,
              topology.mathNormalize(spec.targetClaim()),
              id,
              0.55d,
              problemHash,
              List.of(),
              List.of(route.routeId),
              spec.targetClaim(),
              "open");
      String computationBottleneck =
          route.failure == null || route.failure.firstErrorFingerprint() == null
              ? topology.mathNormalize(spec.targetClaim())
              : route.failure.firstErrorFingerprint();
      addControlledObligation(
          computationObligation,
          obligationContext(
              route,
              ObligationSourceType.COMPUTATION_TARGET,
              "experiment://" + spec.experimentId(),
              computationBottleneck,
              spec.targetClaim()),
          FocusedRecoveryActionType.EXACT_FALSIFICATION);
    }
    CanonicalObligationRecord canonical =
        proofGraph
            .canonicalTargetForObligation(id)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "isolated computation question has no canonical target"));
    return new ComputationTargetBinding(
        problemHash,
        "",
        "",
        "",
        id,
        canonical.signature().signatureHash(),
        canonical.canonicalTargetId(),
        computationScopeHash(spec),
        canonical.signature().polarity(),
        true,
        null);
  }

  private Optional<ComputationTargetBinding> exactComputationTarget(
      RouteState route, ExperimentSpec spec) {
    ClaimEvidenceSemanticBinding requested = spec.claimEvidenceSemanticBinding();
    if (requested == null
        || spec.targetClaimId() == null
        || spec.targetClaimId().isBlank()
        || !sameHash(requested.problemHash(), problemHash)
        || !requested.claimId().equals(spec.targetClaimId())) {
      return Optional.empty();
    }
    FrozenClaimSnapshot frozen =
        claimCourt.records().stream()
            .map(ClaimCourtRecord::frozenClaim)
            .filter(value -> exactClaimBinding(requested, value, spec))
            .findFirst()
            .orElse(null);
    return proofGraph.obligations().stream()
        .filter(obligation -> obligation.routeIds().contains(route.routeId))
        .filter(obligation -> "open".equals(obligation.status()))
        .filter(obligation -> exactComputationObligation(obligation, requested, frozen, spec))
        .map(
            obligation -> {
              CanonicalObligationRecord canonical =
                  proofGraph.canonicalTargetForObligation(obligation.obligationId()).orElse(null);
              if (canonical == null) {
                return null;
              }
              String statementHash =
                  frozen == null
                      ? CanonicalJson.stableHash(obligation.statement())
                      : frozen.claimStatementHash();
              String semanticHash =
                  frozen == null
                      ? canonical.signature().signatureHash()
                      : frozen.claimSemanticHash();
              if (!sameHash(requested.claimStatementHash(), statementHash)
                  || !sameHash(requested.claimSemanticHash(), semanticHash)) {
                return null;
              }
              return new ComputationTargetBinding(
                  problemHash,
                  requested.claimId(),
                  statementHash,
                  semanticHash,
                  obligation.obligationId(),
                  canonical.signature().signatureHash(),
                  canonical.canonicalTargetId(),
                  computationScopeHash(spec),
                  canonical.signature().polarity(),
                  false,
                  null);
            })
        .filter(Objects::nonNull)
        .findFirst();
  }

  private boolean exactComputationObligation(
      ProofObligation obligation,
      ClaimEvidenceSemanticBinding requested,
      FrozenClaimSnapshot frozen,
      ExperimentSpec spec) {
    CanonicalObligationRecord canonical =
        proofGraph.canonicalTargetForObligation(obligation.obligationId()).orElse(null);
    if (canonical == null || !sameHash(obligation.problemHash(), problemHash)) {
      return false;
    }
    boolean idBound =
        requested.claimId().equals(obligation.obligationId()) || frozen != null;
    String normalizedTarget = topology.mathNormalize(spec.targetClaim());
    boolean exactStatement =
        normalizedTarget.equals(topology.mathNormalize(obligation.statement()))
            || (frozen != null
                && (normalizedTarget.equals(topology.mathNormalize(frozen.statement()))
                    || normalizedTarget.equals(topology.mathNormalize(frozen.conclusion()))));
    return idBound
        && exactStatement
        && requested.assumptions().equals(obligation.assumptions())
        && requested.quantifiers().equals(obligation.quantifiers())
        && requested.polarity().equals(canonical.signature().polarity())
        && exactScopeMarkers(requested.scopeLimitations(), canonical.signature().scopeMarkers());
  }

  private static boolean exactClaimBinding(
      ClaimEvidenceSemanticBinding requested,
      FrozenClaimSnapshot frozen,
      ExperimentSpec spec) {
    return sameHash(requested.problemHash(), frozen.problemHash())
        && requested.claimId().equals(frozen.claimId())
        && sameHash(requested.claimStatementHash(), frozen.claimStatementHash())
        && sameHash(requested.claimSemanticHash(), frozen.claimSemanticHash())
        && requested.statement().equals(frozen.statement())
        && requested.conclusion().equals(frozen.conclusion())
        && requested.assumptions().equals(frozen.assumptions())
        && requested.quantifiers().equals(frozen.quantifiers())
        && requested.variableBindings().equals(frozen.variableBindings())
        && requested.scopeLimitations().equals(frozen.scopeLimitations())
        && requested.polarity().equals(frozen.polarity())
        && requested.dependencyClaimIds().equals(frozen.dependencyClaimIds())
        && requested.computationDomains().equals(spec.domains());
  }

  private static boolean exactScopeMarkers(
      List<String> requested, List<String> canonical) {
    List<String> normalizedRequested =
        requested.stream().map(DesktopSolveCoordinator::normalizedIdentityText).sorted().toList();
    List<String> normalizedCanonical =
        canonical.stream().map(DesktopSolveCoordinator::normalizedIdentityText).sorted().toList();
    return normalizedRequested.equals(normalizedCanonical)
        || (normalizedRequested.isEmpty() && normalizedCanonical.equals(List.of("unspecified")));
  }

  private static String computationScopeHash(ExperimentSpec spec) {
    ClaimEvidenceSemanticBinding binding = spec.claimEvidenceSemanticBinding();
    return CanonicalJson.stableHash(
        Map.of(
            "assumptions", spec.assumptions(),
            "domains", spec.domains(),
            "quantifiers", binding == null ? List.of() : binding.quantifiers(),
            "variable_bindings", binding == null ? List.of() : binding.variableBindings(),
            "scope_limitations", binding == null ? List.of() : binding.scopeLimitations(),
            "polarity", binding == null ? "positive" : binding.polarity()));
  }

  private static boolean sameHash(String expected, String actual) {
    return expected != null
        && actual != null
        && MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
  }

  private ComputationTrace executePreparedComputation(
      RouteState route,
      ComputationBroker.PreparedDecision prepared,
      int segment,
      ComputationTargetBinding targetBinding,
      int executionAttempt) {
    ComputationDecision decision = prepared.decision();
    ExperimentProgram program = null;
    ExperimentResult result = null;
    if (decision.decision() == ComputationDecisionStatus.ALLOW) {
      if (prepared.spec().method() == ComputationMethod.SANDBOXED_PYTHON) {
        AgentRuntime experimenter =
            pool.select("experimenter", Set.of(route.author.id()), List.of(), null, true);
        StructuredCallResult<SandboxProgramDraft> draft =
            callStage(
                "codegen-"
                    + route.routeId
                    + "-r"
                    + roundIndex.get()
                    + "-s"
                    + segment
                    + "-a"
                    + executionAttempt,
                "experiment_codegen",
                SandboxProgramDraft.class,
                Map.of(
                    "immutable_problem", frozenProblem,
                    "experiment", prepared.spec(),
                    "sandbox_input", prepared.spec().arguments().path("input"),
                    "allowed_dependencies",
                        List.of(
                            "collections",
                            "decimal",
                            "fractions",
                            "functools",
                            "itertools",
                            "math"),
                    "program_rules",
                        List.of(
                            "Define exactly one function named run with signature run(data).",
                            "Use only direct public function calls; attribute access is forbidden.",
                            "Do not use files, network, processes, eval, exec, reflection, or private names.",
                            "Return outcome, cases_checked, scope, and exact_arithmetic.",
                            "The dependency list must exactly match imported top-level modules.")),
                experimenter,
                "depth",
                "Generating a bounded sandbox computation");
        program = sandboxProgram(prepared.spec(), draft.value());
      }
      String claimId = prepared.spec().targetClaimId();
      String claimSemanticHash =
          prepared.spec().claimEvidenceSemanticBinding() == null
              ? claimId == null || claimId.isBlank()
                  ? ""
                  : CanonicalJson.stableHash(prepared.spec().targetClaim())
              : prepared.spec().claimEvidenceSemanticBinding().claimSemanticHash();
      ComputationExecutionContext executionContext =
          new ComputationExecutionContext(
              problemHash,
              rootGoal().sourceStatementHash(),
              route.routeId,
              claimId,
              claimSemanticHash,
              targetBinding.obligationId(),
              targetBinding.canonicalTargetId(),
              roundIndex.get(),
              null);
      upsertComputationTrace(
          new ComputationTrace(
              route.routeId,
              prepared.spec(),
              decision,
              program,
              null,
              targetBinding,
              ComputationEvidenceGate.EvidenceAuthority.INCONCLUSIVE,
              false));
      result =
          computation.runExperiment(
              prepared.spec(), decision, program, executionContext);
    }
    return new ComputationTrace(
        route.routeId,
        prepared.spec(),
        decision,
        program,
        result,
        targetBinding,
        ComputationEvidenceGate.EvidenceAuthority.INCONCLUSIVE,
        false);
  }

  private void upsertComputationTrace(ComputationTrace trace) {
    synchronized (computationTraces) {
      computationTraces.removeIf(
          current ->
              current.routeId().equals(trace.routeId())
                  && current.spec().experimentId().equals(trace.spec().experimentId()));
      computationTraces.add(trace);
    }
  }

  private ComputationBroker.PreparedDecision replayFailurePrepared(
      ExperimentSpec spec, ComputationDecision decision, String diagnostic) {
    ComputationDecision rejected =
        new ComputationDecision(
            false,
            decision.canonicalRequestHash(),
            diagnostic,
            ComputationContractRepairStatus.FAILED,
            decision.createdAt(),
            ComputationDecisionStatus.REJECT,
            decision.experimentId(),
            spec.requestHash(),
            "Independent replay failed: " + diagnostic,
            decision.remainingExperiments(),
            decision.requestHash(),
            true,
            "request.replay_failed");
    return new ComputationBroker.PreparedDecision(spec, rejected);
  }

  private ComputationAudit auditComputation(ComputationTrace trace) {
    if (trace.decision().decision() != ComputationDecisionStatus.ALLOW
        || trace.result() == null) {
      return null;
    }
    ComputationAudit audit =
        computation.auditExperiment(
            trace.spec(), trace.decision(), trace.program(), trace.result());
    recordComputationAudit(audit);
    return audit;
  }

  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification = "The real mathematical projection is rolled back before failure propagation.")
  private void applyComputationAuthorityAtomically(
      RouteState route,
      ComputationTrace trace,
      ComputationExecutionOutcome executionOutcome) {
    TypedMemorySnapshot memoryBefore = typedMemory.snapshot();
    ProofGraphSnapshot graphBefore = proofGraph.snapshot();
    ComputationExecutionState computationBefore = computation.snapshot();
    try {
      AuthorityMutationIds ids = applyComputationAuthority(route, trace, executionOutcome);
      ComputationAuthorityMutationReceipt mutation =
          new ComputationAuthorityMutationReceipt(
              "computation-mutation-"
                  + CanonicalJson.stableHash(
                          Map.of(
                              "execution_id", executionOutcome.executionId(),
                              "target_binding_hash", trace.targetBinding().bindingHash(),
                              "action", executionOutcome.applicationReceipt().action().value()))
                      .substring(0, 24),
              executionOutcome.executionId(),
              trace.targetBinding().bindingHash(),
              executionOutcome.applicationReceipt().action(),
              ids.factMessageId(),
              ids.counterexampleMessageId(),
              ids.closedObligationId(),
              ids.refutedObligationId(),
              ids.claimCourtEvidenceId(),
              null);
      computation
          .executionService()
          .recordAuthorityMutation(mutation, roundIndex.get());
      computation
          .executionService()
          .completeAuthorityApplication(executionOutcome.executionId(), roundIndex.get());
    } catch (RuntimeException exception) {
      typedMemory = TypedMemory.restore(memoryBefore, memoryPolicy());
      proofGraph = ProofGraphStore.restore(graphBefore, ProofGraphPolicy.defaults());
      computation.restore(computationBefore);
      installNegativeKnowledgeRuntime();
      try {
        persistUnchecked("computation_authority_projection_rollback", false);
      } catch (RuntimeException rollbackFailure) {
        exception.addSuppressed(rollbackFailure);
      }
      throw exception;
    }
  }

  private AuthorityMutationIds applyComputationAuthority(
      RouteState route,
      ComputationTrace trace,
      ComputationExecutionOutcome executionOutcome) {
    if (trace.result() == null
        || !trace.replayValid()
        || !targetBindingStillExact(trace)) {
      event(
          "computation_evidence_disabled",
          "working_delta",
          route.author.id(),
          "warning",
          "The result has no proof authority because replay or exact target binding did not pass",
          trace.spec().experimentId());
      return AuthorityMutationIds.none();
    }
    return switch (trace.authority()) {
      case REFUTED -> applyComputationCounterexample(route, trace, executionOutcome);
      case NOT_REFUTED -> {
        typedMemory.addInsight(computationInsightMessage(route, trace));
        yield AuthorityMutationIds.none();
      }
      case VERIFIED_BOUNDED ->
          promoteComputationCertificate(route, trace, executionOutcome, false);
      case VERIFIED -> promoteComputationCertificate(route, trace, executionOutcome, true);
      case INCONCLUSIVE ->
          {
            event(
                "computation_inconclusive",
                "working_delta",
                route.author.id(),
                "warning",
                "The computation is retained for audit but cannot support a proof claim",
                trace.result().experimentId());
            yield AuthorityMutationIds.none();
          }
    };
  }

  private AuthorityMutationIds applyComputationCounterexample(
      RouteState route,
      ComputationTrace trace,
      ComputationExecutionOutcome executionOutcome) {
    ClaimEvidenceSemanticBinding claimBinding =
        trace.targetBinding().isolatedComputationQuestion()
            ? null
            : claimBindingForAuthorityProjection(trace);
    MessageEnvelope counterexample =
        computationCounterexampleMessage(route, trace, claimBinding);
    String counterexampleId = "";
    if (claimBinding != null) {
      typedMemory.applyVerifiedCounterexample(
          counterexample,
          VerifiedCounterexampleAuthority.independentReplay(
              trace.result() != null,
              trace.replayValid(),
              trace.authority(),
              "experiment://" + trace.result().experimentId(),
              claimBinding.statement(),
              trace.result().resultHash(),
              List.of()));
      counterexampleId = counterexample.messageId();
    }
    String refutedObligationId = trace.targetBinding().obligationId();
    String evidenceId =
        counterexampleId.isEmpty()
            ? "experiment://" + trace.result().experimentId()
            : counterexampleId;
    findObligation(refutedObligationId)
        .filter(obligation -> !"refuted".equals(obligation.status()))
        .ifPresent(
            obligation ->
                proofGraph.refuteObligation(
                    obligation.obligationId(), evidenceId));
    computation
        .executionService()
        .fireHook(
            ComputationExecutionFailurePoint.AFTER_COUNTEREXAMPLE_MUTATION_BEFORE_LEDGER_COMMIT,
            executionOutcome.executionId());
    event(
        "computation_counterexample_admitted",
        "working_delta",
        route.author.id(),
        "rejected",
        trace.targetBinding().isolatedComputationQuestion()
            ? "Counterexample refuted only its isolated computation question"
            : "Counterexample entered Negative Memory and refuted its exact bound obligation",
        counterexampleId.isEmpty() ? refutedObligationId : counterexampleId);
    return new AuthorityMutationIds(
        "", counterexampleId, "", refutedObligationId, "");
  }

  private MessageEnvelope computationCounterexampleMessage(
      RouteState route,
      ComputationTrace trace,
      ClaimEvidenceSemanticBinding claimBinding) {
    ExperimentResult result = trace.result();
    if (claimBinding != null) {
      return new MessageEnvelope(
          List.of("experiment://" + result.experimentId()),
          claimBinding.assumptions(),
          claimBinding.conclusion(),
          "",
          null,
          claimBinding.dependencyClaimIds(),
          List.of(),
          EvidenceType.COUNTEREXAMPLE,
          MemoryTier.NEGATIVE,
          "counterexample-" + result.experimentId() + "-" + result.resultHash().substring(0, 12),
          MessageType.COUNTEREXAMPLE,
          1.0d,
          topology.mathNormalize(claimBinding.statement()),
          claimBinding.problemHash(),
          claimBinding.quantifiers(),
          result.resultHash(),
          roundIndex.get(),
          "1",
          claimBinding.scopeLimitations(),
          "independent-computation-replay",
          RouteRole.TOOL_SPECIALIST,
          route.routeId,
          claimBinding.statement(),
          List.of(route.routeId),
          config.topology().crossRoute().messageTtlRounds(),
          claimBinding.variableBindings(),
          1.0d,
          ClaimStatus.REJECTED,
          claimBinding.claimStatementHash(),
          claimBinding.claimSemanticHash(),
          claimBinding.polarity());
    }
    return new MessageEnvelope(
        List.of("experiment://" + result.experimentId()),
        trace.spec().assumptions(),
        result.targetClaim(),
        "",
        null,
        List.of(),
        List.of(),
        EvidenceType.COUNTEREXAMPLE,
        MemoryTier.NEGATIVE,
        "counterexample-" + result.experimentId() + "-" + result.resultHash().substring(0, 12),
        MessageType.COUNTEREXAMPLE,
        1.0d,
        topology.mathNormalize(result.targetClaim()),
        problemHash,
        List.of(),
        result.resultHash(),
        roundIndex.get(),
        "1",
        negativeKnowledgeScope(),
        "independent-computation-replay",
        RouteRole.TOOL_SPECIALIST,
        route.routeId,
        "Counterexample to: " + result.targetClaim(),
        List.of(route.routeId),
        config.topology().crossRoute().messageTtlRounds(),
        List.of(),
        1.0d,
        ClaimStatus.REJECTED);
  }

  private MessageEnvelope computationInsightMessage(RouteState route, ComputationTrace trace) {
    ExperimentResult result = trace.result();
    return new MessageEnvelope(
        List.of("experiment://" + result.experimentId()),
        trace.spec().assumptions(),
        "No counterexample was found only within the recorded finite scope.",
        "",
        null,
        List.of(),
        List.of(),
        EvidenceType.BOUNDED_EXPERIMENT,
        MemoryTier.INSIGHT,
        "not-refuted-" + result.experimentId() + "-" + result.resultHash().substring(0, 12),
        MessageType.COMPUTATION_CERTIFICATE,
        1.0d,
        topology.mathNormalize("not refuted in finite scope " + result.targetClaim()),
        problemHash,
        List.of(),
        result.resultHash(),
        roundIndex.get(),
        "1",
        List.of(
            "Finite scope: " + result.scope(),
            "This item is not admissible to Fact Memory and cannot close an obligation."),
        "independent-computation-replay",
        RouteRole.TOOL_SPECIALIST,
        route.routeId,
        "Not refuted within finite scope: " + result.targetClaim(),
        List.of(route.routeId),
        config.topology().crossRoute().messageTtlRounds(),
        List.of(),
        1.0d,
        ClaimStatus.PROPOSED);
  }

  private AuthorityMutationIds promoteComputationCertificate(
      RouteState route,
      ComputationTrace trace,
      ComputationExecutionOutcome executionOutcome,
      boolean formal) {
    ExperimentResult result = trace.result();
    if (trace.targetBinding().isolatedComputationQuestion()) {
      String obligationId = trace.targetBinding().obligationId();
      MessageEnvelope isolatedEvidence =
          isolatedComputationCertificateMessage(route, trace, formal);
      proofGraph.addClaimNode(isolatedEvidence);
      findObligation(obligationId)
          .filter(obligation -> !"closed".equals(obligation.status()))
          .ifPresent(
              obligation ->
                  proofGraph.closeObligation(
                      obligation.obligationId(),
                      isolatedEvidence.messageId(),
                      1.0d));
      computation
          .executionService()
          .fireHook(
              ComputationExecutionFailurePoint.AFTER_FACT_MUTATION_BEFORE_LEDGER_COMMIT,
              executionOutcome.executionId());
      return new AuthorityMutationIds("", "", obligationId, "", "");
    }
    ClaimEvidenceSemanticBinding claimBinding = claimBindingForAuthorityProjection(trace);
    MessageEnvelope certificate =
        computationCertificateMessage(route, trace, claimBinding, formal);
    try {
      MessageEnvelope admitted =
          typedMemory.addFact(
              certificate, "independent-computation-replay", roundIndex.get());
      proofGraph.addClaimNode(admitted);
      String obligationId = trace.targetBinding().obligationId();
      findObligation(obligationId)
          .filter(
              obligation ->
                  formal
                      || obligation.kind() == ObligationKind.COMPUTATION_QUESTION)
          .filter(obligation -> !"closed".equals(obligation.status()))
          .ifPresent(
              obligation ->
                  proofGraph.closeObligation(
                      obligation.obligationId(), admitted.messageId(), 1.0d));
      computation
          .executionService()
          .fireHook(
              ComputationExecutionFailurePoint.AFTER_FACT_MUTATION_BEFORE_LEDGER_COMMIT,
              executionOutcome.executionId());
      boolean closed =
          findObligation(obligationId).map(value -> "closed".equals(value.status())).orElse(false);
      return new AuthorityMutationIds(
          admitted.messageId(), "", closed ? obligationId : "", "", "");
    } catch (IllegalArgumentException rejected) {
      event(
          "computation_fact_rejected",
          "working_delta",
          route.author.id(),
          "warning",
          rejected.getMessage(),
          result.experimentId());
      return AuthorityMutationIds.none();
    }
  }

  private MessageEnvelope computationCertificateMessage(
      RouteState route,
      ComputationTrace trace,
      ClaimEvidenceSemanticBinding claimBinding,
      boolean formal) {
    ExperimentResult result = trace.result();
    String statement = claimBinding.statement();
    String conclusion = claimBinding.conclusion();
    List<String> scopeLimitations = claimBinding.scopeLimitations();
    String claimStatementHash = claimBinding.claimStatementHash();
    String claimSemanticHash = claimBinding.claimSemanticHash();
    if (!formal) {
      String canonicalDomains = CanonicalJson.canonicalize(trace.spec().domains());
      String canonicalInput = CanonicalJson.canonicalize(trace.spec().arguments());
      String canonicalResultScope = CanonicalJson.canonicalize(result.scope());
      statement =
          "Finite-domain certificate for: "
              + claimBinding.statement()
              + "; certified input="
              + canonicalInput
              + "; declared domains="
              + canonicalDomains
              + "; result scope="
              + canonicalResultScope;
      conclusion = statement;
      scopeLimitations =
          java.util.stream.Stream.concat(
                  claimBinding.scopeLimitations().stream(),
                  java.util.stream.Stream.of(
                      "Finite computation domains: " + canonicalDomains,
                      "Certified finite input: " + canonicalInput,
                      "Certified result scope: " + canonicalResultScope,
                      "This certificate does not establish the unrestricted claim."))
              .distinct()
              .toList();
      claimStatementHash = CanonicalJson.stableHash(statement);
      claimSemanticHash =
          CanonicalJson.stableHash(
              Map.ofEntries(
                  Map.entry("identity_kind", "complete_finite_enumeration"),
                  Map.entry("problem_hash", claimBinding.problemHash()),
                  Map.entry("source_claim_statement_hash", claimBinding.claimStatementHash()),
                  Map.entry("source_claim_semantic_hash", claimBinding.claimSemanticHash()),
                  Map.entry("statement", statement),
                  Map.entry("conclusion", conclusion),
                  Map.entry("assumptions", claimBinding.assumptions()),
                  Map.entry("quantifiers", claimBinding.quantifiers()),
                  Map.entry("variable_bindings", claimBinding.variableBindings()),
                  Map.entry("scope_limitations", scopeLimitations),
                  Map.entry("polarity", claimBinding.polarity()),
                  Map.entry("dependency_claim_ids", claimBinding.dependencyClaimIds()),
                  Map.entry("computation_method", trace.spec().method().value()),
                  Map.entry("computation_domains", trace.spec().domains()),
                  Map.entry("computation_input", trace.spec().arguments()),
                  Map.entry("result_scope", result.scope())));
    }
    return new MessageEnvelope(
        List.of("experiment://" + result.experimentId()),
        claimBinding.assumptions(),
        conclusion,
        "",
        null,
        claimBinding.dependencyClaimIds(),
        List.of(),
        formal
            ? EvidenceType.FORMAL_KERNEL_CERTIFICATE
            : EvidenceType.COMPLETE_FINITE_ENUMERATION,
        MemoryTier.FACT,
        "computation-fact-" + result.experimentId() + "-" + result.resultHash().substring(0, 12),
        formal ? MessageType.FORMAL_CERTIFICATE : MessageType.COMPUTATION_CERTIFICATE,
        1.0d,
        topology.mathNormalize(statement),
        claimBinding.problemHash(),
        claimBinding.quantifiers(),
        result.resultHash(),
        roundIndex.get(),
        "1",
        scopeLimitations,
        route.author.id(),
        RouteRole.TOOL_SPECIALIST,
        route.routeId,
        statement,
        List.of(route.routeId),
        config.topology().crossRoute().messageTtlRounds(),
        claimBinding.variableBindings(),
        1.0d,
        ClaimStatus.VERIFIED,
        claimStatementHash,
        claimSemanticHash,
        claimBinding.polarity());
  }

  private ClaimEvidenceSemanticBinding claimBindingForAuthorityProjection(
      ComputationTrace trace) {
    ComputationTargetBinding target = trace.targetBinding();
    ClaimEvidenceSemanticBinding request = trace.spec().claimEvidenceSemanticBinding();
    ClaimEvidenceSemanticBinding result =
        trace.result() == null ? null : trace.result().claimEvidenceSemanticBinding();
    if (target == null
        || target.isolatedComputationQuestion()
        || request == null
        || !request.equals(result)
        || !sameHash(request.problemHash(), target.problemHash())
        || !request.claimId().equals(target.claimId())
        || !sameHash(request.claimStatementHash(), target.claimStatementHash())
        || !sameHash(request.claimSemanticHash(), target.claimSemanticHash())
        || !request.polarity().equals(target.polarity())
        || !request.claimId().equals(trace.result().targetClaimId())) {
      throw new IllegalStateException(
          "claim-bound authority projection requires exact semantic binding");
    }
    return request;
  }

  private MessageEnvelope isolatedComputationCertificateMessage(
      RouteState route, ComputationTrace trace, boolean formal) {
    ExperimentResult result = trace.result();
    String statement =
        (formal
                ? "Formally certified only for isolated computation question: "
                : "Complete finite certificate only for isolated computation question: ")
            + result.targetClaim();
    return new MessageEnvelope(
        List.of("experiment://" + result.experimentId()),
        trace.spec().assumptions(),
        statement,
        "",
        null,
        List.of(),
        List.of(),
        formal
            ? EvidenceType.FORMAL_KERNEL_CERTIFICATE
            : EvidenceType.COMPLETE_FINITE_ENUMERATION,
        MemoryTier.FACT,
        "isolated-computation-fact-"
            + result.experimentId()
            + "-"
            + result.resultHash().substring(0, 12),
        formal ? MessageType.FORMAL_CERTIFICATE : MessageType.COMPUTATION_CERTIFICATE,
        1.0d,
        topology.mathNormalize(statement),
        problemHash,
        List.of(),
        result.resultHash(),
        roundIndex.get(),
        "1",
        List.of("Authority is restricted to " + trace.targetBinding().obligationId()),
        route.author.id(),
        RouteRole.TOOL_SPECIALIST,
        route.routeId,
        statement,
        List.of(route.routeId),
        config.topology().crossRoute().messageTtlRounds(),
        List.of(),
        1.0d,
        ClaimStatus.VERIFIED);
  }

  private boolean targetBindingStillExact(ComputationTrace trace) {
    ComputationTargetBinding binding = trace.targetBinding();
    if (binding == null
        || !sameHash(binding.problemHash(), problemHash)
        || !sameHash(binding.scopeHash(), computationScopeHash(trace.spec()))) {
      return false;
    }
    ProofObligation obligation = findObligation(binding.obligationId()).orElse(null);
    CanonicalObligationRecord canonical =
        proofGraph.canonicalTargetForObligation(binding.obligationId()).orElse(null);
    if (obligation == null
        || canonical == null
        || !canonical.canonicalTargetId().equals(binding.canonicalTargetId())
        || !sameHash(canonical.signature().signatureHash(), binding.obligationSemanticHash())
        || !canonical.signature().polarity().equals(binding.polarity())) {
      return false;
    }
    if (binding.isolatedComputationQuestion()) {
      return obligation.kind() == ObligationKind.COMPUTATION_QUESTION
          && topology
              .mathNormalize(obligation.statement())
              .equals(topology.mathNormalize(trace.spec().targetClaim()));
    }
    ClaimEvidenceSemanticBinding requested = trace.spec().claimEvidenceSemanticBinding();
    if (requested == null
        || trace.result().claimEvidenceSemanticBinding() == null
        || !requested.equals(trace.result().claimEvidenceSemanticBinding())
        || !binding.claimId().equals(trace.result().targetClaimId())) {
      return false;
    }
    FrozenClaimSnapshot frozen =
        claimCourt.records().stream()
            .map(ClaimCourtRecord::frozenClaim)
            .filter(value -> exactClaimBinding(requested, value, trace.spec()))
            .findFirst()
            .orElse(null);
    return exactComputationObligation(obligation, requested, frozen, trace.spec())
        && sameHash(
            binding.claimStatementHash(),
            frozen == null
                ? CanonicalJson.stableHash(obligation.statement())
                : frozen.claimStatementHash())
        && sameHash(
            binding.claimSemanticHash(),
            frozen == null
                ? canonical.signature().signatureHash()
                : frozen.claimSemanticHash());
  }

  private ComputationBroker.PreparedDecision repairComputationContract(
      RouteState route, ComputationBroker.PreparedDecision original, int segment) {
    ExperimentSpec originalSpec = original.spec();
    List<String> issues =
        new ArrayList<>(ContractsFunctions.validateExperimentContract(originalSpec));
    if (issues.isEmpty()) {
      issues.add(original.decision().reason());
    }
    if (config.computation().maxContractRepairsPerSegment() <= 0) {
      return failedComputationRepair(
          original,
          ComputationContractRepairStatus.DISABLED,
          "request.contract_repair_disabled",
          "The request was not executed because bounded contract repair is disabled.",
          route.author.id());
    }
    if (ledger.remainingCalls() <= 0) {
      return failedComputationRepair(
          original,
          ComputationContractRepairStatus.FAILED,
          "request.contract_repair_failed",
          "The request was not executed because no model call remained for contract repair.",
          route.author.id());
    }

    AgentRuntime repairAgent = computationRepairAgent(route.author);
    event(
        "computation_contract_repair_started",
        "computation_contract_repair",
        repairAgent.id(),
        "running",
        "Repairing one invalid typed computation request",
        originalSpec.requestHash());
    StructuredCallResult<ComputationContractRepair> call;
    try {
      call =
          callStage(
              "computation-repair-"
                  + route.routeId
                  + "-r"
                  + roundIndex.get()
                  + "-s"
                  + segment,
              "computation_contract_repair",
              ComputationContractRepair.class,
              Map.of(
                  "immutable_problem",
                  frozenProblem,
                  "original_experiment_specification",
                  originalSpec,
                  "contract_errors",
                  issues,
                  "registered_tool_contracts",
                  ContractsFunctions.experimentToolCatalog(
                      new LinkedHashSet<>(frozenProblem.allowedTools())),
                  "sandbox_available",
                  sandboxEnabled,
                  "repair_rules",
                  List.of(
                      "Change only method, domains, arguments, exact_arithmetic, and typed_tool_gap.",
                      "Preserve every mathematical-semantic and budget field exactly.",
                      "Do not reduce the requested scope or silently discard an input.",
                      "sandboxed_python requires the complete bounded JSON object under arguments.input.",
                      "Abandon when no semantics-preserving bounded request can be represented.")),
              repairAgent,
              "depth",
              "Repairing the typed computation contract");
    } catch (AgentCallFailure failure) {
      if (failure.providerFailure().kind() == ProviderErrorKind.CANCELLED) {
        throw failure;
      }
      return failedComputationRepair(
          original,
          ComputationContractRepairStatus.FAILED,
          "request.contract_repair_failed",
          "The bounded contract repair call failed; nothing was executed.",
          repairAgent.id());
    } catch (StructuredOutputError failure) {
      return failedComputationRepair(
          original,
          ComputationContractRepairStatus.FAILED,
          "request.contract_repair_failed",
          "The bounded contract repair returned no valid replacement; nothing was executed.",
          repairAgent.id());
    }

    ComputationContractRepair repair = call.value();
    if (repair.action() == ComputationContractRepairAction.ABANDON_AS_UNREPRESENTABLE) {
      return failedComputationRepair(
          original,
          ComputationContractRepairStatus.ABANDONED,
          "request.contract_unrepresentable",
          repair.reason(),
          call.agentId());
    }

    ExperimentSpec candidate = repair.repairedSpec();
    List<String> changed = changedImmutableComputationFields(originalSpec, candidate);
    if (!changed.isEmpty()) {
      return failedComputationRepair(
          original,
          ComputationContractRepairStatus.FAILED,
          "request.contract_repair_failed",
          "The repair attempted to change immutable computation semantics: "
              + String.join(", ", changed)
              + ". Nothing was executed.",
          call.agentId());
    }

    ExperimentSpec rebound = bindRepairedExperiment(originalSpec, candidate);
    if (!frozenProblem.allowedTools().isEmpty()
        && !frozenProblem.allowedTools().contains(rebound.method().value())) {
      return failedComputationRepair(
          original,
          ComputationContractRepairStatus.FAILED,
          "request.contract_repair_failed",
          "The replacement method is not enabled for this run; nothing was executed.",
          call.agentId());
    }
    List<String> repairedIssues = ContractsFunctions.validateExperimentContract(rebound);
    if (!repairedIssues.isEmpty()) {
      return failedComputationRepair(
          original,
          ComputationContractRepairStatus.FAILED,
          "request.contract_repair_failed",
          "The single replacement request still failed typed validation; nothing was executed.",
          call.agentId());
    }

    ComputationBroker.PreparedDecision repaired =
        computation.decide(
            rebound,
            ComputationContext.initial(route.routeId, safeInt(ledger.remainingCalls())));
    ComputationDecision decision =
        withComputationRepair(
            repaired.decision(),
            ComputationContractRepairStatus.SUCCEEDED,
            originalSpec.requestHash(),
            repair.reason());
    event(
        "computation_contract_repaired",
        "computation_contract_repair",
        call.agentId(),
        decision.decision() == ComputationDecisionStatus.ALLOW ? "completed" : "warning",
        decision.decision() == ComputationDecisionStatus.ALLOW
            ? "Repaired computation contract passed pre-execution policy"
            : "Repaired computation contract remained inadmissible",
        call.responseArtifactRef());
    return new ComputationBroker.PreparedDecision(repaired.spec(), decision);
  }

  private ComputationBroker.PreparedDecision failedComputationRepair(
      ComputationBroker.PreparedDecision original,
      ComputationContractRepairStatus status,
      String ruleId,
      String reason,
      String agentId) {
    ComputationDecision rejected =
        new ComputationDecision(
            false,
            original.decision().canonicalRequestHash(),
            reason,
            status,
            original.decision().createdAt(),
            ComputationDecisionStatus.REJECT,
            original.decision().experimentId(),
            original.spec().requestHash(),
            reason,
            original.decision().remainingExperiments(),
            original.decision().requestHash(),
            false,
            ruleId);
    event(
        "computation_not_executed",
        "computation_contract_repair",
        agentId,
        "warning",
        reason,
        original.spec().requestHash());
    return new ComputationBroker.PreparedDecision(original.spec(), rejected);
  }

  private AgentRuntime computationRepairAgent(AgentRuntime author) {
    for (String role : List.of("experimenter", "planner")) {
      try {
        return pool.select(role, Set.of(author.id()), List.of(), null, true);
      } catch (IllegalStateException ignored) {
        // The authoritative flow falls back to the author when no independent compiler exists.
      }
    }
    return author;
  }

  private static boolean repairableComputationDecision(ComputationDecision decision) {
    return Set.of(
            "request.invalid_tool_contract",
            "request.invalid_precision_claim",
            "request.replay_failed",
            "sandbox.typed_gap_required",
            "sandbox.disabled",
            "tool.unregistered")
        .contains(decision.ruleId());
  }

  private static List<String> allowedComputationMethods(boolean sandboxEnabled) {
    return EnumSet.allOf(ComputationMethod.class).stream()
        .filter(method -> sandboxEnabled || method != ComputationMethod.SANDBOXED_PYTHON)
        .map(ComputationMethod::value)
        .toList();
  }

  private static List<String> changedImmutableComputationFields(
      ExperimentSpec original, ExperimentSpec candidate) {
    List<String> changed = new ArrayList<>();
    immutableDifference(
        changed, "experiment_id", original.experimentId(), candidate.experimentId());
    immutableDifference(changed, "purpose", original.purpose(), candidate.purpose());
    immutableDifference(changed, "target_claim", original.targetClaim(), candidate.targetClaim());
    immutableDifference(
        changed, "target_claim_id", original.targetClaimId(), candidate.targetClaimId());
    immutableDifference(
        changed,
        "claim_evidence_semantic_binding",
        original.claimEvidenceSemanticBinding(),
        candidate.claimEvidenceSemanticBinding());
    immutableDifference(changed, "assumptions", original.assumptions(), candidate.assumptions());
    immutableDifference(
        changed,
        "reasoning_basis",
        original.reasoningBasis(),
        candidate.reasoningBasis());
    immutableDifference(
        changed,
        "why_computation_is_needed",
        original.whyComputationIsNeeded(),
        candidate.whyComputationIsNeeded());
    immutableDifference(
        changed,
        "decision_if_confirmed",
        original.decisionIfConfirmed(),
        candidate.decisionIfConfirmed());
    immutableDifference(
        changed,
        "decision_if_refuted",
        original.decisionIfRefuted(),
        candidate.decisionIfRefuted());
    immutableDifference(
        changed,
        "noncomputational_alternative",
        original.noncomputationalAlternative(),
        candidate.noncomputationalAlternative());
    immutableDifference(changed, "broad_search", original.broadSearch(), candidate.broadSearch());
    immutableDifference(changed, "max_cases", original.maxCases(), candidate.maxCases());
    immutableDifference(changed, "seed", original.seed(), candidate.seed());
    return List.copyOf(changed);
  }

  private static void immutableDifference(
      List<String> changed, String field, Object original, Object candidate) {
    if (!Objects.equals(original, candidate)) {
      changed.add(field);
    }
  }

  private static ExperimentSpec bindRepairedExperiment(
      ExperimentSpec original, ExperimentSpec repaired) {
    return new ExperimentSpec(
        repaired.arguments(),
        original.assumptions(),
        original.broadSearch(),
        original.decisionIfConfirmed(),
        original.decisionIfRefuted(),
        repaired.domains(),
        repaired.exactArithmetic(),
        null,
        original.experimentId(),
        original.maxCases(),
        repaired.method(),
        original.noncomputationalAlternative(),
        original.parentCheckpointId(),
        original.pathId(),
        original.purpose(),
        original.reasoningBasis(),
        null,
        original.requestedBy(),
        JsonNodeFactory.instance.objectNode(),
        original.seed(),
        original.targetClaim(),
        repaired.typedToolGap(),
        original.whyComputationIsNeeded(),
        original.targetClaimId(),
        original.claimEvidenceSemanticBinding());
  }

  private static ComputationDecision withComputationRepair(
      ComputationDecision source,
      ComputationContractRepairStatus status,
      String originalRequestHash,
      String reason) {
    return new ComputationDecision(
        source.cacheHit(),
        source.canonicalRequestHash(),
        reason,
        status,
        source.createdAt(),
        source.decision(),
        source.experimentId(),
        originalRequestHash,
        source.reason(),
        source.remainingExperiments(),
        source.requestHash(),
        source.requiresMetaReview(),
        source.ruleId());
  }

  private void ensureSeedCheckpoint(RouteState route) {
    if (route.checkpoint != null) {
      return;
    }
    route.checkpoint =
        new ContinuationFunctions.Checkpoint(
            "checkpoint-" + route.routeId + "-0",
            "",
            problemHash,
            route.routeId,
            route.strategy.strategyId(),
            0,
            route.routeId,
            true);
    checkpoints.seed(route.checkpoint);
    event(
        "checkpoint_seeded",
        "committed_checkpoint",
        route.author.id(),
        "completed",
        "Seeded immutable route checkpoint",
        route.checkpoint.checkpointId());
  }

  private void integrateCommittedRoutes() {
    List<RouteState> submitted =
        routes.stream()
            .filter(route -> route.attempt != null)
            .filter(route -> !route.integrated)
            .toList();
    if (submitted.isEmpty()) {
      return;
    }
    stage(
        RoutePipelineFunctions.RunStage.WORKING_DELTA,
        "Building bounded route deltas from isolated attempts");
    for (RouteState route : submitted) {
      ensureSeedCheckpoint(route);
      if (route.delta != null) {
        continue;
      }
      String reviewerId =
          route.plan.referee() != null && route.plan.referee().assigned()
              ? route.plan.referee().agentId()
              : "unassigned-referee-" + route.routeId;
      route.delta =
          ContinuationFunctions.boundedDelta(
              route.checkpoint,
              route.author.id(),
              reviewerId,
              Math.min(16, route.attempt.proofSteps().size()),
              Math.min(8, Math.max(1, route.attempt.proposedLemmas().size())),
              true);
      route.deltaId = route.delta.deltaId();
      event(
          "working_delta_created",
          "working_delta",
          route.author.id(),
          "completed",
          "Created bounded continuation delta for independent review",
          route.deltaId);
    }
    complete(RoutePipelineFunctions.RunStage.WORKING_DELTA);
    persistUnchecked("working_delta", false);

    stage(
        RoutePipelineFunctions.RunStage.INDEPENDENT_REVIEW,
        "Running Skeptic, Tool Specialist, structural, and detailed Referee gates");
    reviewRoutesConcurrently(submitted);
    complete(RoutePipelineFunctions.RunStage.INDEPENDENT_REVIEW);

    stage(
        RoutePipelineFunctions.RunStage.COMMITTED_CHECKPOINT,
        "Committing accepted deltas with compare-and-swap semantics");
    for (RouteState route : submitted) {
      if (route.checkpointProcessed) {
        continue;
      }
      boolean accepted = "verified".equals(route.status);
      route.delta =
          new ContinuationFunctions.Delta(
              route.delta.deltaId(),
              route.delta.parentCheckpointId(),
              route.delta.problemHash(),
              route.delta.pathId(),
              route.delta.strategyId(),
              route.delta.segmentIndex(),
              route.delta.authorAgentId(),
              route.delta.reviewerAgentId(),
              route.delta.newSteps(),
              route.delta.newClaims(),
              accepted);
      ContinuationFunctions.CommitResult commit =
          checkpoints.commit(route.checkpoint.branchId(), route.delta);
      if (commit.committed()) {
        route.checkpoint = commit.checkpoint();
        acknowledgeConsumedMessages(route);
        event(
            "checkpoint_committed",
            "committed_checkpoint",
            route.author.id(),
            "completed",
            commit.reason(),
            commit.checkpoint().checkpointId());
      } else {
        route.status = "unverified";
        if (config.continuation().allowCheckpointRollback()) {
          String branch = route.routeId + "-revision-" + (route.revisionCount + 1);
          checkpoints.rollbackAndBranch(route.checkpoint.checkpointId(), branch);
          event(
              "checkpoint_rolled_back",
              "committed_checkpoint",
              route.author.id(),
              "warning",
              commit.reason() + "; retained rejected delta and opened a revision branch",
              route.deltaId);
          }
      }
      route.checkpointProcessed = true;
      persistUnchecked("committed_checkpoint", false);
    }
    complete(RoutePipelineFunctions.RunStage.COMMITTED_CHECKPOINT);
    persistUnchecked("committed_checkpoint", false);

    stage(
        RoutePipelineFunctions.RunStage.CLAIM_MEMORY_GRAPH,
        "Extracting claims and updating lemma memory, typed memory, and proof graph");
    Map<String, List<AttemptArtifactRecord>> reviewedByRoute =
        reviewRouteClaimsConcurrently(submitted);
    for (RouteState route : submitted) {
      List<AttemptArtifactRecord> reviewed = reviewedByRoute.getOrDefault(route.routeId, List.of());
      integrateVerifiedAttemptArtifacts(route, reviewed);
      if ("verified".equals(route.status)) {
        integrateRouteTheorem(route, reviewed);
      } else {
        recordRouteFailure(route);
      }
      verifyConsumedArtifactEffects(route);
      route.integrated = true;
      recordInspirationOutcome(route);
      persistUnchecked("claim_memory_graph", false);
    }
    complete(RoutePipelineFunctions.RunStage.CLAIM_MEMORY_GRAPH);
    persistUnchecked("claim_memory_graph", false);
  }

  private Map<String, List<AttemptArtifactRecord>> reviewRouteClaimsConcurrently(
      List<RouteState> submitted) {
    Map<String, List<AttemptArtifactRecord>> harvestedByRoute = new LinkedHashMap<>();
    submitted.stream()
        .sorted(java.util.Comparator.comparing(route -> route.routeId))
        .forEach(route -> harvestedByRoute.put(route.routeId, harvestAttemptArtifacts(route)));
    Map<String, RouteState> routeById =
        submitted.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    route -> route.routeId,
                    java.util.function.Function.identity(),
                    (left, right) -> left,
                    LinkedHashMap::new));
    Map<String, AttemptArtifactRecord> artifactByCase = new LinkedHashMap<>();
    Map<String, List<ClaimCourtProjectionTarget>> targetsByCourtCase = new LinkedHashMap<>();
    Set<String> plannedCourtCaseIds = new LinkedHashSet<>();
    List<AuthoritativeWorkSpec> specs = new ArrayList<>();
    int ordinal = 0;
    for (RouteState route :
        submitted.stream().sorted(java.util.Comparator.comparing(item -> item.routeId)).toList()) {
      for (AttemptArtifactRecord artifact :
          prepareClaimCourtWorkItem(
              route, harvestedByRoute.getOrDefault(route.routeId, List.of()))) {
        String caseKey = claimCourtCaseKey(route.routeId, artifact.claimId());
        artifactByCase.put(caseKey, artifact);
        FrozenClaimSnapshot frozen = freezeClaimForCourt(route, artifact);
        targetsByCourtCase
            .computeIfAbsent(frozen.courtCaseId(), ignored -> new ArrayList<>())
            .add(new ClaimCourtProjectionTarget(route.routeId, artifact.claimId()));
        if (!plannedCourtCaseIds.add(frozen.courtCaseId())) {
          continue;
        }
        specs.add(
            new AuthoritativeWorkSpec(
                ResearchWorkKind.CLAIM_PROOF_AUDIT,
                route.routeId,
                artifact.claimId(),
                "",
                "",
                "route_referee",
                AgentLeaseClass.COORDINATION,
                Set.of(artifact.authorAgentId()),
                new ResearchWorkReadSet(
                    Set.of(currentResearchAuthorityAnchor().stableHash()),
                    Set.of("attempt-artifact://" + artifact.artifactId())),
                new ResearchWorkConflictSet(
                    Set.of(route.routeId),
                    Set.of("claim:" + artifact.claimId(), "content:" + artifact.contentHash()),
                    Set.of(),
                    Set.of(),
                    Set.of()),
                "attempt-artifact://" + artifact.artifactId(),
                ClaimCourtCaseDraft.class.getName(),
                ordinal++));
      }
    }
    if (!specs.isEmpty()) {
      ClaimCourtSnapshot courtBase = claimCourt.snapshot();
      ClaimProofRevisionSnapshot revisionsBase = claimProofRevisions.snapshot();
      ClaimCourtStageExecutionSnapshot executionsBase = claimCourtExecutions.snapshot();
      executeAuthoritativeEpoch(
          "claim-court-r" + roundIndex.get(),
          specs,
          (frozen, item) ->
              executeClaimCourtCaseAgainstFrozenSnapshot(
                  frozen,
                  item,
                  routeById,
                  artifactByCase,
                  courtBase,
                  revisionsBase,
                  executionsBase),
          results ->
              commitClaimCourtResultsInStableOrder(
                  results, routeById, artifactByCase, targetsByCourtCase));
    }
    Map<String, List<AttemptArtifactRecord>> reviewedByRoute = new LinkedHashMap<>();
    for (RouteState route : submitted) {
      reviewedByRoute.put(
          route.routeId,
          attemptArtifacts.recordsForAttempt(route.attempt.attemptId()));
    }
    return Map.copyOf(reviewedByRoute);
  }

  private List<AttemptArtifactRecord> prepareClaimCourtWorkItem(
      RouteState route, List<AttemptArtifactRecord> harvested) {
    List<AttemptArtifactRecord> checked =
        harvested.stream()
            .map(record -> rejectUnboundModernLocalClaim(route, record))
            .map(record -> rejectMismatchedBoundClaimStatement(route, record))
            .map(record -> rejectInvalidClaimCourtVariableIdentity(route, record))
            .toList();
    checked.stream()
        .filter(record -> record.status() == AttemptArtifactStatus.UNCERTAIN)
        .forEach(
            record -> {
              lemmaMemory.applyClaimReviewDecision(
                  record.claimId(), uncertainDecision(record.claimId(), "invalid artifact targeting"));
              addDistinct(route.uncertainClaimIds, record.claimId());
            });
    List<AttemptArtifactRecord> reviewable =
        checked.stream()
            .filter(
                record ->
                    record.status() == AttemptArtifactStatus.HARVESTED
                        || record.status() == AttemptArtifactStatus.REVIEW_PENDING)
            .limit(ClaimReviewBatch.MAX_DECISIONS)
            .toList();
    if (reviewable.stream()
        .anyMatch(record -> record.status() == AttemptArtifactStatus.HARVESTED)) {
      attemptArtifacts.markReviewPending(route.attempt.attemptId());
    }
    return reviewable.stream()
        .map(record -> attemptArtifacts.get(record.artifactId()))
        .toList();
  }

  private AttemptArtifactRecord rejectMismatchedBoundClaimStatement(
      RouteState route, AttemptArtifactRecord record) {
    if (record.status() != AttemptArtifactStatus.HARVESTED
        && record.status() != AttemptArtifactStatus.REVIEW_PENDING) {
      return record;
    }
    if (!mismatchesBoundCriticalClaim(route, record.claimId(), record.statement())) {
      return record;
    }
    String reason = "CLAIM_COURT_CONTEXT_STATEMENT_MISMATCH:" + record.claimId();
    event(
        "claim_context_rejected",
        "claim_court",
        record.authorAgentId(),
        "blocked",
        reason,
        record.claimId());
    return attemptArtifacts.markUncertain(record.artifactId(), reason);
  }

  private boolean mismatchesBoundCriticalClaim(
      RouteState route, String claimId, String statement) {
    CriticalClaim boundClaim =
        route.strategy.criticalClaims().stream()
            .filter(claim -> claim.claimId().equals(claimId))
            .findFirst()
            .orElse(null);
    return boundClaim != null
        && !topology
            .mathNormalize(boundClaim.statement())
            .equals(topology.mathNormalize(statement));
  }

  private AttemptArtifactRecord rejectInvalidClaimCourtVariableIdentity(
      RouteState route, AttemptArtifactRecord record) {
    if (record.status() != AttemptArtifactStatus.HARVESTED
        && record.status() != AttemptArtifactStatus.REVIEW_PENDING) {
      return record;
    }
    String reason =
        isolatableClaimCourtContextFailure(route, proofClaimForArtifact(record));
    if (reason.isEmpty()) {
      return record;
    }
    event(
        "claim_context_rejected",
        "claim_court",
        record.authorAgentId(),
        "blocked",
        reason,
        record.claimId());
    return attemptArtifacts.markUncertain(record.artifactId(), reason);
  }

  private String isolatableClaimCourtContextFailure(RouteState route, ClaimCard claim) {
    try {
      claimCourtSemanticContext(route, claim);
      return "";
    } catch (IllegalArgumentException exception) {
      String message = exception.getMessage() == null ? "" : exception.getMessage();
      if (message.startsWith("MISSING_ATTEMPT_LOCAL_CLAIM_CONTEXT_BINDING:")
          || message.startsWith("UNBOUND_CLAIM_COURT_QUANTIFIER:")
          || message.startsWith("DUPLICATE_CLAIM_COURT_VARIABLE_BINDING:")) {
        return message;
      }
      throw exception;
    }
  }

  private ResearchWorkResultEnvelope executeClaimCourtCaseAgainstFrozenSnapshot(
      FrozenResearchSnapshot frozen,
      ResearchWorkItem item,
      Map<String, RouteState> routeById,
      Map<String, AttemptArtifactRecord> artifactByCase,
      ClaimCourtSnapshot courtBase,
      ClaimProofRevisionSnapshot revisionsBase,
      ClaimCourtStageExecutionSnapshot executionsBase) {
    RouteState route = copyRouteState(Objects.requireNonNull(routeById.get(item.routeId()), "route"));
    AttemptArtifactRecord artifact =
        Objects.requireNonNull(
            artifactByCase.get(claimCourtCaseKey(item.routeId(), item.claimId())), "artifact");
    ClaimCourtLedger localCourt = new ClaimCourtLedger();
    localCourt.restore(courtBase);
    ClaimProofRevisionLedger localRevisions = new ClaimProofRevisionLedger();
    localRevisions.restore(revisionsBase);
    ClaimCourtStageExecutionLedger localExecutions = new ClaimCourtStageExecutionLedger();
    localExecutions.restore(executionsBase);
    NegativeKnowledgeRegistry localNegativeKnowledge =
        NegativeKnowledgeRegistry.restore(negativeKnowledgeRegistry.snapshot());
    activeClaimCourtWorker.set(
        new ClaimCourtWorkerContext(
            localCourt, localRevisions, localExecutions, localNegativeKnowledge));
    try {
      ClaimCourtReviewResult review = conductClaimCourt(route, artifact);
      ClaimCourtCaseDraft draft =
          new ClaimCourtCaseDraft(
              review.record(),
              review.revision(),
              review.authorityAgentId(),
              review.confidence(),
              localCourt.snapshot(),
              localRevisions.snapshot(),
              localExecutions.snapshot());
      return new ResearchWorkResultEnvelope(
          item.workItemId(),
          frozen.epochId(),
          frozen.snapshotHash(),
          review.authorityAgentId(),
          "claim-court-" + review.record().courtCaseId(),
          ResearchWorkResultStatus.SUCCEEDED,
          Map.of("draft", ContractObjectMapper.toTree(draft)),
          List.of(),
          List.of(),
          List.of());
    } finally {
      activeClaimCourtWorker.remove();
    }
  }

  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification =
          "Single-case projection must preserve the original runtime failure after restoring its mutation snapshot.")
  private void commitClaimCourtResultsInStableOrder(
      List<ResearchWorkResultEnvelope> results,
      Map<String, RouteState> routeById,
      Map<String, AttemptArtifactRecord> artifactByCase,
      Map<String, List<ClaimCourtProjectionTarget>> targetsByCourtCase) {
    boolean batch =
        targetsByCourtCase.values().stream().mapToInt(List::size).sum() > 1;
    int appliedResults = 0;
    for (ResearchWorkResultEnvelope envelope : results) {
      ClaimCourtCaseDraft draft =
          ContractObjectMapper.read(
              ContractObjectMapper.toTree(envelope.publicStructuredResult().get("draft")),
              ClaimCourtCaseDraft.class);
      mergeClaimCourtWorkerDraft(draft);
      List<ClaimCourtProjectionTarget> targets =
          Objects.requireNonNull(
              targetsByCourtCase.get(draft.record().courtCaseId()),
              "Claim Court projection targets");
      for (ClaimCourtProjectionTarget target : targets) {
        RouteState route = Objects.requireNonNull(routeById.get(target.routeId()), "route");
        AttemptArtifactRecord artifact =
            Objects.requireNonNull(
                artifactByCase.get(claimCourtCaseKey(target.routeId(), target.claimId())),
                "artifact");
        ClaimCourtMutationSnapshot fallback = captureClaimCourtMutation(route);
        route.courtCaseIds.add(draft.record().courtCaseId());
        try {
          projectClaimCourtOutcome(route, artifact, draft.reviewResult());
        } catch (NegativeKnowledgeBlockedException exception) {
          restoreClaimCourtMutation(fallback);
          attemptArtifacts.markAdmissionRejected(artifact.artifactId(), exception.getMessage());
          recordNegativeKnowledgeRejection(
              "claim_projection_rejected",
              "claim_memory_graph",
              artifact.claimId(),
              exception);
        } catch (RuntimeException exception) {
          restoreClaimCourtMutation(fallback);
          if (!batch) {
            throw exception;
          }
          attemptArtifacts.markUncertain(
              artifact.artifactId(),
              "CLAIM_COURT_PROJECTION_FAILED:" + exception.getClass().getSimpleName());
          addDistinct(route.uncertainClaimIds, artifact.claimId());
          event(
              "claim_court_projection_isolated",
              "claim_memory_graph",
              draft.authorityAgentId(),
              "quarantined",
              "A failed Claim Court projection was isolated after all sibling cases settled",
              artifact.artifactId());
        }
      }
      appliedResults++;
      if (appliedResults == 1) {
        failAuthoritativeConcurrencyAt(
            AuthoritativeConcurrencyFailurePoint.AFTER_FIRST_AUTHORITY_RESULT_APPLIED);
      }
    }
  }

  private FrozenClaimSnapshot freezeClaimForCourt(
      RouteState route, AttemptArtifactRecord artifact) {
    ClaimCard claim = proofClaimForArtifact(artifact);
    return claimFreezeService.freeze(
        problemHash,
        rootGoal().sourceStatementHash(),
        route.routeId,
        claim,
        claimCourtSemanticContext(route, claim));
  }

  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification =
          "The authoritative three-ledger merge restores its complete frontier before preserving the original conflict.")
  private void mergeClaimCourtWorkerDraft(ClaimCourtCaseDraft draft) {
    ClaimCourtSnapshot currentCourt = claimCourt.snapshot();
    ClaimProofRevisionSnapshot currentRevisions = claimProofRevisions.snapshot();
    ClaimCourtStageExecutionSnapshot currentExecutions = claimCourtExecutions.snapshot();
    ClaimCourtWorkerFrontierMerger.MergedFrontier merged =
        ClaimCourtWorkerFrontierMerger.merge(
            currentCourt,
            currentRevisions,
            currentExecutions,
            draft.court(),
            draft.revisions(),
            draft.executions());
    try {
      claimCourt.restore(merged.court());
      claimProofRevisions.restore(merged.revisions());
      claimCourtExecutions.restore(merged.executions());
    } catch (RuntimeException exception) {
      claimCourt.restore(currentCourt);
      claimProofRevisions.restore(currentRevisions);
      claimCourtExecutions.restore(currentExecutions);
      throw exception;
    }
  }

  private void mergeClaimCourtExecutionFrontier(
      ClaimCourtStageExecutionSnapshot candidateExecutions) {
    ClaimCourtStageExecutionSnapshot currentExecutions = claimCourtExecutions.snapshot();
    claimCourtExecutions.restore(
        ClaimCourtWorkerFrontierMerger.mergeExecutions(
            currentExecutions, candidateExecutions));
  }

  private static String claimCourtCaseKey(String routeId, String claimId) {
    return routeId + "\u0000" + claimId;
  }

  private void reviewRoutesConcurrently(List<RouteState> submitted) {
    List<RouteState> pending =
        submitted.stream()
            .filter(route -> !route.reviewComplete)
            .sorted(java.util.Comparator.comparing(route -> route.routeId))
            .toList();
    if (pending.isEmpty()) {
      return;
    }
    List<AuthoritativeWorkSpec> specs = new ArrayList<>();
    Map<String, RouteState> routeById = new LinkedHashMap<>();
    for (int ordinal = 0; ordinal < pending.size(); ordinal++) {
      RouteState route = pending.get(ordinal);
      routeById.put(route.routeId, route);
      specs.add(
          new AuthoritativeWorkSpec(
              ResearchWorkKind.ROUTE_REVIEW,
              route.routeId,
              "",
              route.focusObligationId,
              route.focusedCanonicalTargetId,
              "route_referee",
              AgentLeaseClass.COORDINATION,
              Set.of(route.author.id()),
              new ResearchWorkReadSet(
                  Set.of(currentResearchAuthorityAnchor().stableHash()),
                  Set.of(route.deltaId)),
              new ResearchWorkConflictSet(
                  Set.of(route.routeId), Set.of(), Set.of(), Set.of(), Set.of()),
              "route-delta://" + route.deltaId,
              RouteReviewDraft.class.getName(),
              ordinal));
    }
    executeAuthoritativeEpoch(
        "route-review-r" + roundIndex.get(),
        specs,
        (frozen, item) -> executeRouteReviewAgainstFrozenSnapshot(frozen, item, routeById),
        results -> commitRouteReviewResultsInStableOrder(results, routeById));
  }

  private ResearchWorkResultEnvelope executeRouteReviewAgainstFrozenSnapshot(
      FrozenResearchSnapshot frozen,
      ResearchWorkItem item,
      Map<String, RouteState> routeById) {
    RouteState source = Objects.requireNonNull(routeById.get(item.routeId()), "route");
    RouteState draft = copyRouteState(source);
    ResearchWorkerContext worker =
        new ResearchWorkerContext(
            ResearchCheckpointLedger.restore(researchCheckpoints.snapshot()), draft, "", true);
    activeResearchWorker.set(worker);
    try {
      independentlyReview(draft);
      RouteReviewDraft result = RouteReviewDraft.from(draft, worker.computationAudits);
      String agentId =
          draft.detailedReview == null ? draft.author.id() : draft.detailedReview.agentId();
      return new ResearchWorkResultEnvelope(
          item.workItemId(),
          frozen.epochId(),
          frozen.snapshotHash(),
          agentId,
          "route-review-" + item.workItemId(),
          ResearchWorkResultStatus.SUCCEEDED,
          Map.of("draft", ContractObjectMapper.toTree(result)),
          List.of(),
          List.of(),
          List.of());
    } finally {
      activeResearchWorker.remove();
    }
  }

  private void commitRouteReviewResultsInStableOrder(
      List<ResearchWorkResultEnvelope> results, Map<String, RouteState> routeById) {
    for (ResearchWorkResultEnvelope envelope : results) {
      ResearchWorkItem item = researchTasks.require(envelope.workItemId()).item();
      RouteState route = Objects.requireNonNull(routeById.get(item.routeId()), "route");
      RouteReviewDraft draft =
          ContractObjectMapper.read(
              ContractObjectMapper.toTree(envelope.publicStructuredResult().get("draft")),
              RouteReviewDraft.class);
      route.plan = draft.plan();
      route.skepticReview = draft.skepticReview();
      route.toolAudit = draft.toolAudit();
      route.structuralReview = draft.structuralReview();
      route.detailedReview = draft.detailedReview();
      route.crossProviderReview = draft.crossProviderReview();
      route.teamResult = draft.teamResult();
      route.escalation = draft.escalation();
      route.validationExecution = draft.validationExecution();
      route.status = draft.status();
      route.failureReason = draft.failureReason();
      draft.computationAudits().forEach(this::recordComputationAudit);
      route.reviewComplete = true;
      registerRoute(route);
    }
  }

  private static RouteState copyRouteState(RouteState source) {
    RouteState copy =
        new RouteState(
            source.routeId,
            source.author,
            source.strategy,
            source.plan,
            source.revisionCount);
    copy.claimIds.addAll(source.claimIds);
    copy.artifactIds.addAll(source.artifactIds);
    copy.salvagedVerifiedClaimIds.addAll(source.salvagedVerifiedClaimIds);
    copy.salvagedCounterexampleIds.addAll(source.salvagedCounterexampleIds);
    copy.rejectedClaimIds.addAll(source.rejectedClaimIds);
    copy.uncertainClaimIds.addAll(source.uncertainClaimIds);
    copy.courtCaseIds.addAll(source.courtCaseIds);
    copy.proofInvalidOpenClaimIds.addAll(source.proofInvalidOpenClaimIds);
    copy.repairExhaustedClaimIds.addAll(source.repairExhaustedClaimIds);
    copy.revisionHistory.addAll(source.revisionHistory);
    copy.pendingDeliveries.addAll(source.pendingDeliveries);
    copy.attempt = source.attempt;
    copy.skepticReview = source.skepticReview;
    copy.toolAudit = source.toolAudit;
    copy.structuralReview = source.structuralReview;
    copy.detailedReview = source.detailedReview;
    copy.crossProviderReview = source.crossProviderReview;
    copy.claimReview = source.claimReview;
    copy.teamResult = source.teamResult;
    copy.escalation = source.escalation;
    copy.validationExecution = source.validationExecution;
    copy.checkpoint = source.checkpoint;
    copy.delta = source.delta;
    copy.failure = source.failure;
    copy.deltaId = source.deltaId;
    copy.status = source.status;
    copy.failureReason = source.failureReason;
    copy.nearMissId = source.nearMissId;
    copy.segmentCount = source.segmentCount;
    copy.noProgressSegments = source.noProgressSegments;
    copy.cooldownUntilRound = source.cooldownUntilRound;
    copy.metaAbandoned = source.metaAbandoned;
    copy.metaControlReason = source.metaControlReason;
    copy.focusObligationId = source.focusObligationId;
    copy.focusedCanonicalTargetId = source.focusedCanonicalTargetId;
    copy.focusedBottleneckFamilyId = source.focusedBottleneckFamilyId;
    copy.focusSource = source.focusSource;
    copy.latestResearchCheckpointId = source.latestResearchCheckpointId;
    copy.activeResearchFindingIds.addAll(source.activeResearchFindingIds);
    copy.lastCheckpointedProviderCallId = source.lastCheckpointedProviderCallId;
    copy.checkpointRecoveryCount = source.checkpointRecoveryCount;
    copy.pendingFindingReconciliation = source.pendingFindingReconciliation;
    copy.reviewComplete = source.reviewComplete;
    copy.checkpointProcessed = source.checkpointProcessed;
    copy.integrated = source.integrated;
    copy.activeSemanticPivotId = source.activeSemanticPivotId;
    copy.semanticPivotIds.addAll(source.semanticPivotIds);
    copy.activeStrategyEpochId = source.activeStrategyEpochId;
    copy.retiredActiveClaimIds.addAll(source.retiredActiveClaimIds);
    copy.pendingPivotProposedClaims.addAll(source.pendingPivotProposedClaims);
    copy.retiredStrategyFocusObligationIds.addAll(source.retiredStrategyFocusObligationIds);
    copy.activeMathematicalObjectIds.clear();
    copy.activeMathematicalObjectIds.addAll(source.activeMathematicalObjectIds);
    copy.activeDirectionSignature = source.activeDirectionSignature;
    return copy;
  }

  private void independentlyReview(RouteState route) {
    ensureRouteTeamCoversActualRisk(route);
    boolean skepticPassed =
        reviewAssignedRole(route, route.plan.skeptic(), "skeptic_review");
    boolean toolPassed = runToolAudit(route);
    if (route.plan.referee() == null || !route.plan.referee().assigned()) {
      route.status = "unverified";
      route.failureReason = "required independent referee is unavailable";
      event(
          "route_review_blocked",
          "independent_review",
          route.author.id(),
          "unverified",
          route.failureReason,
          route.deltaId);
      return;
    }

    AgentRuntime referee = requireAgent(route.plan.referee().agentId());
    VerificationPipeline.Result verification =
        new VerificationPipeline()
            .verify(
                Set.of(route.author.id()),
                referee.id(),
                () ->
                    runAttemptReview(
                        route,
                        referee,
                        "structural-verification-" + route.routeId + "-r" + roundIndex.get(),
                        "structural_verification",
                        VerificationStage.STRUCTURAL,
                        "Checking proof structure, dependencies, scopes, and quantifiers"),
                () ->
                    runAttemptReview(
                        route,
                        referee,
                        "detailed-verification-" + route.routeId + "-r" + roundIndex.get(),
                        "detailed_verification",
                        VerificationStage.DETAILED,
                        "Independently auditing every proof step"));
    route.structuralReview = verification.structuralReport();
    route.detailedReview = verification.detailedReport();
    boolean refereePassed =
        verification.passed()
            && route.detailedReview != null
            && route.detailedReview.problemIntegrityOk()
            && !route.detailedReview.checkedDependencies().isEmpty()
            && route.detailedReview.confidence() >= config.budget().verificationPassThreshold();
    RouteTeamResult teamResult =
        routeTeam.review(route.plan, skepticPassed, toolPassed, refereePassed);
    route.teamResult = teamResult;

    EscalationPlan escalation =
        new ValidationEscalator(ValidationEscalationPolicy.defaults())
            .plan(
                route.plan.risk().score(),
                reviewVerdicts(route),
                crossProviderRouteReviewer(route).isPresent(),
                route.toolAudit != null,
                true,
                false);
    route.escalation = escalation;
    event(
        "validation_escalation_planned",
        "independent_review",
        referee.id(),
        "completed",
        "Validation ladder: "
            + (escalation.levels().isEmpty()
                ? "none"
                : escalation.levels().stream().map(Enum::name).toList()),
        route.deltaId);
    route.validationExecution = executeRouteEscalation(route, escalation);
    event(
        "validation_escalation_executed",
        "independent_review",
        referee.id(),
        route.validationExecution.passed() ? "verified" : "unverified",
        route.validationExecution.passed()
            ? "Every required route validation level passed"
            : String.join("; ", route.validationExecution.diagnostics()),
        route.deltaId);

    boolean passed =
        verification.passed()
            && teamResult.globalShareAllowed()
            && skepticPassed
            && toolPassed
            && refereePassed
            && route.validationExecution.factPromotionAllowed();
    route.status = passed ? "verified" : "unverified";
    route.failureReason =
        passed
            ? ""
            : teamResult.diagnostics().isEmpty()
                ? reviewFailure(route)
                : String.join("; ", teamResult.diagnostics());
    event(
        "route_reviewed",
        "independent_review",
        referee.id(),
        passed ? "verified" : "unverified",
        passed
            ? "Skeptic, Tool Specialist, structural, and detailed Referee gates passed"
            : route.failureReason,
        route.detailedReview == null ? route.deltaId : route.detailedReview.reportId());
  }

  private void ensureRouteTeamCoversActualRisk(RouteState route) {
    boolean computationObserved = !computationTracesForRoute(route.routeId).isEmpty();
    boolean toolAlreadyAssigned =
        route.plan.toolSpecialist() != null && route.plan.toolSpecialist().assigned();
    if (!computationObserved || toolAlreadyAssigned) {
      return;
    }
    RiskAssessment actualRisk =
        routeTeam.classifyRisk(
            new RouteTeam.RiskSignals(
                !route.strategy.criticalClaims().isEmpty(),
                false,
                true,
                true,
                false,
                false,
                false,
                route.revisionCount > 0,
                true,
                true));
    route.plan = teamFactory.plan(route.routeId, route.author.id(), actualRisk);
    if (activeResearchWorker.get() == null) {
      registerRoute(route);
    }
    event(
        "proof_team_replanned",
        "independent_review",
        route.author.id(),
        "completed",
        "Observed computation evidence; assigned an independent Tool Specialist before review",
        route.routeId);
  }

  private boolean reviewAssignedRole(
      RouteState route, RoleAssignment assignment, String stageName) {
    if (assignment == null) {
      return true;
    }
    if (!assignment.assigned()) {
      return false;
    }
    AgentRuntime reviewer = requireAgent(assignment.agentId());
    VerificationReport report =
        runAttemptReview(
            route,
            reviewer,
            stageName + "-" + route.routeId + "-r" + roundIndex.get(),
            stageName,
            VerificationStage.STRUCTURAL,
            "Actively searching for counterexamples and invalid proof steps");
    route.skepticReview = report;
    return report.verdict() == VerificationVerdict.PASS && report.problemIntegrityOk();
  }

  private boolean runToolAudit(RouteState route) {
    RoleAssignment assignment = route.plan.toolSpecialist();
    if (assignment == null) {
      return true;
    }
    if (!assignment.assigned()) {
      return false;
    }
    AgentRuntime specialist = requireAgent(assignment.agentId());
    List<ComputationTrace> traces = computationTracesForRoute(route.routeId);
    List<ComputationAudit> audits = auditComputations(traces);
    StructuredCallResult<ToolAuditReport> call =
        callStage(
            "tool-audit-" + route.routeId + "-r" + roundIndex.get(),
            "tool_replay",
            ToolAuditReport.class,
            Map.of(
                "immutable_problem", frozenProblem,
                "problem_hash", problemHash,
                "candidate_attempt", route.attempt,
                "working_delta", route.delta,
                "recorded_computations", traces.stream().map(ComputationTrace::publicView).toList(),
                "independent_replay_audits", audits,
                "mapping_rule",
                    "Check the mathematical mapping separately from deterministic replay; bounded evidence never proves a universal claim."),
            specialist,
            "verification",
            "Independently replaying computation evidence and auditing its mathematical mapping");
    ToolAuditReport source = call.value();
    long replayEligible = traces.stream().filter(ComputationTrace::replayValid).count();
    boolean disabledEvidence = traces.stream().anyMatch(trace -> !trace.replayValid());
    boolean replayed =
        !disabledEvidence
            && replayEligible > 0
            && audits.size() == replayEligible
            && audits.stream().allMatch(ComputationAudit::valid);
    boolean passed =
        replayed
            && source.mathematicalMappingChecked()
            && "pass".equals(source.verdict());
    List<String> issues = new ArrayList<>(source.issues());
    audits.stream()
        .filter(audit -> !audit.valid())
        .map(ComputationAudit::diagnostic)
        .forEach(issues::add);
    if (traces.isEmpty()) {
      issues.add("required numerical evidence was not produced");
    }
    traces.stream()
        .filter(trace -> !trace.replayValid())
        .forEach(
            trace ->
                issues.add(
                    "computation evidence disabled after replay/repair failure: "
                        + trace.spec().experimentId()));
    route.toolAudit =
        new ToolAuditReport(
            specialist.id(),
            replayed,
            source.confidence(),
            traces.stream().map(trace -> trace.spec().experimentId()).toList(),
            issues,
            source.mathematicalMappingChecked(),
            audits.stream().map(ComputationAudit::replayedResultHash).filter(value -> !value.isBlank()).toList(),
            route.routeId,
            passed ? "pass" : "fail");
    return passed;
  }

  private ValidationExecution executeRouteEscalation(
      RouteState route, EscalationPlan escalation) {
    Map<ValidationLevel, java.util.function.Supplier<ValidationStepResult>> handlers =
        new EnumMap<>(ValidationLevel.class);
    handlers.put(
        ValidationLevel.DETERMINISTIC,
        () -> {
          boolean passed =
              route.delta != null
                  && route.checkpoint != null
                  && route.delta.problemHash().equals(problemHash)
                  && route.delta.parentCheckpointId().equals(route.checkpoint.checkpointId())
                  && route.delta.segmentIndex() == route.checkpoint.segmentIndex() + 1;
          return passed
              ? ValidationStepResult.passed(ValidationLevel.DETERMINISTIC, List.of(route.deltaId))
              : ValidationStepResult.failed(
                  ValidationLevel.DETERMINISTIC,
                  "delta identity, parent checkpoint, or segment order did not match");
        });
    handlers.put(
        ValidationLevel.BLIND_SAME_MODEL,
        () -> validationStep(ValidationLevel.BLIND_SAME_MODEL, route.skepticReview));
    handlers.put(
        ValidationLevel.ADVERSARIAL_BLIND,
        () -> validationStep(ValidationLevel.ADVERSARIAL_BLIND, route.detailedReview));
    handlers.put(
        ValidationLevel.CROSS_PROVIDER,
        () -> crossProviderRouteStep(route));
    handlers.put(
        ValidationLevel.TOOL_OR_FORMAL,
        () -> {
          ToolAuditReport audit = route.toolAudit;
          boolean passed =
              audit != null
                  && audit.allResultsReplayedIndependently()
                  && audit.mathematicalMappingChecked()
                  && "pass".equals(audit.verdict());
          return passed
              ? ValidationStepResult.passed(
                  ValidationLevel.TOOL_OR_FORMAL, audit.replayArtifactRefs())
              : ValidationStepResult.failed(
                  ValidationLevel.TOOL_OR_FORMAL,
                  "tool evidence did not pass independent replay and mathematical mapping");
        });
    return new ValidationEscalationExecutor().execute(escalation, handlers);
  }

  private ValidationStepResult validationStep(
      ValidationLevel level, VerificationReport report) {
    boolean passed =
        report != null
            && report.verdict() == VerificationVerdict.PASS
            && report.problemIntegrityOk()
            && report.confidence() >= config.budget().verificationPassThreshold();
    return passed
        ? ValidationStepResult.passed(level, List.of(evidenceId(report)))
        : ValidationStepResult.failed(level, "independent reviewer did not pass this level");
  }

  private ValidationStepResult crossProviderRouteStep(RouteState route) {
    if (route.crossProviderReview == null) {
      Optional<AgentRuntime> candidate = crossProviderRouteReviewer(route);
      if (candidate.isEmpty()) {
        return ValidationStepResult.missing(ValidationLevel.CROSS_PROVIDER);
      }
      route.crossProviderReview =
          runAttemptReview(
              route,
              candidate.orElseThrow(),
              "cross-provider-" + route.routeId + "-r" + roundIndex.get(),
              "detailed_verification",
              VerificationStage.DETAILED,
              "Running a cross-provider route-referee audit");
    }
    return validationStep(ValidationLevel.CROSS_PROVIDER, route.crossProviderReview);
  }

  private Optional<AgentRuntime> crossProviderRouteReviewer(RouteState route) {
    Set<String> excluded =
        java.util.stream.Stream.of(
                route.author.id(),
                route.plan.skeptic() == null ? null : route.plan.skeptic().agentId(),
                route.plan.toolSpecialist() == null ? null : route.plan.toolSpecialist().agentId(),
                route.plan.referee() == null ? null : route.plan.referee().agentId())
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
    return pool.agents().stream()
        .filter(agent -> !excluded.contains(agent.id()))
        .filter(agent -> !agent.provider().equals(route.author.provider()))
        .filter(
            agent ->
                agent.supportsRole("route_referee")
                    || agent.supportsRole("detailed_verifier")
                    || agent.supportsRole("final_verifier"))
        .findFirst();
  }

  private VerificationReport runAttemptReview(
      RouteState route,
      AgentRuntime reviewer,
      String idempotencyKey,
      String stageName,
      VerificationStage verificationStage,
      String summary) {
    StructuredCallResult<VerificationReport> call =
        callStage(
            idempotencyKey,
            stageName,
            VerificationReport.class,
            Map.of(
                "immutable_problem", frozenProblem,
                "problem_hash", problemHash,
                "candidate_attempt", route.attempt,
                "working_delta", route.delta,
                "computation_evidence", computationsForRoute(route.routeId),
                "required_target_id", route.attempt.attemptId(),
                "required_target_type", "attempt",
                "required_stage", verificationStage.value(),
                "author_excluded", route.author.id(),
                "review_rule", summary),
            reviewer,
            "verification",
            summary + " for " + route.routeId);
    return bindReview(
        call.value(),
        call,
        reviewer,
        route.attempt.attemptId(),
        "attempt",
        verificationStage);
  }

  private List<String> reviewVerdicts(RouteState route) {
    List<String> verdicts =
        java.util.stream.Stream.of(
                route.skepticReview,
                route.structuralReview,
                route.detailedReview,
                route.crossProviderReview)
        .filter(Objects::nonNull)
        .map(report -> report.verdict().value())
        .toList();
    if (route.toolAudit == null) {
      return verdicts;
    }
    return java.util.stream.Stream.concat(
            verdicts.stream(), java.util.stream.Stream.of(route.toolAudit.verdict()))
        .toList();
  }

  private String reviewFailure(RouteState route) {
    String reviewerFailure =
        java.util.stream.Stream.of(
                route.skepticReview,
                route.structuralReview,
                route.detailedReview,
                route.crossProviderReview)
        .filter(Objects::nonNull)
        .filter(report -> report.verdict() != VerificationVerdict.PASS)
        .map(VerificationReport::conciseFeedback)
        .filter(value -> value != null && !value.isBlank())
        .findFirst()
        .orElse("");
    if (!reviewerFailure.isBlank()) {
      return reviewerFailure;
    }
    if (route.toolAudit != null && !"pass".equals(route.toolAudit.verdict())) {
      return route.toolAudit.issues().isEmpty()
          ? "independent tool replay did not pass"
          : route.toolAudit.issues().getFirst();
    }
    return "one or more independent proof gates did not pass";
  }

  private List<Map<String, Object>> computationsForRoute(String routeId) {
    return computationTracesForRoute(routeId).stream()
        .map(ComputationTrace::publicView)
        .toList();
  }

  private List<ComputationTrace> computationTracesForRoute(String routeId) {
    return computationTraces.stream()
        .filter(trace -> trace.routeId().equals(routeId))
        .toList();
  }

  private List<ComputationAudit> auditComputations(List<ComputationTrace> traces) {
    List<ComputationAudit> audits =
        traces.stream()
            .filter(ComputationTrace::replayValid)
            .filter(trace -> trace.decision().decision() == ComputationDecisionStatus.ALLOW)
            .filter(trace -> trace.result() != null)
            .map(
                trace ->
                    computation.auditExperiment(
                        trace.spec(), trace.decision(), trace.program(), trace.result()))
            .toList();
    for (ComputationAudit audit : audits) {
      recordComputationAudit(audit);
    }
    return audits;
  }

  private void recordComputationAudit(ComputationAudit audit) {
    ResearchWorkerContext worker = activeResearchWorker.get();
    if (worker != null) {
      worker.computationAudits.removeIf(
          existing ->
              existing.experimentId().equals(audit.experimentId())
                  && existing.requestHash().equals(audit.requestHash()));
      worker.computationAudits.add(audit);
      return;
    }
    computationAudits.removeIf(
        existing ->
            existing.experimentId().equals(audit.experimentId())
                && existing.requestHash().equals(audit.requestHash()));
    computationAudits.add(audit);
    event(
        "computation_replay_audit",
        "tool_replay",
        null,
        audit.valid() ? "verified" : "unverified",
        audit.diagnostic(),
        audit.replayedResultHash().isBlank()
            ? audit.recordedResultHash()
            : audit.replayedResultHash());
  }

  private void acknowledgeConsumedMessages(RouteState route) {
    if (route.attempt == null) {
      return;
    }
    Set<String> verifiedSteps =
        route.attempt.proofSteps().stream()
            .map(ProofStep::stepId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    List<String> pendingArtifactRequests =
        mathematicalArtifactBroker.pendingProviderRequestsForRoute(route.routeId);
    for (String providerRequestId : pendingArtifactRequests) {
      mathematicalArtifactBroker.acknowledge(providerRequestId, verifiedSteps);
    }
    if (!pendingArtifactRequests.isEmpty()) {
      persistUnchecked("broker_use_receipt", false);
    }
    if (route.pendingDeliveries.isEmpty()) {
      return;
    }
    event(
        "legacy_broker_delivery_unattributed",
        "cross_route_broker",
        route.author.id(),
        "audit_only",
        "Legacy PROMPT_CONSUMED deliveries remain historical audit data and cannot receive "
            + "automatic proof-step attribution or scheduler utility",
        route.routeId);
    route.pendingDeliveries.clear();
  }

  private Set<String> refutedClaimIdsForRoute(RouteState route) {
    return Set.copyOf(route.rejectedClaimIds);
  }

  private void verifyConsumedArtifactEffects(RouteState route) {
    if (route.attempt == null) {
      return;
    }
    Set<String> committedSteps =
        route.attempt.proofSteps().stream()
            .map(ProofStep::stepId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    Set<String> verifiedClaims =
        typedMemory.facts().stream()
            .map(MessageEnvelope::messageId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    Set<String> refutedClaims = refutedClaimIdsForRoute(route);
    Set<String> closedObligations =
        proofGraph.obligations().stream()
            .filter(obligation -> "closed".equals(obligation.status()))
            .map(ProofObligation::obligationId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    int utilitiesBefore = mathematicalArtifactBroker.utilities().size();
    mathematicalArtifactBroker.receipts().stream()
        .filter(receipt -> receipt.routeId().equals(route.routeId))
        .filter(receipt -> receipt.status() == BrokerArtifactReceiptStatus.USED_PENDING_EFFECT)
        .forEach(
            receipt -> {
              var lineage =
                  mathematicalArtifactBroker.lineage().stream()
                      .filter(value -> value.deliveryId().equals(receipt.deliveryId()))
                      .findFirst()
                      .orElse(null);
              BrokerArtifactEffectObservation observation =
                  new BrokerArtifactEffectObservation(
                      committedSteps,
                      verifiedClaims,
                      refutedClaims,
                      closedObligations,
                      route.retiredActiveClaimIds,
                      route.focusedCanonicalTargetId,
                      lineage == null ? null : lineage.repairId(),
                      lineage == null ? null : lineage.pivotId(),
                      lineage == null ? null : lineage.computationPlanId(),
                      false,
                      Math.max(0.0d, proofGraph.canonicalProofDebt(route.routeId)));
              mathematicalArtifactBroker.verifyEffect(receipt.deliveryId(), observation);
            });
    if (mathematicalArtifactBroker.utilities().size() != utilitiesBefore) {
      persistUnchecked("broker_verified_utility", false);
    }
  }

  private List<AttemptArtifactRecord> harvestAttemptArtifacts(RouteState route) {
    Set<String> obligationIds =
        proofGraph.obligations().stream()
            .map(ProofObligation::obligationId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    List<ClaimCard> claims =
        route.attempt.proposedLemmas().stream().map(source -> bindClaim(source, route)).toList();
    lemmaMemory.addMany(
        claims.stream()
            .filter(
                claim ->
                    !mismatchesBoundCriticalClaim(
                        route, claim.claimId(), claim.statement()))
            .filter(claim -> isolatableClaimCourtContextFailure(route, claim).isEmpty())
            .toList());
    List<AttemptArtifactRecord> harvested =
        new ArrayList<>(
            attemptArtifactHarvester.harvest(
                problemHash,
                route.routeId,
                route.deltaId,
                route.status,
                route.attempt,
                obligationIds));
    if ("verified".equals(route.status)) {
      ClaimCard theorem = routeTheoremClaim(route);
      attemptArtifactHarvester
          .harvestRouteTheorem(
              problemHash,
              route.routeId,
              route.deltaId,
              route.status,
              route.attempt,
              theorem,
              route.validationExecution != null && route.validationExecution.passed(),
              route.validationExecution != null
                  && route.validationExecution.factPromotionAllowed())
          .ifPresent(
              artifact -> {
                lemmaMemory.addMany(List.of(theorem));
                harvested.add(artifact);
              });
    }
    List<AttemptArtifactRecord> stored = attemptArtifacts.addAll(harvested);
    stored.stream().map(AttemptArtifactRecord::artifactId).forEach(route.artifactIds::add);
    Set<String> harvestedClaimIds =
        stored.stream()
            .map(AttemptArtifactRecord::claimId)
            .collect(java.util.stream.Collectors.toSet());
    route.pendingPivotProposedClaims.removeIf(
        claim -> harvestedClaimIds.contains(claim.claimId()));
    return stored;
  }

  private AttemptArtifactRecord rejectUnboundModernLocalClaim(
      RouteState route, AttemptArtifactRecord record) {
    if (record.status() != AttemptArtifactStatus.HARVESTED
        || record.kind() == AttemptArtifactKind.ROUTE_THEOREM
        || route.attempt.claimSemanticContextManifestVersion() != 1
        || route.strategy.criticalClaims().stream()
            .anyMatch(claim -> claim.claimId().equals(record.claimId()))) {
      return record;
    }
    boolean bound =
        route.attempt.claimSemanticContextBindings().stream()
            .anyMatch(binding -> binding.claimId().equals(record.claimId()));
    if (bound) {
      return record;
    }
    event(
        "claim_context_rejected",
        "claim_court",
        record.authorAgentId(),
        "blocked",
        "MISSING_ATTEMPT_LOCAL_CLAIM_CONTEXT_BINDING",
        record.claimId());
    return attemptArtifacts.markUncertain(
        record.artifactId(),
        "MISSING_ATTEMPT_LOCAL_CLAIM_CONTEXT_BINDING:" + record.claimId());
  }

  private ClaimCourtReviewResult conductClaimCourt(
      RouteState route, AttemptArtifactRecord artifact) {
    ClaimCard claim = proofClaimForArtifact(artifact);
    FrozenClaimSnapshot frozen =
        claimFreezeService.freeze(
            problemHash,
            rootGoal().sourceStatementHash(),
            route.routeId,
            claim,
            claimCourtSemanticContext(route, claim));
    ClaimCourtRecord existing = activeClaimCourt().findProofCase(frozen).orElse(null);
    ClaimProofRevisionRecord original =
        existing == null
            ? activeClaimProofRevisions()
                .createOriginal(frozen, claim.proofSteps(), claim.evidenceRefs())
            : activeClaimProofRevisions().get(existing.frozenClaim().initialProofRevisionId());
    failClaimCourtAt(ClaimCourtFailurePoint.AFTER_CLAIM_FREEZE);

    route.courtCaseIds.add(existing == null ? frozen.courtCaseId() : existing.courtCaseId());
    ClaimCourtRolePolicy.Assignment assignment =
        existing == null
            ? claimCourtAssignment(frozen.authorAgentId(), route.routeId).orElse(null)
            : existing.roleAssignment();
    if (existing == null && assignment == null) {
      ClaimCourtRecord deferred = activeClaimCourt().deferIndependence(frozen);
      return claimCourtResult(deferred, original, "claim-court");
    }
    if (assignment != null) {
      claimCourtRolePolicy.requireIndependent(assignment);
    }
    ClaimCourtRecord record =
        existing == null ? activeClaimCourt().open(frozen, assignment) : existing;

    while (!record.status().terminal()) {
      try {
        record = advanceClaimCourt(route, artifact, record, assignment);
      } catch (ClaimCourtStageQuarantinedException exception) {
        record = activeClaimCourt().defer(record.courtCaseId(), exception.getMessage());
      }
    }
    ClaimProofRevisionRecord revision =
        activeClaimProofRevisions().get(record.currentProofRevisionId());
    return claimCourtResult(record, revision, finalAuthorityAgent(record));
  }

  private FrozenClaimSemanticContext claimCourtSemanticContext(
      RouteState route, ClaimCard claim) {
    StrategyBlueprintCompiler.Compilation blueprint =
        strategyBlueprints.get(route.strategy.strategyId());
    if (blueprint == null) {
      throw new IllegalArgumentException(
          "MISSING_CLAIM_COURT_SEMANTIC_CONTEXT_BINDING:" + claim.claimId());
    }
    ProofControlModels.ScopeSignature rootScope =
        proofControl
            .scopeGuard()
            .extract("goal-scope", rootGoal().sourceStatement(), List.of(), 1.0d);
    CriticalClaimContext root =
        rootCriticalClaimContext(route.strategy, blueprint, rootScope);
    Map<String, CriticalClaimContext> compiled =
        criticalClaimContextCompiler.compile(route.strategy, blueprint, root);
    CriticalClaim boundClaim =
        route.strategy.criticalClaims().stream()
            .filter(value -> value.claimId().equals(claim.claimId()))
            .findFirst()
            .orElse(null);
    if (boundClaim != null
        && !topology.mathNormalize(boundClaim.statement())
            .equals(topology.mathNormalize(claim.statement()))) {
      throw new IllegalArgumentException(
          "CLAIM_COURT_CONTEXT_STATEMENT_MISMATCH:" + claim.claimId());
    }
    CriticalClaimContext bound = compiled.getOrDefault(claim.claimId(), root);
    boolean explicitBinding =
        route.strategy.criticalClaimContextBindings().stream()
            .anyMatch(value -> value.claimId().equals(claim.claimId()));
    if (boundClaim != null && !explicitBinding) {
      LinkedHashSet<String> legacyScope =
          new LinkedHashSet<>(bound.scopeLimitations());
      legacyScope.add(LEGACY_INCOMPLETE_SEMANTIC_CONTEXT);
      bound =
          new CriticalClaimContext(
              bound.assumptions(),
              bound.quantifiers(),
              List.copyOf(legacyScope),
              bound.variableBindings(),
              bound.polarity());
    } else if (boundClaim == null) {
      ClaimSemanticContextBinding localBinding =
          route.attempt.claimSemanticContextBindings().stream()
              .filter(value -> value.claimId().equals(claim.claimId()))
              .findFirst()
              .orElse(null);
      if (localBinding != null) {
        bound =
            new CriticalClaimContext(
                localBinding.localAssumptions(),
                localBinding.quantifiers(),
                localBinding.scopeLimitations(),
                localBinding.variableBindings(),
                localBinding.polarity());
      } else if (claim.tags().contains("route_theorem")) {
        bound = root;
      } else if (route.attempt.claimSemanticContextManifestVersion() == 1) {
        throw new IllegalArgumentException(
            "MISSING_ATTEMPT_LOCAL_CLAIM_CONTEXT_BINDING:" + claim.claimId());
      } else {
        bound =
            new CriticalClaimContext(
                List.of(),
                List.of(),
                FrozenClaimSemanticContext.legacyIncomplete(
                        effectiveClaimScope(claim))
                    .scopeLimitations(),
                List.of(),
                "positive");
      }
    }
    return claimCourtSemanticContexts.compile(
        claim,
        new FrozenClaimSemanticContext(
            bound.assumptions(),
            bound.quantifiers(),
            bound.variableBindings(),
            bound.scopeLimitations(),
            bound.polarity()));
  }

  private ClaimCourtRecord advanceClaimCourt(
      RouteState route,
      AttemptArtifactRecord artifact,
      ClaimCourtRecord record,
      ClaimCourtRolePolicy.Assignment assignment) {
    return switch (record.status()) {
      case FROZEN -> activeClaimCourt().beginStatementScreening(record.courtCaseId());
      case STATEMENT_SCREENING -> screenClaimStatement(route, artifact, record, assignment);
      case PROOF_AUDIT_PENDING -> auditClaimProof(route, artifact, record, assignment);
      case PROOF_VALID, REPAIRED_PENDING_ADJUDICATION ->
          activeClaimCourt().beginBlindAdjudication(record.courtCaseId());
      case PROOF_INVALID_REPAIRABLE ->
          activeClaimCourt().beginRepair(record.courtCaseId(), claimCourtConfig);
      case REPAIR_PENDING -> repairClaimProof(route, artifact, record, assignment);
      case BLIND_ADJUDICATION_PENDING ->
          adjudicateClaimProof(route, artifact, record, assignment);
      default -> throw new IllegalStateException("unsupported active Claim Court status " + record.status());
    };
  }

  private ClaimCourtRecord screenClaimStatement(
      RouteState route,
      AttemptArtifactRecord artifact,
      ClaimCourtRecord record,
      ClaimCourtRolePolicy.Assignment assignment) {
    FrozenClaimSnapshot frozen = record.frozenClaim();
    AgentRuntime falsifier = requireAgent(assignment.falsifierAgentId());
    Map<String, ?> context =
        Map.ofEntries(
            Map.entry("immutable_problem", frozenProblem),
            Map.entry("problem_hash", problemHash),
            Map.entry("root_goal_hash", rootGoal().sourceStatementHash()),
            Map.entry("frozen_claim", frozen),
            Map.entry(
                "proof_revision",
                activeClaimProofRevisions().get(record.currentProofRevisionId())),
            Map.entry("counterexample_authority_rule", "Return candidates only; the server verifies exact authority."));
    CourtStageResult<ClaimStatementFalsificationBatch> stage =
        executeClaimCourtStage(
            record.courtCaseId(),
            ClaimCourtStage.STATEMENT_FALSIFICATION,
            ClaimStatementFalsificationBatch.class,
            context,
            falsifier,
            call -> bindStatementFalsification(route, call, falsifier.id()));
    ClaimStatementFalsificationDecision decision =
        statementDecision(stage.value(), frozen.claimId());
    List<StatementCounterexampleCandidate> candidates =
        decision.counterexampleCandidates().stream()
            .filter(candidate -> exactCounterexampleCandidate(candidate, frozen))
            .toList();
    List<ClaimRefutationEvidence> evidence =
        candidates.isEmpty()
            ? List.of()
            : reviewCounterexampleWitnesses(
                route, record, candidates, requireAgent(assignment.auditorAgentId()), "falsification");
    ClaimStatementAuthorityService.Result assessment =
        claimStatementAuthority.assess(
            frozen,
            decision,
            activeNegativeKnowledgeRegistry(),
            roundIndex.get(),
            evidence);
    ClaimCourtRecord updated =
        activeClaimCourt().recordStatementAssessment(record.courtCaseId(), assessment);
    activeClaimCourtExecutions().complete(stage.executionId());
    return updated;
  }

  private ClaimCourtRecord auditClaimProof(
      RouteState route,
      AttemptArtifactRecord artifact,
      ClaimCourtRecord record,
      ClaimCourtRolePolicy.Assignment assignment) {
    AgentRuntime auditor = requireAgent(assignment.auditorAgentId());
    ClaimProofRevisionRecord revision =
        activeClaimProofRevisions().get(record.currentProofRevisionId());
    Map<String, ?> context =
        Map.ofEntries(
            Map.entry("immutable_problem", frozenProblem),
            Map.entry("frozen_claim", record.frozenClaim()),
            Map.entry("proof_revision", revision),
            Map.entry("candidate_artifact", artifact),
            Map.entry("verified_dependency_claim_ids", verifiedClaimIdSet()),
            Map.entry("authority_rule", "Audit proof validity only; do not pronounce the statement false."));
    CourtStageResult<ClaimProofAuditBatch> stage =
        executeClaimCourtStage(
            record.courtCaseId(),
            ClaimCourtStage.PROOF_AUDIT,
            ClaimProofAuditBatch.class,
            context,
            auditor,
            call -> bindProofAudit(route, call, auditor.id()));
    ClaimProofAuditDecision decision =
        deterministicProofAuditBoundary(
            proofAuditDecision(stage.value(), record.frozenClaim().claimId()), revision);
    ClaimCourtRecord updated =
        activeClaimCourt()
            .recordProofAudit(record.courtCaseId(), stage.value().batchId(), decision);
    activeClaimCourtExecutions().complete(stage.executionId());
    failClaimCourtAt(ClaimCourtFailurePoint.AFTER_PROOF_AUDIT_RESULT);
    return updated;
  }

  private ClaimCourtRecord repairClaimProof(
      RouteState route,
      AttemptArtifactRecord artifact,
      ClaimCourtRecord record,
      ClaimCourtRolePolicy.Assignment assignment) {
    AgentRuntime repairer = requireAgent(assignment.repairerAgentId());
    ClaimProofRevisionRecord base =
        activeClaimProofRevisions().get(record.currentProofRevisionId());
    ClaimProofAuditDecision audit = proofAuditFor(record);
    Map<String, ?> context =
        Map.ofEntries(
            Map.entry("immutable_problem", frozenProblem),
            Map.entry("frozen_claim", record.frozenClaim()),
            Map.entry("base_proof_revision", base),
            Map.entry("proof_audit", audit),
            Map.entry("candidate_artifact", artifact),
            Map.entry("verified_dependency_claim_ids", verifiedClaimIdSet()),
            Map.entry("repair_limits", claimCourtConfig),
            Map.entry("authority_rule", "Return only a bounded patch; repair never grants Claim authority."));
    CourtStageResult<ClaimMinimalRepairBatch> stage =
        executeClaimCourtStage(
            record.courtCaseId(),
            ClaimCourtStage.MINIMAL_REPAIR,
            ClaimMinimalRepairBatch.class,
            context,
            repairer,
            call -> bindMinimalRepair(route, call, repairer.id()));
    ClaimMinimalRepairDecision decision =
        minimalRepairDecision(stage.value(), record.frozenClaim().claimId());
    ClaimCourtRecord updated;
    if (decision.disposition() != ClaimMinimalRepairDisposition.PATCH_PROPOSED) {
      boolean exhausted = decision.disposition() == ClaimMinimalRepairDisposition.REPAIR_DECLINED;
      updated =
          activeClaimCourt().recordRepairFailure(
              record.courtCaseId(), exhausted, decision.disposition().name());
    } else {
      ClaimProofPatchValidator.ValidationResult validation =
          new ClaimProofPatchValidator(claimCourtConfig)
              .validate(
                  record.frozenClaim(),
                  base,
                  audit,
                  decision.patch(),
                  verifiedClaimIdSet(),
                  trustedClaimEvidence(record.frozenClaim()));
      failClaimCourtAt(ClaimCourtFailurePoint.AFTER_REPAIR_PATCH_VALIDATION);
      if (!validation.passed()) {
        updated =
            activeClaimCourt().recordRepairFailure(
                record.courtCaseId(),
                record.repairAttempts() >= claimCourtConfig.maxRepairAttempts(),
                String.join(",", validation.failureCodes()));
      } else {
        ClaimProofRevisionRecord repaired =
            activeClaimProofRevisions().createRepaired(
                record.frozenClaim(),
                base,
                decision.patch(),
                validation.proofSteps(),
                validation.evidenceRefs(),
                repairer.id());
        failClaimCourtAt(ClaimCourtFailurePoint.AFTER_REPAIRED_REVISION_WRITE);
        updated =
            activeClaimCourt()
                .recordRepairedRevision(record.courtCaseId(), repaired.revisionId());
      }
    }
    activeClaimCourtExecutions().complete(stage.executionId());
    return updated;
  }

  private ClaimCourtRecord adjudicateClaimProof(
      RouteState route,
      AttemptArtifactRecord artifact,
      ClaimCourtRecord record,
      ClaimCourtRolePolicy.Assignment assignment) {
    ClaimProofRevisionRecord revision =
        activeClaimProofRevisions().get(record.currentProofRevisionId());
    Set<String> verifiedClaims = verifiedClaimIdSet();
    if (!verifiedClaims.containsAll(revision.dependencyClaimIds())) {
      return activeClaimCourt().recordRepairFailure(
          record.courtCaseId(),
          record.repairAttempts() > 0,
          "blind adjudication blocked by unverified dependency");
    }
    ClaimBlindReviewPacket packet;
    try {
      packet =
          claimBlindPackets.create(
              record.frozenClaim(),
              revision,
              verifiedClaims,
              trustedClaimEvidence(record.frozenClaim()));
    } catch (IllegalArgumentException exception) {
      if (claimEvidenceFailure(exception.getMessage())) {
        return activeClaimCourt().recordRepairFailure(
            record.courtCaseId(),
            record.repairAttempts() > 0,
            exception.getMessage());
      }
      throw exception;
    }
    AgentRuntime adjudicator = requireAgent(assignment.blindAdjudicatorAgentId());
    Map<String, ?> context =
        Map.ofEntries(
            Map.entry("immutable_problem", frozenProblem),
            Map.entry("blind_claim_packet", packet),
            Map.entry("candidate_artifact_kind", artifact.kind().name()),
            Map.entry("identity_isolation_rule", "No author, falsifier, auditor, repairer, or prior verdict metadata is supplied."));
    CourtStageResult<ClaimBlindAdjudicationBatch> stage =
        executeClaimCourtStage(
            record.courtCaseId(),
            ClaimCourtStage.BLIND_ADJUDICATION,
            ClaimBlindAdjudicationBatch.class,
            context,
            adjudicator,
            call -> bindBlindAdjudication(route, call, adjudicator.id()));
    ClaimBlindAdjudicationDecision decision =
        blindDecision(stage.value(), record.frozenClaim().claimId());
    ClaimCourtRecord updated;
    if (decision.verdict() == ClaimBlindAdjudicationVerdict.COUNTEREXAMPLE_CANDIDATE) {
      List<StatementCounterexampleCandidate> candidates =
          decision.counterexampleCandidates().stream()
              .filter(candidate -> exactCounterexampleCandidate(candidate, record.frozenClaim()))
              .toList();
      List<ClaimRefutationEvidence> evidence =
          candidates.isEmpty()
              ? List.of()
              : reviewCounterexampleWitnesses(
                  route,
                  record,
                  candidates,
                  requireAgent(assignment.auditorAgentId()),
                  "blind-adjudication");
      updated =
          evidence.isEmpty()
              ? activeClaimCourt().recordBlindAdjudication(
                  record.courtCaseId(), ClaimBlindAdjudicationVerdict.INCONCLUSIVE)
              : activeClaimCourt().recordVerifiedRefutation(
                  record.courtCaseId(), evidence, "independent blind counterexample verified");
    } else {
      updated =
          activeClaimCourt().recordBlindAdjudication(record.courtCaseId(), decision.verdict());
    }
    if (updated.outcome() == ClaimCourtOutcome.VERIFIED) {
      activeClaimProofRevisions().markBlindVerified(revision.revisionId());
    } else if (decision.verdict() == ClaimBlindAdjudicationVerdict.FAIL_PROOF) {
      activeClaimProofRevisions().markBlindRejected(revision.revisionId());
    }
    activeClaimCourtExecutions().complete(stage.executionId());
    failClaimCourtAt(ClaimCourtFailurePoint.AFTER_BLIND_RESULT_DURABLE);
    return updated;
  }

  private List<TrustedClaimEvidence> trustedClaimEvidence(
      FrozenClaimSnapshot frozen) {
    List<TrustedClaimEvidence> trusted = new ArrayList<>();
    synchronized (computationTraces) {
      for (ComputationTrace trace : computationTraces) {
        trusted.addAll(trustedComputationEvidence(trace, frozen));
      }
    }
    typedMemory.facts().stream()
        .filter(fact -> exactVerifiedFactForFrozenClaim(fact, frozen))
        .forEach(
            fact -> {
              String reference = "memory://fact/" + fact.messageId();
              EvidenceRef evidenceRef =
                  new EvidenceRef(
                      reference,
                      fact.contentHash(),
                      null,
                      "Server-resolved verified Fact evidence.");
              trusted.add(
                  new TrustedClaimEvidence(
                      "claim-evidence-fact-" + fact.messageId(),
                      evidenceRef,
                      problemHash,
                      frozen.claimSemanticHash(),
                      fact.evidenceType() == EvidenceType.FORMAL_KERNEL_CERTIFICATE
                          ? ClaimTrustedEvidenceAuthority.FORMAL_CERTIFICATE
                          : ClaimTrustedEvidenceAuthority.VERIFIED_FACT,
                      true,
                      true,
                      true,
                      true));
            });
    return List.copyOf(trusted);
  }

  private List<TrustedClaimEvidence> trustedComputationEvidence(
      ComputationTrace trace, FrozenClaimSnapshot frozen) {
    if (trace.result() == null
        || !trace.replayValid()
        || (trace.authority() != ComputationEvidenceGate.EvidenceAuthority.VERIFIED
            && trace.authority() != ComputationEvidenceGate.EvidenceAuthority.VERIFIED_BOUNDED)
        || !computationTargetsFrozenClaim(trace, frozen)) {
      return List.of();
    }
    List<TrustedClaimEvidence> trusted = new ArrayList<>();
    for (EvidenceRef reference : trace.result().artifactRefs()) {
      if (reference.contentHash() == null || reference.contentHash().isBlank()) {
        continue;
      }
      trusted.add(
          new TrustedClaimEvidence(
              "claim-evidence-"
                  + trace.result().experimentId()
                  + "-"
                  + CanonicalJson.stableHash(reference).substring(0, 12),
              reference,
              problemHash,
              frozen.claimSemanticHash(),
              ClaimTrustedEvidenceAuthority.REPLAYED_COMPUTATION,
              true,
              true,
              true,
              true));
    }
    return List.copyOf(trusted);
  }

  List<TrustedClaimEvidence> trustedComputationEvidenceForTest(
      ExperimentSpec spec,
      ExperimentResult result,
      FrozenClaimSnapshot frozen,
      ComputationEvidenceGate.EvidenceAuthority authority,
      boolean replayValid) {
    return trustedComputationEvidence(
        new ComputationTrace(
            "claim-evidence-test",
            spec,
            null,
            null,
            result,
            null,
            authority,
            replayValid),
        frozen);
  }

  private boolean computationTargetsFrozenClaim(
      ComputationTrace trace, FrozenClaimSnapshot frozen) {
    return computationEvidenceMatchesFrozenClaim(trace.spec(), trace.result(), frozen);
  }

  private boolean computationEvidenceMatchesFrozenClaim(
      ExperimentSpec spec, ExperimentResult result, FrozenClaimSnapshot frozen) {
    String targetClaimId = result.targetClaimId();
    ClaimEvidenceSemanticBinding requestBinding =
        spec.claimEvidenceSemanticBinding();
    ClaimEvidenceSemanticBinding resultBinding =
        result.claimEvidenceSemanticBinding();
    if (targetClaimId == null
        || targetClaimId.isBlank()
        || !targetClaimId.equals(frozen.claimId())
        || requestBinding == null
        || !requestBinding.equals(resultBinding)) {
      return false;
    }
    ClaimEvidenceSemanticBinding expected =
        new ClaimEvidenceSemanticBinding(
            frozen.problemHash(),
            frozen.claimId(),
            frozen.claimStatementHash(),
            frozen.claimSemanticHash(),
            frozen.statement(),
            frozen.conclusion(),
            frozen.assumptions(),
            frozen.quantifiers(),
            frozen.variableBindings(),
            frozen.scopeLimitations(),
            frozen.polarity(),
            frozen.dependencyClaimIds(),
            spec.domains());
    boolean exactStatement =
        topology.mathNormalize(result.targetClaim())
                .equals(topology.mathNormalize(frozen.statement()))
            || topology.mathNormalize(result.targetClaim())
                .equals(topology.mathNormalize(frozen.conclusion()));
    return requestBinding.equals(expected)
        && exactStatement
        && spec.assumptions().equals(frozen.assumptions())
        && resultScopeMatchesDomains(result.scope(), spec.domains());
  }

  private static boolean resultScopeMatchesDomains(ObjectNode scope, ObjectNode domains) {
    JsonNode reportedDomains = scope.get("domains");
    if (reportedDomains != null) {
      return reportedDomains.equals(domains);
    }
    return domains.isEmpty() && scope.path("complete_domain").asBoolean(false);
  }

  private boolean exactVerifiedFactForFrozenClaim(
      MessageEnvelope fact, FrozenClaimSnapshot frozen) {
    return fact.memoryTier() == MemoryTier.FACT
        && fact.verificationStatus() == ClaimStatus.VERIFIED
        && fact.problemHash().equals(frozen.problemHash())
        && fact.statement().equals(frozen.statement())
        && fact.conclusion().equals(frozen.conclusion())
        && fact.assumptions().equals(frozen.assumptions())
        && fact.quantifiers().equals(frozen.quantifiers())
        && fact.variableBindings().equals(frozen.variableBindings())
        && fact.scopeLimitations().equals(frozen.scopeLimitations())
        && fact.dependencies().equals(frozen.dependencyClaimIds())
        && frozen.claimStatementHash().equals(fact.claimStatementHash())
        && frozen.claimSemanticHash().equals(fact.claimSemanticHash())
        && frozen.polarity().equals(fact.polarity());
  }

  private static boolean claimEvidenceFailure(String message) {
    if (message == null) {
      return false;
    }
    return message.startsWith("UNKNOWN_EVIDENCE_REF")
        || message.startsWith("EVIDENCE_CONTENT_HASH_MISMATCH")
        || message.startsWith("EVIDENCE_PROBLEM_SCOPE_MISMATCH")
        || message.startsWith("EVIDENCE_CLAIM_SCOPE_MISMATCH")
        || message.startsWith("EVIDENCE_NOT_VERIFIED")
        || message.startsWith("EVIDENCE_REPLAY_NOT_VERIFIED")
        || message.startsWith("EVIDENCE_AUTHORITY_ESCALATION");
  }

  private List<ClaimRefutationEvidence> reviewCounterexampleWitnesses(
      RouteState route,
      ClaimCourtRecord record,
      List<StatementCounterexampleCandidate> candidates,
      AgentRuntime reviewer,
      String sourceStage) {
    Map<String, ?> context =
        Map.ofEntries(
            Map.entry("immutable_problem", frozenProblem),
            Map.entry("frozen_claim", record.frozenClaim()),
            Map.entry("counterexample_candidates", candidates),
            Map.entry("source_stage", sourceStage),
            Map.entry("authority_rule", "Accept only a replayable witness exactly bound to the frozen Claim."));
    CourtStageResult<ClaimCounterexampleWitnessReviewBatch> stage =
        executeClaimCourtStage(
            record.courtCaseId(),
            ClaimCourtStage.COUNTEREXAMPLE_WITNESS_REVIEW,
            ClaimCounterexampleWitnessReviewBatch.class,
            context,
            reviewer,
            call -> bindWitnessReview(call, reviewer.id()));
    Map<String, ClaimCounterexampleWitnessReviewDecision> decisions = new LinkedHashMap<>();
    stage.value().decisions().forEach(decision -> decisions.put(decision.candidateId(), decision));
    List<ClaimRefutationEvidence> evidence = new ArrayList<>();
    for (StatementCounterexampleCandidate candidate : candidates) {
      ClaimCounterexampleWitnessReviewDecision decision = decisions.get(candidate.candidateId());
      if (decision != null
          && decision.claimId().equals(record.frozenClaim().claimId())
          && decision.statementHash().equals(record.frozenClaim().claimStatementHash())
          && decision.exactWitnessAccepted()) {
        evidence.add(
            new ClaimRefutationEvidence(
                "claim-refutation-"
                    + CanonicalJson.stableHash(
                            List.of(
                                record.courtCaseId(),
                                candidate.candidateId(),
                                candidate.witness(),
                                stage.executionId()))
                        .substring(0, 24),
                ClaimRefutationEvidenceType.INDEPENDENT_WITNESS_ADJUDICATION,
                record.frozenClaim().claimId(),
                record.frozenClaim().claimStatementHash(),
                record.frozenClaim().claimSemanticHash(),
                candidate.witness(),
                "claim-court-witness://" + stage.executionId(),
                true,
                true));
      }
    }
    activeClaimCourtExecutions().complete(stage.executionId());
    return List.copyOf(evidence);
  }

  private <T> CourtStageResult<T> executeClaimCourtStage(
      String courtCaseId,
      ClaimCourtStage courtStage,
      Class<T> responseType,
      Map<String, ?> context,
      AgentRuntime agent,
      Function<StructuredCallResult<T>, T> binder) {
    ClaimCourtStageExecutionRecord execution =
        activeClaimCourtExecutions().reserve(
            courtCaseId,
            courtStage,
            List.of(activeClaimCourt().get(courtCaseId).frozenClaim().claimId()),
            claimCourtStageInputHash(context),
            agent.id());
    if (execution.status() == ClaimCourtStageExecutionStatus.RESULT_DURABLE
        || execution.status() == ClaimCourtStageExecutionStatus.COMPLETED) {
      return new CourtStageResult<>(
          ContractObjectMapper.read(execution.resultPayload(), responseType), execution.executionId());
    }
    if (execution.status() == ClaimCourtStageExecutionStatus.RUNNING) {
      activeClaimCourtExecutions().quarantineInterrupted(execution.executionId());
      throw new ClaimCourtStageQuarantinedException(
          "ambiguous Claim Court provider call quarantined: " + execution.executionId());
    }
    if (execution.status() == ClaimCourtStageExecutionStatus.QUARANTINED) {
      throw new ClaimCourtStageQuarantinedException(
          "quarantined Claim Court provider call cannot be replayed: " + execution.executionId());
    }
    activeClaimCourtExecutions().start(execution.executionId());
    persistClaimCourtStageIfAuthoritative(
        "claim_court_" + courtStage.name().toLowerCase(Locale.ROOT) + "_running");
    StructuredCallResult<T> call =
        callStage(
            execution.executionId(),
            claimCourtPromptStage(courtStage),
            responseType,
            context,
            agent,
            "verification",
            "Claim Court " + courtStage.name().toLowerCase(Locale.ROOT));
    T bound = binder.apply(call);
    activeClaimCourtExecutions()
        .recordResult(execution.executionId(), ContractObjectMapper.toTree(bound));
    persistClaimCourtStageIfAuthoritative(
        "claim_court_" + courtStage.name().toLowerCase(Locale.ROOT) + "_result");
    failClaimCourtAt(durableFailurePoint(courtStage));
    return new CourtStageResult<>(bound, execution.executionId());
  }

  private void persistClaimCourtStageIfAuthoritative(String reason) {
    ClaimCourtWorkerContext worker = activeClaimCourtWorker.get();
    if (worker == null) {
      persistUnchecked(reason, false);
      return;
    }
    persistClaimCourtOperationalFrontier(reason, worker.executions().snapshot());
  }

  private synchronized void persistClaimCourtOperationalFrontier(
      String reason, ClaimCourtStageExecutionSnapshot executions) {
    mergeClaimCourtExecutionFrontier(executions);
    try {
      persist(reason, false);
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Claim Court operational frontier could not be persisted", exception);
    }
  }

  private static String claimCourtStageInputHash(Map<String, ?> context) {
    LinkedHashMap<String, Object> stableContext = new LinkedHashMap<>(context);
    Object candidate = stableContext.get("candidate_artifact");
    if (candidate instanceof AttemptArtifactRecord artifact) {
      stableContext.put(
          "candidate_artifact",
          Map.ofEntries(
              Map.entry("artifact_id", artifact.artifactId()),
              Map.entry("claim_id", artifact.claimId()),
              Map.entry("content_hash", artifact.contentHash()),
              Map.entry("kind", artifact.kind().name()),
              Map.entry("problem_hash", artifact.problemHash()),
              Map.entry("route_id", artifact.routeId()),
              Map.entry("source_attempt_id", artifact.sourceAttemptId()),
              Map.entry("statement", artifact.statement()),
              Map.entry(
                  "target_obligation_id",
                  Objects.toString(artifact.targetObligationId(), ""))));
    }
    return CanonicalJson.stableHash(stableContext);
  }

  private void projectClaimCourtOutcome(
      RouteState route, AttemptArtifactRecord artifact, ClaimCourtReviewResult result) {
    ClaimCourtRecord courtRecord = result.record();
    if (courtRecord.outcome() == null) {
      throw new IllegalStateException("Claim Court projection requires a terminal outcome");
    }
    failClaimCourtAt(ClaimCourtFailurePoint.AFTER_FINAL_OUTCOME_BEFORE_PROJECTION);
    ClaimCard canonicalClaim = lemmaClaimForArtifact(artifact);
    String canonicalClaimId = canonicalClaim.claimId();
    ClaimCourtOutcome projectionOutcome =
        preservedAuthorityOutcome(canonicalClaim, courtRecord.outcome());
    mergeClaimCourtDecision(route, result, projectionOutcome);
    AttemptArtifactRecord projected =
        attemptArtifacts.applyCourtOutcome(
            artifact.artifactId(),
            projectionOutcome,
            courtRecord.courtCaseId(),
            result.revision().revisionId(),
            String.join("; ", courtRecord.history()));
    lemmaMemory.applyClaimCourtOutcome(
        artifact.claimId(),
        projectionOutcome,
        courtRecord.courtCaseId(),
        result.revision().revisionId(),
        result.confidence());
    registerClaimLifecycle(route, projected);
    proofControl
        .claims()
        .recordProofRevision(canonicalClaimId, result.revision().revisionId());
    if (projectionOutcome == ClaimCourtOutcome.VERIFIED) {
      lemmaMemory.applyVerifiedProofRevision(
          canonicalClaimId,
          result.revision().proofSteps(),
          result.revision().revisionId(),
          result.confidence());
    } else if (projectionOutcome == ClaimCourtOutcome.REFUTED) {
      proofControl
          .claims()
          .recordVerifiedRefutation(
              canonicalClaimId,
              courtRecord.refutationEvidenceIds().getFirst(),
              courtRecord.frozenClaim().claimStatementHash());
    } else {
      proofControl
          .claims()
          .recordProofInvalidOpen(
              canonicalClaimId,
              Objects.toString(courtRecord.proofAuditId(), courtRecord.courtCaseId()),
              courtRecord.outcome().name());
    }
    updateRouteClaimProjection(route, projected, projectionOutcome);
    failClaimCourtAt(ClaimCourtFailurePoint.AFTER_LEMMA_MEMORY_PROJECTION);
    if (projected.status() == AttemptArtifactStatus.VERIFIED_LOCAL
        && projected.kind() != AttemptArtifactKind.ROUTE_THEOREM) {
      integrateVerifiedAttemptArtifacts(route, List.of(projected));
    }
    failClaimCourtAt(ClaimCourtFailurePoint.AFTER_FACT_PROJECTION_BEFORE_PERSIST);
    persistAuthorityProjectionOutsideEpoch("claim_court_projection");
    failClaimCourtAt(ClaimCourtFailurePoint.AFTER_FINAL_CHECKPOINT_PERSIST);
  }

  private void persistAuthorityProjectionOutsideEpoch(String reason) {
    if (activeEpochAuthorityCommit.get() == null) {
      persistUnchecked(reason, false);
    }
  }

  private Optional<ClaimCourtRolePolicy.Assignment> claimCourtAssignment(
      String authorAgentId, String routeId) {
    int rotation = deterministicRouteRotation(routeId);
    LinkedHashSet<String> excluded = new LinkedHashSet<>();
    excluded.add(authorAgentId);
    Optional<AgentRuntime> falsifier =
        selectClaimCourtAgent(
            excluded, List.of("counterexample_hunter", "route_skeptic"), rotation);
    if (falsifier.isEmpty()) {
      return Optional.empty();
    }
    excluded.add(falsifier.get().id());
    Optional<AgentRuntime> auditor =
        selectClaimCourtAgent(
            excluded, List.of("detailed_verifier", "route_referee"), rotation);
    if (auditor.isEmpty()) {
      return Optional.empty();
    }
    excluded.add(auditor.get().id());
    Optional<AgentRuntime> repairer =
        selectClaimCourtAgent(
            excluded, List.of("route_prover", "explorer", "bridge_prover"), rotation);
    if (repairer.isEmpty()) {
      return Optional.empty();
    }
    excluded.add(repairer.get().id());
    Optional<AgentRuntime> adjudicator =
        selectClaimCourtAgent(
            excluded, List.of("final_verifier", "detailed_verifier"), rotation);
    if (adjudicator.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new ClaimCourtRolePolicy.Assignment(
            authorAgentId,
            falsifier.get().id(),
            auditor.get().id(),
            repairer.get().id(),
            adjudicator.get().id()));
  }

  private Optional<AgentRuntime> selectClaimCourtAgent(
      Set<String> excluded, List<String> preferredRoles, int rotation) {
    List<AgentRuntime> preferred =
        pool.agents().stream()
            .filter(agent -> !excluded.contains(agent.id()))
            .filter(agent -> preferredRoles.stream().anyMatch(agent::supportsRole))
            .toList();
    if (!preferred.isEmpty()) {
      return Optional.of(preferred.get(Math.floorMod(rotation, preferred.size())));
    }
    List<AgentRuntime> fallback =
        pool.agents().stream().filter(agent -> !excluded.contains(agent.id())).toList();
    return fallback.isEmpty()
        ? Optional.empty()
        : Optional.of(fallback.get(Math.floorMod(rotation, fallback.size())));
  }

  private static int deterministicRouteRotation(String routeId) {
    int separator = routeId.lastIndexOf('-');
    if (separator >= 0 && separator + 1 < routeId.length()) {
      try {
        return Math.max(0, Integer.parseInt(routeId.substring(separator + 1)) - 1);
      } catch (NumberFormatException ignored) {
        // Fall through to the stable hash for non-numeric route identifiers.
      }
    }
    return routeId.hashCode() & Integer.MAX_VALUE;
  }

  private ClaimStatementFalsificationBatch bindStatementFalsification(
      RouteState route,
      StructuredCallResult<ClaimStatementFalsificationBatch> call,
      String agentId) {
    ClaimStatementFalsificationBatch source = call.value();
    return new ClaimStatementFalsificationBatch(
        source.batchId(),
        agentId,
        route.routeId,
        route.attempt.attemptId(),
        source.decisions(),
        call.responseArtifactRef(),
        call.usage());
  }

  private ClaimProofAuditBatch bindProofAudit(
      RouteState route, StructuredCallResult<ClaimProofAuditBatch> call, String agentId) {
    ClaimProofAuditBatch source = call.value();
    return new ClaimProofAuditBatch(
        source.batchId(),
        agentId,
        route.routeId,
        route.attempt.attemptId(),
        source.decisions(),
        call.responseArtifactRef(),
        call.usage());
  }

  private ClaimMinimalRepairBatch bindMinimalRepair(
      RouteState route, StructuredCallResult<ClaimMinimalRepairBatch> call, String agentId) {
    ClaimMinimalRepairBatch source = call.value();
    return new ClaimMinimalRepairBatch(
        source.batchId(),
        agentId,
        route.routeId,
        route.attempt.attemptId(),
        source.decisions(),
        call.responseArtifactRef(),
        call.usage());
  }

  private ClaimBlindAdjudicationBatch bindBlindAdjudication(
      RouteState route,
      StructuredCallResult<ClaimBlindAdjudicationBatch> call,
      String agentId) {
    ClaimBlindAdjudicationBatch source = call.value();
    return new ClaimBlindAdjudicationBatch(
        source.batchId(),
        agentId,
        route.routeId,
        route.attempt.attemptId(),
        source.decisions(),
        call.responseArtifactRef(),
        call.usage());
  }

  private static ClaimCounterexampleWitnessReviewBatch bindWitnessReview(
      StructuredCallResult<ClaimCounterexampleWitnessReviewBatch> call, String agentId) {
    ClaimCounterexampleWitnessReviewBatch source = call.value();
    return new ClaimCounterexampleWitnessReviewBatch(
        source.batchId(),
        agentId,
        source.decisions(),
        call.responseArtifactRef(),
        call.usage());
  }

  private static ClaimStatementFalsificationDecision statementDecision(
      ClaimStatementFalsificationBatch batch, String claimId) {
    List<ClaimStatementFalsificationDecision> matching =
        batch.decisions().stream().filter(decision -> decision.claimId().equals(claimId)).toList();
    return matching.size() == 1
        ? matching.getFirst()
        : new ClaimStatementFalsificationDecision(
            claimId,
            StatementFalsificationDisposition.INCONCLUSIVE,
            List.of(),
            "exactly one statement decision was required");
  }

  private static ClaimProofAuditDecision proofAuditDecision(
      ClaimProofAuditBatch batch, String claimId) {
    List<ClaimProofAuditDecision> matching =
        batch.decisions().stream().filter(decision -> decision.claimId().equals(claimId)).toList();
    return matching.size() == 1
        ? matching.getFirst()
        : new ClaimProofAuditDecision(
            claimId,
            ClaimProofAuditVerdict.INCONCLUSIVE,
            List.of(),
            "exactly one proof audit decision was required");
  }

  private static ClaimMinimalRepairDecision minimalRepairDecision(
      ClaimMinimalRepairBatch batch, String claimId) {
    List<ClaimMinimalRepairDecision> matching =
        batch.decisions().stream().filter(decision -> decision.claimId().equals(claimId)).toList();
    return matching.size() == 1
        ? matching.getFirst()
        : new ClaimMinimalRepairDecision(
            claimId,
            ClaimMinimalRepairDisposition.REPAIR_DECLINED,
            null,
            "exactly one repair decision was required");
  }

  private static ClaimBlindAdjudicationDecision blindDecision(
      ClaimBlindAdjudicationBatch batch, String claimId) {
    List<ClaimBlindAdjudicationDecision> matching =
        batch.decisions().stream().filter(decision -> decision.claimId().equals(claimId)).toList();
    return matching.size() == 1
        ? matching.getFirst()
        : new ClaimBlindAdjudicationDecision(
            claimId,
            ClaimBlindAdjudicationVerdict.INCONCLUSIVE,
            List.of(),
            "exactly one blind adjudication decision was required");
  }

  private ClaimProofAuditDecision deterministicProofAuditBoundary(
      ClaimProofAuditDecision source, ClaimProofRevisionRecord revision) {
    Set<String> stepIds =
        revision.proofSteps().stream()
            .map(ProofStep::stepId)
            .collect(java.util.stream.Collectors.toSet());
    Set<String> verified = verifiedClaimIdSet();
    boolean malformed =
        source.issues().stream()
            .anyMatch(
                issue ->
                    !stepIds.contains(issue.stepId())
                        || issue.touchesClaimStatement()
                        || issue.issueKind()
                            == io.github.aililuola.mathproofmesh.contract.ProofIssueKind
                                .STATEMENT_REFORMULATION_REQUIRED
                        || (source.verdict() == ClaimProofAuditVerdict.INVALID_REPAIRABLE
                            && issue.repairability() != ProofRepairability.LOCAL_PATCH
                            && issue.repairability()
                                != ProofRepairability.VERIFIED_DEPENDENCY_PATCH)
                        || !verified.containsAll(issue.requiredVerifiedDependencyIds()));
    if (!malformed) {
      return source;
    }
    return new ClaimProofAuditDecision(
        source.claimId(),
        ClaimProofAuditVerdict.INVALID_UNREPAIRABLE,
        source.issues(),
        "deterministic audit boundary rejected a nonlocal or unbound repair");
  }

  private ClaimProofAuditDecision proofAuditFor(ClaimCourtRecord record) {
    return activeClaimCourtExecutions().records().stream()
        .filter(execution -> execution.courtCaseId().equals(record.courtCaseId()))
        .filter(execution -> execution.stage() == ClaimCourtStage.PROOF_AUDIT)
        .filter(execution -> execution.resultPayload() != null)
        .map(
            execution ->
                ContractObjectMapper.read(execution.resultPayload(), ClaimProofAuditBatch.class))
        .map(batch -> proofAuditDecision(batch, record.frozenClaim().claimId()))
        .map(
            decision ->
                deterministicProofAuditBoundary(
                    decision,
                    activeClaimProofRevisions().get(record.currentProofRevisionId())))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("repair requires a durable proof audit"));
  }

  private ClaimCourtLedger activeClaimCourt() {
    ClaimCourtWorkerContext worker = activeClaimCourtWorker.get();
    return worker == null ? claimCourt : worker.court();
  }

  private ClaimProofRevisionLedger activeClaimProofRevisions() {
    ClaimCourtWorkerContext worker = activeClaimCourtWorker.get();
    return worker == null ? claimProofRevisions : worker.revisions();
  }

  private ClaimCourtStageExecutionLedger activeClaimCourtExecutions() {
    ClaimCourtWorkerContext worker = activeClaimCourtWorker.get();
    return worker == null ? claimCourtExecutions : worker.executions();
  }

  private NegativeKnowledgeRegistry activeNegativeKnowledgeRegistry() {
    ClaimCourtWorkerContext worker = activeClaimCourtWorker.get();
    return worker == null ? negativeKnowledgeRegistry : worker.negativeKnowledge();
  }

  private static boolean exactCounterexampleCandidate(
      StatementCounterexampleCandidate candidate, FrozenClaimSnapshot frozen) {
    return candidate.claimId().equals(frozen.claimId())
        && candidate.statementHash().equals(frozen.claimStatementHash())
        && candidate.assumptions().equals(frozen.assumptions())
        && candidate.quantifiers().equals(frozen.quantifiers())
        && candidate.scopeLimitations().equals(frozen.scopeLimitations())
        && candidate.polarity().equals(frozen.polarity());
  }

  private Set<String> verifiedClaimIdSet() {
    LinkedHashSet<String> verified =
        lemmaMemory.verified().stream()
            .map(ClaimCard::claimId)
            .collect(
                java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    typedMemory.facts().stream().map(MessageEnvelope::messageId).forEach(verified::add);
    return Set.copyOf(verified);
  }

  private static String claimCourtPromptStage(ClaimCourtStage stage) {
    return switch (stage) {
      case STATEMENT_FALSIFICATION -> "claim_statement_falsification";
      case COUNTEREXAMPLE_WITNESS_REVIEW -> "claim_counterexample_witness_review";
      case PROOF_AUDIT -> "claim_proof_audit";
      case MINIMAL_REPAIR -> "claim_minimal_repair";
      case BLIND_ADJUDICATION -> "claim_blind_adjudication";
    };
  }

  private static ClaimCourtFailurePoint durableFailurePoint(ClaimCourtStage stage) {
    return switch (stage) {
      case STATEMENT_FALSIFICATION -> ClaimCourtFailurePoint.AFTER_STATEMENT_RESULT_DURABLE;
      case PROOF_AUDIT -> ClaimCourtFailurePoint.AFTER_PROOF_AUDIT_RESULT;
      case BLIND_ADJUDICATION -> ClaimCourtFailurePoint.AFTER_BLIND_RESULT_DURABLE;
      case COUNTEREXAMPLE_WITNESS_REVIEW, MINIMAL_REPAIR -> ClaimCourtFailurePoint.NONE;
    };
  }

  private void registerClaimLifecycle(RouteState route, AttemptArtifactRecord artifact) {
    ClaimCard claim = claimForArtifact(artifact);
    ArtifactDependencyResolution dependency = resolveArtifactDependencies(route, claim);
    proofControl
        .claims()
        .register(
            claim.claimId(),
            artifact.sourceAttemptId(),
            artifact.sourceDeltaId(),
            dependency.migration().refs(),
            artifact.kind(),
            artifact.sourceAttemptStatus(),
            artifact.sourceRouteStatus());
  }

  private static ClaimCourtOutcome preservedAuthorityOutcome(
      ClaimCard claim, ClaimCourtOutcome proposed) {
    if (claim.status() == ClaimStatus.VERIFIED && proposed != ClaimCourtOutcome.VERIFIED) {
      return ClaimCourtOutcome.VERIFIED;
    }
    if (claim.status() == ClaimStatus.REJECTED && proposed != ClaimCourtOutcome.REFUTED) {
      return ClaimCourtOutcome.REFUTED;
    }
    return proposed;
  }

  private void mergeClaimCourtDecision(
      RouteState route, ClaimCourtReviewResult result, ClaimCourtOutcome outcome) {
    LinkedHashMap<String, ClaimReviewDecision> decisions = new LinkedHashMap<>();
    if (route.claimReview != null) {
      route.claimReview.decisions().forEach(decision -> decisions.put(decision.claimId(), decision));
    }
    VerificationVerdict verdict =
        switch (outcome) {
          case VERIFIED -> VerificationVerdict.PASS;
          case REFUTED -> VerificationVerdict.FAIL;
          default -> VerificationVerdict.UNCERTAIN;
        };
    boolean authoritative = outcome == ClaimCourtOutcome.VERIFIED;
    decisions.put(
        result.record().frozenClaim().claimId(),
        new ClaimReviewDecision(
            result.record().frozenClaim().claimId(),
            verdict,
            result.confidence(),
            result.revision().dependencyClaimIds(),
            true,
            authoritative,
            authoritative,
            authoritative,
            outcome == ClaimCourtOutcome.VERIFIED || outcome == ClaimCourtOutcome.REFUTED,
            List.of(),
            "server-authoritative Claim Court outcome: " + outcome.name()));
    List<ClaimReviewDecision> bounded =
        decisions.values().stream().limit(ClaimReviewBatch.MAX_DECISIONS).toList();
    route.claimReview =
        new ClaimReviewBatch(
            "claim-court-report-"
                + CanonicalJson.stableHash(
                        List.of(route.attempt.attemptId(), bounded.stream().map(ClaimReviewDecision::claimId).toList()))
                    .substring(0, 24),
            result.authorityAgentId(),
            route.routeId,
            route.attempt.attemptId(),
            bounded,
            "claim-court://" + result.record().courtCaseId(),
            new UsageRecord());
  }

  private void updateRouteClaimProjection(
      RouteState route, AttemptArtifactRecord artifact, ClaimCourtOutcome outcome) {
    String claimId = artifact.claimId();
    switch (outcome) {
      case VERIFIED -> {
        route.rejectedClaimIds.remove(claimId);
        route.uncertainClaimIds.remove(claimId);
        route.proofInvalidOpenClaimIds.remove(claimId);
        route.repairExhaustedClaimIds.remove(claimId);
        if (artifact.kind() == AttemptArtifactKind.COUNTEREXAMPLE) {
          addDistinct(route.salvagedCounterexampleIds, claimId);
        } else if (artifact.kind() == AttemptArtifactKind.LOCAL_LEMMA) {
          addDistinct(route.salvagedVerifiedClaimIds, claimId);
        }
      }
      case REFUTED -> {
        addDistinct(route.rejectedClaimIds, claimId);
        route.uncertainClaimIds.remove(claimId);
      }
      case PROOF_INVALID_BUT_CLAIM_OPEN -> {
        addDistinct(route.uncertainClaimIds, claimId);
        route.proofInvalidOpenClaimIds.add(claimId);
      }
      case REPAIR_EXHAUSTED -> {
        addDistinct(route.uncertainClaimIds, claimId);
        route.proofInvalidOpenClaimIds.add(claimId);
        route.repairExhaustedClaimIds.add(claimId);
      }
      case INCONCLUSIVE, DEFERRED_INDEPENDENCE_UNAVAILABLE ->
          addDistinct(route.uncertainClaimIds, claimId);
    }
  }

  private static ClaimCourtReviewResult claimCourtResult(
      ClaimCourtRecord record, ClaimProofRevisionRecord revision, String authorityAgentId) {
    double confidence = record.outcome() == ClaimCourtOutcome.VERIFIED ? 1.0d : 0.0d;
    return new ClaimCourtReviewResult(record, revision, authorityAgentId, confidence);
  }

  private static String finalAuthorityAgent(ClaimCourtRecord record) {
    if (record.roleAssignment() == null) {
      return "claim-court";
    }
    return switch (record.outcome()) {
      case VERIFIED, PROOF_INVALID_BUT_CLAIM_OPEN, REPAIR_EXHAUSTED, INCONCLUSIVE ->
          record.roleAssignment().blindAdjudicatorAgentId();
      case REFUTED -> record.roleAssignment().auditorAgentId();
      case DEFERRED_INDEPENDENCE_UNAVAILABLE -> "claim-court";
    };
  }

  private ClaimCourtMutationSnapshot captureClaimCourtMutation(RouteState route) {
    return new ClaimCourtMutationSnapshot(
        claimCourt.snapshot(),
        claimProofRevisions.snapshot(),
        claimCourtExecutions.snapshot(),
        attemptArtifacts.snapshot(),
        lemmaMemory.snapshot(),
        proofControl.claims().snapshot(),
        typedMemory.snapshot(),
        proofGraph.snapshot(),
        checkpoints.snapshot(),
        List.copyOf(pendingProofTasks),
        new RouteClaimProjectionSnapshot(
            route.routeId,
            route.claimIds,
            route.salvagedVerifiedClaimIds,
            route.salvagedCounterexampleIds,
            route.rejectedClaimIds,
            route.uncertainClaimIds,
            route.courtCaseIds,
            route.proofInvalidOpenClaimIds,
            route.repairExhaustedClaimIds,
            route.claimReview));
  }

  private void restoreClaimCourtMutation(ClaimCourtMutationSnapshot snapshot) {
    claimCourt.restore(snapshot.court());
    claimProofRevisions.restore(snapshot.revisions());
    claimCourtExecutions.restore(snapshot.executions());
    attemptArtifacts = AttemptArtifactLedger.restore(snapshot.attemptArtifacts());
    lemmaMemory = LemmaMemory.restore(snapshot.lemmaMemory());
    proofControl.claims().load(snapshot.claimLifecycle());
    typedMemory = TypedMemory.restore(snapshot.typedMemory(), memoryPolicy());
    proofGraph = ProofGraphStore.restore(snapshot.proofGraph(), ProofGraphPolicy.defaults());
    checkpoints.restore(snapshot.checkpoints());
    pendingProofTasks.clear();
    pendingProofTasks.addAll(snapshot.pendingProofTasks());
    installNegativeKnowledgeRuntime();
    RouteState route =
        routes.stream()
            .filter(candidate -> candidate.routeId.equals(snapshot.route().routeId()))
            .findFirst()
            .orElse(null);
    if (route != null) {
      snapshot.route().restore(route);
    }
  }

  void setClaimCourtFailurePointForTest(ClaimCourtFailurePoint point) {
    claimCourtFailurePoint = Objects.requireNonNull(point, "point");
  }

  void setClaimCourtHardCrashPointForTest(ClaimCourtFailurePoint point) {
    claimCourtHardCrashPoint = Objects.requireNonNull(point, "point");
  }

  private void failClaimCourtAt(ClaimCourtFailurePoint point) {
    if (point == ClaimCourtFailurePoint.NONE) {
      return;
    }
    if (claimCourtHardCrashPoint == point) {
      claimCourtHardCrashPoint = ClaimCourtFailurePoint.NONE;
      throw new SimulatedClaimCourtProcessTermination(point);
    }
    if (claimCourtFailurePoint == point) {
      claimCourtFailurePoint = ClaimCourtFailurePoint.NONE;
      throw new IllegalStateException("simulated Claim Court failure at " + point);
    }
  }

  private void integrateVerifiedAttemptArtifacts(
      RouteState route, List<AttemptArtifactRecord> reviewed) {
    reviewed.stream()
        .filter(record -> record.status() == AttemptArtifactStatus.VERIFIED_LOCAL)
        .filter(record -> record.kind() != AttemptArtifactKind.ROUTE_THEOREM)
        .forEach(
            record -> {
              if (record.kind() == AttemptArtifactKind.COUNTEREXAMPLE) {
                integrateVerifiedCounterexample(route, record);
              } else {
                integrateVerifiedLocalClaim(route, record);
              }
            });
  }

  private void integrateVerifiedLocalClaim(RouteState route, AttemptArtifactRecord artifact) {
    MessageEnvelope admitted = admitArtifactFact(route, artifact, false);
    if (admitted == null) {
      return;
    }
    attemptArtifacts.markPromoted(artifact.artifactId(), admitted.messageId());
    addDistinct(route.claimIds, admitted.messageId());
    event(
        "local_claim_salvaged",
        "claim_memory_graph",
        reviewerId(route),
        "verified",
        "Independently verified local claim survived its route verdict",
        admitted.messageId());
  }

  private void integrateVerifiedCounterexample(
      RouteState route, AttemptArtifactRecord artifact) {
    if (artifact.targetObligationId() == null
        || proofGraph.obligations().stream()
            .noneMatch(
                obligation ->
                    obligation.obligationId().equals(artifact.targetObligationId()))) {
      throw new IllegalStateException("verified counterexample lost its exact target obligation");
    }
    MessageEnvelope admitted = admitArtifactFact(route, artifact, true);
    if (admitted == null) {
      return;
    }
    proofGraph.refuteObligation(artifact.targetObligationId(), admitted.messageId());
    attemptArtifacts.markCounterexampleApplied(artifact.artifactId(), admitted.messageId());
    addDistinct(route.claimIds, admitted.messageId());
    event(
        "counterexample_salvaged",
        "claim_memory_graph",
        reviewerId(route),
        "verified",
        "Independently checked counterexample refuted only its exact target obligation",
        admitted.messageId());
  }

  private void integrateRouteTheorem(
      RouteState route, List<AttemptArtifactRecord> reviewed) {
    reviewed.stream()
        .filter(record -> record.kind() == AttemptArtifactKind.ROUTE_THEOREM)
        .filter(record -> record.status() == AttemptArtifactStatus.VERIFIED_LOCAL)
        .findFirst()
        .ifPresent(
            artifact -> {
              lemmaMemory.registerCommittedStepIds(
                  route.attempt.proofSteps().stream().map(ProofStep::stepId).toList());
              MessageEnvelope admitted = admitArtifactFact(route, artifact, false);
              if (admitted == null) {
                return;
              }
              attemptArtifacts.markPromoted(artifact.artifactId(), admitted.messageId());
              addDistinct(route.claimIds, admitted.messageId());
              closeRouteTheoremObligations(route, admitted, claimDecision(route, artifact).confidence());
              event(
                  "route_theorem_promoted",
                  "claim_memory_graph",
                  reviewerId(route),
                  "verified",
                  "Complete verified route theorem passed claim-scoped review",
                  admitted.messageId());
            });
  }

  private MessageEnvelope admitArtifactFact(
      RouteState route, AttemptArtifactRecord artifact, boolean counterexample) {
    ClaimCard claim = claimForArtifact(artifact);
    ClaimReviewDecision decision = claimDecision(route, artifact);
    ArtifactDependencyResolution dependency = resolveArtifactDependencies(route, claim);
    proofControl
        .claims()
        .register(
            claim.claimId(),
            artifact.sourceAttemptId(),
            artifact.sourceDeltaId(),
            dependency.migration().refs(),
            artifact.kind(),
            artifact.sourceAttemptStatus(),
            artifact.sourceRouteStatus());
    proofControl
        .claims()
        .recordLocalVerification(claim.claimId(), "claim-proof://" + artifact.artifactId());
    proofControl
        .claims()
        .recordIndependentVerification(
            claim.claimId(), reviewerId(route), artifact.authorAgentId(), decisionReportId(route));
    proofControl
        .claims()
        .recordRefereeAcceptance(
            claim.claimId(),
            reviewerId(route),
            artifact.authorAgentId(),
            decisionReportId(route),
            dependency.resolution().resolved(),
            decision.scopeValid(),
            decision.quantifiersValid(),
            decision.evidenceTypeValid());
    var promotion =
        proofControl
            .claims()
            .proposePromotion(
                claim.claimId(), dependency.resolution(), true, List.of());
    if (!promotion.eligible()) {
      event(
          "claim_kept_local",
          "claim_memory_graph",
          reviewerId(route),
          "warning",
          "Claim retained without Fact authority: " + String.join("; ", promotion.reasons()),
          claim.claimId());
      return null;
    }

    VerifiedCourtFactProjection courtProjection = verifiedCourtFactProjection(artifact);
    boolean completeCourtContext =
        courtProjection != null
            && !courtProjection
                .frozen()
                .scopeLimitations()
                .contains(LEGACY_INCOMPLETE_SEMANTIC_CONTEXT);
    MessageEnvelope candidate =
        completeCourtContext
            ? factMessage(
                courtProjection.frozen(),
                courtProjection.revision(),
                claim,
                route,
                artifact,
                decision,
                counterexample)
            : legacyFactMessage(claim, route, artifact, decision, counterexample);
    MessageEnvelope admitted =
        typedMemory
            .find(claim.claimId())
            .filter(existing -> existing.memoryTier() == MemoryTier.FACT)
            .map(existing -> requireSameClaim(existing, candidate))
            .orElseGet(
                () ->
                    typedMemory.facts().stream()
                        .filter(existing -> existing.contentHash().equals(candidate.contentHash()))
                        .findFirst()
                        .orElseGet(
                            () ->
                                typedMemory.addFact(
                                    candidate, reviewerId(route), roundIndex.get())));
    proofControl.claims().observeExternalAdmission(claim.claimId(), admitted.messageId());
    proofGraph.addClaimNode(admitted);
    return admitted;
  }

  private static MessageEnvelope requireSameClaim(
      MessageEnvelope existing, MessageEnvelope candidate) {
    if (!existing.normalizedStatement().equals(candidate.normalizedStatement())
        || !existing.assumptions().equals(candidate.assumptions())
        || !existing.conclusion().equals(candidate.conclusion())
        || !existing.quantifiers().equals(candidate.quantifiers())
        || !existing.variableBindings().equals(candidate.variableBindings())
        || !existing.scopeLimitations().equals(candidate.scopeLimitations())
        || !Objects.equals(existing.claimStatementHash(), candidate.claimStatementHash())
        || !Objects.equals(existing.claimSemanticHash(), candidate.claimSemanticHash())
        || !Objects.equals(existing.polarity(), candidate.polarity())) {
      throw new IllegalArgumentException(
          "claim Fact ID collision for non-equivalent statements: " + candidate.messageId());
    }
    return existing;
  }

  private VerifiedCourtFactProjection verifiedCourtFactProjection(
      AttemptArtifactRecord artifact) {
    for (String reviewRef : artifact.reviewIds()) {
      int separator = reviewRef.lastIndexOf(':');
      if (separator <= 0 || separator + 1 >= reviewRef.length()) {
        continue;
      }
      String courtCaseId = reviewRef.substring(0, separator);
      String revisionId = reviewRef.substring(separator + 1);
      ClaimCourtRecord courtRecord;
      ClaimProofRevisionRecord revision;
      try {
        courtRecord = claimCourt.get(courtCaseId);
        revision = claimProofRevisions.get(revisionId);
      } catch (IllegalArgumentException ignored) {
        continue;
      }
      if (courtRecord.outcome() == ClaimCourtOutcome.VERIFIED
          && courtRecord.currentProofRevisionId().equals(revision.revisionId())
          && revision.status() == ClaimProofRevisionStatus.BLIND_VERIFIED
          && revision.claimSemanticHash().equals(courtRecord.frozenClaim().claimSemanticHash())) {
        return new VerifiedCourtFactProjection(courtRecord.frozenClaim(), revision);
      }
    }
    return null;
  }

  private ArtifactDependencyResolution resolveArtifactDependencies(
      RouteState route, ClaimCard claim) {
    Set<String> localSteps =
        claim.proofSteps().stream()
            .map(ProofStep::stepId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    Set<String> localClaims =
        lemmaMemory.verified().stream()
            .map(ClaimCard::claimId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    localClaims.add(claim.claimId());
    Set<String> brokerFacts =
        typedMemory.facts().stream()
            .map(MessageEnvelope::messageId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    Set<String> obligationIds =
        proofGraph.obligations().stream()
            .map(ProofObligation::obligationId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    DependencyResolver resolver = new DependencyResolver();
    DependencyResolver.MigrationResult migration =
        resolver.migrateLegacy(
            claim.dependencies(),
            route.attempt.attemptId(),
            route.deltaId,
            route.routeId,
            localSteps,
            localClaims,
            brokerFacts);
    DependencyResolver.Resolution resolution =
        resolver.resolve(
            migration.refs(),
            new DependencyResolver.ResolutionContext(
                route.attempt.attemptId(),
                route.deltaId,
                localSteps,
                localClaims,
                brokerFacts,
                brokerFacts,
                obligationIds,
                computationCertificateIds(route.routeId),
                Set.of(),
                Set.of()));
    return new ArtifactDependencyResolution(migration, resolution);
  }

  private void closeRouteTheoremObligations(
      RouteState route, MessageEnvelope admitted, double confidence) {
    List<ProofObligation> closable =
        proofGraph.obligations().stream()
            .filter(
                obligation ->
                    MAIN_GOAL_ID.equals(obligation.obligationId())
                        || obligation.routeIds().contains(route.routeId))
            .filter(obligation -> obligation.kind() != ObligationKind.COMPUTATION_QUESTION)
            .filter(obligation -> "open".equals(obligation.status()))
            .toList();
    for (ProofObligation obligation : closable) {
      proofGraph.closeObligation(obligation.obligationId(), admitted.messageId(), confidence);
    }
  }

  private AgentRuntime claimReviewer(RouteState route) {
    if (route.plan.referee() != null
        && route.plan.referee().assigned()
        && !route.plan.referee().agentId().equals(route.attempt.agentId())) {
      return requireAgent(route.plan.referee().agentId());
    }
    return selectIndependentAgent(Set.of(route.attempt.agentId()), "detailed_verifier");
  }

  private ClaimCard proofClaimForArtifact(AttemptArtifactRecord artifact) {
    ClaimCard sourceClaim =
        routes.stream()
            .filter(route -> route.routeId.equals(artifact.routeId()))
            .map(route -> route.attempt)
            .filter(Objects::nonNull)
            .filter(attempt -> attempt.attemptId().equals(artifact.sourceAttemptId()))
            .flatMap(attempt -> attempt.proposedLemmas().stream())
            .filter(claim -> claim.claimId().equals(artifact.claimId()))
            .filter(claim -> claim.contentHash().equals(artifact.contentHash()))
            .findFirst()
            .orElse(null);
    if (sourceClaim != null) {
      RouteState sourceRoute =
          routes.stream()
              .filter(route -> route.routeId.equals(artifact.routeId()))
              .findFirst()
              .orElseThrow();
      return bindClaim(sourceClaim, sourceRoute);
    }
    return claimForArtifact(artifact);
  }

  private ClaimCard claimForArtifact(AttemptArtifactRecord artifact) {
    String claimId = lemmaMemory.resolveClaimId(artifact.claimId());
    return lemmaMemory.claims().stream()
        .filter(claim -> claim.claimId().equals(claimId))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "attempt artifact has no lemma-memory projection: " + artifact.artifactId()));
  }

  private ClaimCard lemmaClaimForArtifact(AttemptArtifactRecord artifact) {
    return claimForArtifact(artifact);
  }

  private ClaimReviewDecision claimDecision(
      RouteState route, AttemptArtifactRecord artifact) {
    if (route.claimReview == null) {
      throw new IllegalStateException("claim-scoped review is required before promotion");
    }
    String canonicalClaimId = lemmaMemory.resolveClaimId(artifact.claimId());
    return route.claimReview.decisions().stream()
        .filter(
            decision ->
                decision.claimId().equals(artifact.claimId())
                    || decision.claimId().equals(canonicalClaimId))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "claim-scoped decision is missing for " + artifact.claimId()));
  }

  private static ClaimReviewDecision uncertainDecision(String claimId, String feedback) {
    return new ClaimReviewDecision(
        claimId,
        VerificationVerdict.UNCERTAIN,
        0.0d,
        List.of(),
        false,
        false,
        false,
        false,
        false,
        List.of(),
        feedback);
  }

  private static void addDistinct(List<String> values, String value) {
    if (value != null && !value.isBlank() && !values.contains(value)) {
      values.add(value);
    }
  }

  private String reviewerId(RouteState route) {
    return route.claimReview == null
        ? claimReviewer(route).id()
        : route.claimReview.agentId();
  }

  private String decisionReportId(RouteState route) {
    if (route.claimReview == null) {
      throw new IllegalStateException("claim review report is unavailable");
    }
    return route.claimReview.reportId();
  }

  private record ArtifactDependencyResolution(
      DependencyResolver.MigrationResult migration,
      DependencyResolver.Resolution resolution) {}

  private Set<String> computationCertificateIds(String routeId) {
    return computationTraces.stream()
        .filter(trace -> trace.routeId().equals(routeId))
        .filter(ComputationTrace::replayValid)
        .filter(
            trace ->
                trace.authority() == ComputationEvidenceGate.EvidenceAuthority.VERIFIED
                    || trace.authority()
                        == ComputationEvidenceGate.EvidenceAuthority.VERIFIED_BOUNDED)
        .map(ComputationTrace::result)
        .filter(Objects::nonNull)
        .map(ExperimentResult::experimentId)
        .collect(java.util.stream.Collectors.toSet());
  }

  private void recordInspirationOutcome(RouteState route) {
    String proposalId = route.strategy.inspirationProposalId();
    if (proposalId == null || !inspirationLedger.snapshot().containsKey(proposalId)) {
      return;
    }
    inspirationLedger.recordUsage(proposalId, "route", Math.max(1, route.segmentCount), 0);
    if ("verified".equals(route.status)) {
      List<String> closed =
          proofGraph.obligations().stream()
              .filter(obligation -> obligation.routeIds().contains(route.routeId))
              .filter(obligation -> "closed".equals(obligation.status()))
              .map(ProofObligation::obligationId)
              .toList();
      inspirationLedger.recordVerifiedGain(
          proposalId, roundIndex.get(), proofGraph.canonicalProofDebt(route.routeId), closed);
    } else {
      InspirationOutcome current = inspirationLedger.snapshot().get(proposalId);
      inspirationLedger.recordMaterialization(
          proposalId,
          current.materializationAction() == null
              ? "rejected"
              : current.materializationAction(),
          true);
    }
    inspirationOutcomes.clear();
    inspirationOutcomes.addAll(inspirationLedger.snapshot().values());
  }

  private static String routeObligationId(String routeId) {
    return "obligation-" + routeId;
  }

  private ClaimCard bindClaim(ClaimCard source, RouteState route) {
    return new ClaimCard(
        source.assumptions(),
        source.claimId(),
        source.conclusion(),
        null,
        source.counterexampleRisk(),
        source.dependencies(),
        source.dependencyRefs(),
        source.evidenceRefs(),
        source.proofSteps(),
        source.scopeLimitations(),
        source.selfConfidence(),
        route.author.id(),
        route.attempt.attemptId(),
        route.deltaId,
        source.statement(),
        ClaimStatus.PROPOSED,
        source.tags(),
        null);
  }

  private ClaimCard routeTheoremClaim(RouteState route) {
    String statement = route.attempt.finalAnswer();
    if (statement == null || statement.isBlank()) {
      statement =
          route.attempt.proofSteps().isEmpty()
              ? route.attempt.proofSketch()
              : route.attempt.proofSteps().getLast().statement();
    }
    if (statement == null || statement.isBlank()) {
      statement = "The submitted route proves the frozen main goal.";
    }
    return new ClaimCard(
        List.of(),
        "claim-" + route.routeId + "-theorem-r" + roundIndex.get(),
        statement,
        null,
        "independently reviewed",
        List.of(),
        List.of(),
        List.of(),
        route.attempt.proofSteps(),
        List.of(),
        route.attempt.selfConfidence(),
        route.author.id(),
        route.attempt.attemptId(),
        route.deltaId,
        statement,
        ClaimStatus.PROPOSED,
        List.of("route_theorem", route.strategy.strategyId()),
        null);
  }

  private MessageEnvelope factMessage(
      FrozenClaimSnapshot frozen,
      ClaimProofRevisionRecord verifiedRevision,
      ClaimCard claim,
      RouteState route,
      AttemptArtifactRecord artifact,
      ClaimReviewDecision decision,
      boolean counterexample) {
    List<String> artifactRefs =
        artifact.evidenceRefs().stream()
            .filter(DesktopSolveCoordinator::runScopedArtifactReference)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    verifiedRevision.evidenceRefs().stream()
        .map(EvidenceRef::artifactRef)
        .filter(DesktopSolveCoordinator::runScopedArtifactReference)
        .forEach(reference -> addDistinct(artifactRefs, reference));
    if (route.claimReview != null
        && runScopedArtifactReference(route.claimReview.rawArtifactRef())) {
      addDistinct(artifactRefs, route.claimReview.rawArtifactRef());
    }
    String rawSourceRef =
        artifactRefs.stream()
            .findFirst()
            .orElseGet(
                () ->
                    runScopedArtifactReference(route.attempt.rawArtifactRef())
                        ? route.attempt.rawArtifactRef()
                        : null);
    return new MessageEnvelope(
        artifactRefs,
        frozen.assumptions(),
        frozen.conclusion(),
        "",
        null,
        frozen.dependencyClaimIds(),
        claim.dependencyRefs(),
        counterexample ? EvidenceType.COUNTEREXAMPLE : EvidenceType.NATURAL_PROOF_AUDITED,
        MemoryTier.FACT,
        claim.claimId(),
        counterexample ? MessageType.COUNTEREXAMPLE : MessageType.VERIFIED_LEMMA,
        1.0d,
        topology.mathNormalize(frozen.statement()),
        frozen.problemHash(),
        frozen.quantifiers(),
        rawSourceRef,
        roundIndex.get(),
        config.topology().typedCommunication().schemaVersion(),
        frozen.scopeLimitations(),
        frozen.authorAgentId(),
        counterexample ? RouteRole.SKEPTIC : RouteRole.PROVER,
        route.routeId,
        frozen.statement(),
        List.of("*"),
        config.topology().crossRoute().messageTtlRounds(),
        frozen.variableBindings(),
        decision.confidence(),
        ClaimStatus.VERIFIED,
        frozen.claimStatementHash(),
        frozen.claimSemanticHash(),
        frozen.polarity());
  }

  private static boolean runScopedArtifactReference(String reference) {
    return reference != null && reference.startsWith("artifact://");
  }

  private MessageEnvelope legacyFactMessage(
      ClaimCard claim,
      RouteState route,
      AttemptArtifactRecord artifact,
      ClaimReviewDecision decision,
      boolean counterexample) {
    List<String> artifactRefs = new ArrayList<>(artifact.evidenceRefs());
    if (route.claimReview != null
        && route.claimReview.rawArtifactRef() != null
        && !route.claimReview.rawArtifactRef().isBlank()) {
      addDistinct(artifactRefs, route.claimReview.rawArtifactRef());
    }
    return new MessageEnvelope(
        artifactRefs,
        claim.assumptions(),
        claim.conclusion(),
        "",
        null,
        claim.dependencies(),
        claim.dependencyRefs(),
        counterexample ? EvidenceType.COUNTEREXAMPLE : EvidenceType.NATURAL_PROOF_AUDITED,
        MemoryTier.FACT,
        claim.claimId(),
        counterexample ? MessageType.COUNTEREXAMPLE : MessageType.VERIFIED_LEMMA,
        1.0d,
        topology.mathNormalize(claim.statement()),
        problemHash,
        List.of(),
        route.claimReview == null
            ? route.attempt.rawArtifactRef()
            : route.claimReview.rawArtifactRef(),
        roundIndex.get(),
        "1",
        effectiveClaimScope(claim),
        artifact.authorAgentId(),
        counterexample ? RouteRole.SKEPTIC : RouteRole.PROVER,
        route.routeId,
        claim.statement(),
        List.of("*"),
        config.topology().crossRoute().messageTtlRounds(),
        List.of(),
        decision.confidence(),
        ClaimStatus.VERIFIED);
  }

  private void recordRouteFailure(RouteState route) {
    String reason =
        route.failureReason == null || route.failureReason.isBlank()
            ? reviewFailure(route)
            : route.failureReason;
    String firstError =
        route.detailedReview == null ? null : route.detailedReview.firstErrorStep();
    FailureControlService.Failure failure =
        failureControl.classify(
            route.routeId,
            route.attempt == null ? route.strategy.strategyId() : route.attempt.attemptId(),
            reason,
            firstError,
            reviewEvidence(route));
    route.failure = failure;
    FailureControlService.RewriteRequest rewrite =
        failureControl.rewrite(
            failure,
            verifiedClaimIds(),
            route.attempt == null
                ? List.of()
                : route.attempt.proofSteps().stream().map(ProofStep::stepId).toList(),
            List.of(route.strategy.bottleneck()),
            List.of(route.strategy.bottleneck()),
            List.of(MAIN_GOAL_ID));
    proofControl
        .actions()
        .dispatch(
            proofControlMode(),
            runId,
            route.routeId,
            ProofControlModels.ControlActionType.REWRITE_BLUEPRINT,
            route.strategy.strategyId(),
            Map.of("failure_id", failure.id(), "rewrite_id", rewrite.id()),
            ignored -> "rewrite://" + rewrite.id());
    if (route.attempt != null && !route.attempt.proofSteps().isEmpty()) {
      NearMissLedger.NearMiss nearMiss =
          nearMisses.record(
              new NearMissLedger.Candidate(
                  route.routeId,
                  MAIN_GOAL_ID,
                  route.attempt.attemptId(),
                  route.attempt.proofSketch().isBlank()
                      ? route.strategy.coreIdea()
                      : route.attempt.proofSketch(),
                  route.attempt.proofSteps().getLast().statement(),
                  route.attempt.proofSteps().stream()
                      .map(ProofStep::statement)
                      .limit(Math.max(1, route.attempt.proofSteps().size() - 1L))
                      .toList(),
                  List.of(reason),
                  failure.failureClass().name(),
                  List.of(route.attempt.proofSteps().getFirst().statement()),
                  reviewEvidence(route),
                  route.detailedReview == null ? 0.0d : route.detailedReview.confidence()),
              failure.failureClass() != ProofControlModels.FailureClass.EXECUTION);
      if (nearMiss != null) {
        route.nearMissId = nearMiss.id();
      }
    }
    MessageEnvelope negative = failureMessage(route, failure);
    typedMemory.addNegative(negative, reason);
    if (config.topology().crossRoute().shareFailureRecords()
        && route.plan.referee() != null
        && route.plan.referee().assigned()) {
      event(
          "broker_control_broadcast_rejected",
          "cross_route_broker",
          route.plan.referee().agentId(),
          "rejected",
          "GENERIC_FAILURE_RECORD: retained in local failure audit only",
          negative.messageId());
    }
    event(
        "failure_classified",
        "claim_memory_graph",
        route.author.id(),
        "warning",
        failure.failureClass().name() + ": " + failure.recommendedAction(),
        failure.id());
  }

  private List<String> reviewEvidence(RouteState route) {
    List<String> evidence =
        java.util.stream.Stream.of(
                route.skepticReview,
                route.structuralReview,
                route.detailedReview,
                route.crossProviderReview)
        .filter(Objects::nonNull)
        .map(this::evidenceId)
        .toList();
    if (route.toolAudit == null) {
      return evidence;
    }
    return java.util.stream.Stream.concat(
            evidence.stream(), route.toolAudit.replayArtifactRefs().stream())
        .toList();
  }

  private String evidenceId(VerificationReport report) {
    if (report == null) {
      return "report-unavailable";
    }
    return report.reportId() == null || report.reportId().isBlank()
        ? "report-" + report.targetId() + "-" + report.stage().value()
        : report.reportId();
  }

  private MessageEnvelope failureMessage(
      RouteState route, FailureControlService.Failure failure) {
    List<String> salvagedArtifacts = new ArrayList<>();
    salvagedArtifacts.addAll(route.salvagedVerifiedClaimIds);
    salvagedArtifacts.addAll(route.salvagedCounterexampleIds);
    salvagedArtifacts.addAll(route.rejectedClaimIds);
    salvagedArtifacts.addAll(route.uncertainClaimIds);
    return new MessageEnvelope(
        salvagedArtifacts.stream().distinct().toList(),
        List.of(),
        failure.recommendedAction(),
        "",
        null,
        List.of(),
        List.of(),
        EvidenceType.UNVERIFIED_IDEA,
        MemoryTier.NEGATIVE,
        "failure-message-" + failure.id(),
        MessageType.FAILURE_RECORD,
        1.0d,
        topology.mathNormalize(failure.recommendedAction() + " " + route.failureReason),
        problemHash,
        List.of(),
        null,
        roundIndex.get(),
        "1",
        List.of(),
        route.author.id(),
        RouteRole.PROVER,
        route.routeId,
        "Route failure: " + failure.failureClass().name() + ". " + route.failureReason,
        List.of(),
        config.topology().crossRoute().messageTtlRounds(),
        List.of(),
        failure.confidence(),
        ClaimStatus.REJECTED);
  }

  private void distributeVerifiedClaims() {
    stage(
        RoutePipelineFunctions.RunStage.CROSS_ROUTE_BROKER,
        "Compiling authoritative mathematical state into typed cross-route artifacts");
    if (rootGoal == null) {
      complete(RoutePipelineFunctions.RunStage.CROSS_ROUTE_BROKER);
      return;
    }
    List<RouteMathematicalNeedProfile> profiles = routeMathematicalNeedProfiles();
    for (ClaimCourtRecord record : claimCourt.records()) {
      BrokerArtifactCompilationRequest request = brokerCompilationRequest(record);
      if (request == null) {
        continue;
      }
      var compilation = brokerArtifactCompiler.compile(request);
      if (!compilation.accepted()) {
        event(
            "broker_artifact_rejected",
            "cross_route_broker",
            null,
            "rejected",
            String.join(", ", compilation.rejectionCodes()),
            record.courtCaseId());
        continue;
      }
      BrokerArtifactPublishResult published =
          mathematicalArtifactBroker.publish(
              compilation.artifact(),
              profiles,
              roundIndex.get(),
              config.topology().crossRoute().maxMessagesPerRoutePerRound());
      event(
          "broker_artifact_admitted",
          "cross_route_broker",
          null,
          "completed",
          "Published authoritative "
              + published.artifact().artifactType()
              + " to "
              + published.deliveries().size()
              + " relevant routes",
          published.artifact().artifactId());
    }
    complete(RoutePipelineFunctions.RunStage.CROSS_ROUTE_BROKER);
  }

  private BrokerArtifactCompilationRequest brokerCompilationRequest(ClaimCourtRecord record) {
    FrozenClaimSnapshot frozen = record.frozenClaim();
    ClaimProofRevisionRecord revision;
    try {
      revision = claimProofRevisions.get(record.currentProofRevisionId());
    } catch (IllegalArgumentException ignored) {
      return null;
    }
    BrokerClaimSemanticContext context = brokerClaimContext(frozen);
    List<String> evidenceRefs =
        revision.evidenceRefs().stream().map(EvidenceRef::artifactRef).distinct().toList();
    List<String> sourceSteps = revision.proofSteps().stream().map(ProofStep::stepId).toList();
    int ttl = config.topology().crossRoute().messageTtlRounds();
    if (record.outcome() == ClaimCourtOutcome.VERIFIED) {
      boolean projectedFact =
          typedMemory
              .find(frozen.claimId())
              .filter(message -> message.memoryTier() == MemoryTier.FACT)
              .filter(
                  message ->
                      Objects.equals(message.claimSemanticHash(), frozen.claimSemanticHash()))
              .isPresent();
      boolean authorityValid =
          revision.status() == ClaimProofRevisionStatus.BLIND_VERIFIED
              && record.currentProofRevisionId().equals(revision.revisionId())
              && revision.claimSemanticHash().equals(frozen.claimSemanticHash())
              && claimLifecycleFactAuthority(frozen.claimId());
      return new BrokerArtifactCompilationRequest(
          problemHash,
          rootGoal.sourceStatementHash(),
          BrokerArtifactType.VERIFIED_CLAIM,
          new VerifiedClaimPayload(context),
          BrokerArtifactSourceKind.CLAIM_COURT_VERIFIED,
          frozen.sourceRouteId(),
          frozen.sourceAttemptId(),
          frozen.claimId(),
          revision.revisionId(),
          sourceObligations(frozen.sourceRouteId()),
          sourceSteps,
          evidenceRefs,
          List.of(
              new BrokerReusableConsequence(
                  frozen.statement(),
                  sourceCanonicalTargets(frozen.sourceRouteId()),
                  List.of(frozen.claimSemanticHash()),
                  new ArrayList<>(
                      ProofIdentity.domainObjects(
                          java.util.stream.Stream.concat(
                                  java.util.stream.Stream.of(frozen.statement()),
                                  frozen.assumptions().stream())
                              .toList())))),
          List.of(),
          List.of(frozen.claimId()),
          null,
          roundIndex.get(),
          ttl,
          authorityValid,
          projectedFact);
    }
    if (record.outcome() == ClaimCourtOutcome.REFUTED
        && !record.refutationEvidenceIds().isEmpty()) {
      List<String> affected = exactCounterexampleObligations(frozen.claimId());
      boolean appliedCounterexample =
          attemptArtifacts.records().stream()
              .filter(artifact -> artifact.claimId().equals(frozen.claimId()))
              .filter(artifact -> artifact.status() == AttemptArtifactStatus.APPLIED_COUNTEREXAMPLE)
              .anyMatch(
                  artifact ->
                      artifact.evidenceRefs().stream()
                          .anyMatch(record.refutationEvidenceIds()::contains));
      boolean trustedCounterexampleAuthority = hasVerifiedCounterexampleAuthority(frozen);
      String witness =
          "Verified exact counterexample evidence: "
              + String.join(", ", record.refutationEvidenceIds());
      return new BrokerArtifactCompilationRequest(
          problemHash,
          rootGoal.sourceStatementHash(),
          BrokerArtifactType.VERIFIED_COUNTEREXAMPLE,
          new VerifiedCounterexamplePayload(
              context,
              frozen.claimId(),
              frozen.claimSemanticHash(),
              witness,
              record.refutationEvidenceIds(),
              affected),
          BrokerArtifactSourceKind.VERIFIED_COUNTEREXAMPLE,
          frozen.sourceRouteId(),
          frozen.sourceAttemptId(),
          frozen.claimId(),
          revision.revisionId(),
          affected,
          sourceSteps,
          record.refutationEvidenceIds(),
          List.of(),
          List.of(
              new BrokerBlockedInference(
                  frozen.statement(), List.of(frozen.claimSemanticHash()), affected)),
          List.of(),
          affected.isEmpty() ? null : affected.getFirst(),
          roundIndex.get(),
          ttl,
          appliedCounterexample && trustedCounterexampleAuthority,
          true);
    }
    if (record.outcome() == ClaimCourtOutcome.PROOF_INVALID_BUT_CLAIM_OPEN) {
      ClaimProofAuditDecision audit;
      try {
        audit = proofAuditFor(record);
      } catch (IllegalStateException ignored) {
        return null;
      }
      if (audit.issues().isEmpty()) {
        return null;
      }
      ProofAuditIssue issue = audit.issues().getFirst();
      List<String> sourceObligations = sourceObligations(frozen.sourceRouteId());
      String nextObligation =
          sourceObligations.isEmpty()
              ? "claim-proof-repair:" + frozen.claimId()
              : sourceObligations.getFirst();
      ReviewedObstructionPayload payload =
          new ReviewedObstructionPayload(
              issue.stepId(),
              issue.premiseSummary() + " -> " + issue.conclusionSummary(),
              frozen.dependencyClaimIds(),
              issue.issueKind().name(),
              issue.repairability().name(),
              issue.description(),
              nextObligation,
              List.of(record.proofAuditId()));
      return new BrokerArtifactCompilationRequest(
          problemHash,
          rootGoal.sourceStatementHash(),
          BrokerArtifactType.REVIEWED_OBSTRUCTION,
          payload,
          BrokerArtifactSourceKind.REVIEWED_PROOF_OBSTRUCTION,
          frozen.sourceRouteId(),
          frozen.sourceAttemptId(),
          frozen.claimId(),
          revision.revisionId(),
          sourceObligations,
          List.of(issue.stepId()),
          List.of(record.proofAuditId()),
          List.of(),
          List.of(
              new BrokerBlockedInference(
                  issue.description(),
                  List.of(frozen.claimSemanticHash()),
                  sourceCanonicalTargets(frozen.sourceRouteId()))),
          frozen.dependencyClaimIds(),
          nextObligation,
          roundIndex.get(),
          ttl,
          true,
          true);
    }
    return null;
  }

  private static BrokerClaimSemanticContext brokerClaimContext(FrozenClaimSnapshot frozen) {
    return new BrokerClaimSemanticContext(
        frozen.statement(),
        frozen.conclusion(),
        frozen.assumptions(),
        frozen.quantifiers(),
        frozen.variableBindings(),
        frozen.scopeLimitations(),
        frozen.polarity(),
        frozen.claimStatementHash(),
        frozen.claimSemanticHash(),
        frozen.dependencyClaimIds());
  }

  private boolean claimLifecycleFactAuthority(String claimId) {
    try {
      ClaimLifecycleController.State state = proofControl.claims().get(claimId).state();
      return state == ClaimLifecycleController.State.FACT_CANDIDATE
          || state == ClaimLifecycleController.State.EXTERNALLY_ADMITTED_FACT;
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  private List<String> sourceObligations(String routeId) {
    return proofGraph.obligations().stream()
        .filter(obligation -> obligation.routeIds().contains(routeId))
        .map(ProofObligation::obligationId)
        .sorted()
        .toList();
  }

  private List<String> sourceCanonicalTargets(String routeId) {
    return proofGraph.obligations().stream()
        .filter(obligation -> obligation.routeIds().contains(routeId))
        .map(ProofObligation::obligationId)
        .map(proofGraph::canonicalTargetForObligation)
        .flatMap(Optional::stream)
        .map(record -> record.canonicalTargetId())
        .distinct()
        .sorted()
        .toList();
  }

  private List<String> exactCounterexampleObligations(String claimId) {
    return attemptArtifacts.records().stream()
        .filter(artifact -> artifact.claimId().equals(claimId))
        .filter(artifact -> artifact.status() == AttemptArtifactStatus.APPLIED_COUNTEREXAMPLE)
        .map(AttemptArtifactRecord::targetObligationId)
        .filter(Objects::nonNull)
        .distinct()
        .toList();
  }

  private boolean hasVerifiedCounterexampleAuthority(FrozenClaimSnapshot frozen) {
    NegativeKnowledgeCandidate candidate =
        new NegativeKnowledgeCandidate(
            problemHash,
            NegativeKnowledgeTargetType.CLAIM,
            frozen.statement(),
            frozen.statement(),
            frozen.assumptions(),
            frozen.quantifiers(),
            frozen.variableBindings(),
            frozen.scopeLimitations(),
            frozen.polarity(),
            NegativeKnowledgeSurface.RESTORE_REVALIDATION,
            NegativeCandidateIntent.AUDIT_ONLY);
    String semanticKey = candidate.semanticKey();
    return negativeKnowledgeRegistry.records().stream()
        .filter(record -> record.activeAt(roundIndex.get()))
        .filter(record -> record.problemHash().equals(problemHash))
        .filter(record -> record.targetType() == NegativeKnowledgeTargetType.CLAIM)
        .filter(record -> record.kinds().contains(NegativeKnowledgeKind.VERIFIED_COUNTEREXAMPLE))
        .anyMatch(
            record ->
                record.primarySemanticKey().equals(semanticKey)
                    || record.trustedAliasKeys().contains(semanticKey));
  }

  private List<RouteMathematicalNeedProfile> routeMathematicalNeedProfiles() {
    return routes.stream().map(this::routeMathematicalNeedProfile).toList();
  }

  private RouteMathematicalNeedProfile routeMathematicalNeedProfile(RouteState route) {
    LinkedHashSet<String> activeTargets = new LinkedHashSet<>();
    LinkedHashSet<String> dependencies = new LinkedHashSet<>();
    proofGraph.obligations().stream()
        .filter(obligation -> obligation.routeIds().contains(route.routeId))
        .filter(obligation -> "open".equals(obligation.status()))
        .forEach(
            obligation -> {
              activeTargets.add(obligation.obligationId());
              dependencies.addAll(obligation.dependencyIds());
              proofGraph
                  .canonicalTargetForObligation(obligation.obligationId())
                  .map(record -> record.canonicalTargetId())
                  .ifPresent(activeTargets::add);
            });
    if (route.focusedCanonicalTargetId != null && !route.focusedCanonicalTargetId.isBlank()) {
      activeTargets.add(route.focusedCanonicalTargetId);
    }
    LinkedHashSet<String> required =
        route.strategy.criticalClaims().stream()
            .filter(claim -> "required".equals(claim.necessity()))
            .filter(claim -> !"verified".equals(claim.status()))
            .map(CriticalClaim::claimId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    dependencies.addAll(required);
    route.strategy.criticalClaims().stream()
        .map(CriticalClaim::claimId)
        .forEach(dependencies::add);
    LinkedHashSet<String> families = new LinkedHashSet<>();
    if (route.focusedBottleneckFamilyId != null
        && !route.focusedBottleneckFamilyId.isBlank()) {
      families.add(route.focusedBottleneckFamilyId);
    }
    LinkedHashSet<String> issueKinds = new LinkedHashSet<>();
    for (String claimId : route.proofInvalidOpenClaimIds) {
      claimCourt.records().stream()
          .filter(record -> record.frozenClaim().claimId().equals(claimId))
          .filter(record -> record.outcome() == ClaimCourtOutcome.PROOF_INVALID_BUT_CLAIM_OPEN)
          .findFirst()
          .ifPresent(
              record -> {
                try {
                  proofAuditFor(record).issues().stream()
                      .map(issue -> issue.issueKind().name())
                      .forEach(issueKinds::add);
                } catch (IllegalStateException ignored) {
                  // A missing durable audit cannot establish a mathematical relevance edge.
                }
              });
    }
    String epoch =
        route.activeStrategyEpochId == null || route.activeStrategyEpochId.isBlank()
            ? route.strategy.strategyId()
            : route.activeStrategyEpochId;
    return new RouteMathematicalNeedProfile(
        route.routeId,
        activeTargets,
        required,
        dependencies,
        families,
        route.activeMathematicalObjectIds,
        issueKinds,
        epoch);
  }

  private SchedulerExit runScheduler() {
    int maximumRounds = config.budget().maxRounds();
    while (true) {
      if (CURSOR_SCHEDULER_INSPIRATION.equals(workflowCursor)) {
        if (pendingProofTasks.isEmpty()
            && !verifiedRoutes().isEmpty()
            && synthesisGatePassed()) {
          schedulerStop = null;
          workflowCursor = CURSOR_SYNTHESIS;
          persistUnchecked("scheduler_synthesis_ready", false);
          return SchedulerExit.READY_TO_SYNTHESIZE;
        }
        int activeRound =
            inspirationRoundToRun(roundIndex.get(), maximumRounds, inspirationProgress);
        if (activeRound < 0) {
          if (!verifiedRoutes().isEmpty() && synthesisGatePassed()) {
            workflowCursor = CURSOR_SYNTHESIS;
            persistUnchecked("scheduler_round_limit", false);
            return SchedulerExit.READY_TO_SYNTHESIZE;
          }
          recordSchedulerStop(
              "round_budget_exhausted",
              "The configured scheduler-round budget was exhausted before a dependency-complete proof was verified.");
          persistUnchecked("scheduler_round_limit", false);
          return SchedulerExit.STOPPED;
        }
        roundIndex.set(activeRound);
        runInspiration(inspirationSnapshot(activeRound == 1));
        workflowCursor = CURSOR_SCHEDULER_META;
        persistUnchecked("inspiration", false);
        continue;
      }

      if (CURSOR_SCHEDULER_META.equals(workflowCursor)) {
        stage(
            RoutePipelineFunctions.RunStage.META_REVIEW,
            "Comparing verified progress, conflicts, proof debt, and route utility");
        AgentRuntime metaReviewer = selectMetaReviewer();
        pendingMetaReview =
            callStage(
                    "meta-review-r" + roundIndex.get(),
                    "meta_review",
                    MetaReview.class,
                    Map.of(
                        "immutable_problem", frozenProblem,
                        "problem_hash", problemHash,
                        "round_index", roundIndex.get(),
                        "routes", schedulerRouteState(),
                        "verified_claims", lemmaMemory.verified(),
                        "negative_memory", typedMemory.negatives(),
                        "proof_graph", proofGraph.snapshot(),
                        "broker_utility", brokerUtility(),
                        "decision_rule",
                            "Recommend synthesis only when a dependency-complete route survives independent review."),
                    metaReviewer,
                    "verification",
                    "Performing cross-route meta review")
                .value();
        complete(RoutePipelineFunctions.RunStage.META_REVIEW);
        workflowCursor = CURSOR_SCHEDULER_DECISION;
        persistUnchecked("meta_review", false);
        continue;
      }

      if (CURSOR_SCHEDULER_DECISION.equals(workflowCursor)) {
        if (pendingMetaReview == null) {
          workflowCursor = CURSOR_SCHEDULER_META;
          continue;
        }
        stage(
            RoutePipelineFunctions.RunStage.SCHEDULER_DECISION,
            "Selecting VERIFY, WIDEN, DEEPEN, REVISE, SYNTHESIZE, or STOP");
        bindMetaTarget(pendingMetaReview);
        if (!verifiedRoutes().isEmpty()) {
          event(
              "scheduler_action",
              "scheduler_decision",
              null,
              "completed",
              "VERIFY completed through independent route-team, replay, escalation, and checkpoint gates",
              "scheduler://round-" + roundIndex.get() + "/verify");
        }
        boolean scheduled = budgetHost.schedulePendingProofTasksBatch();
        if (!scheduled) {
          enqueueDebtRepairTaskIfStalled();
          scheduled = budgetHost.schedulePendingProofTasksBatch();
        }
        EvidenceAwareBudgetDecision decision = null;
        if (!scheduled) {
          BudgetStateSnapshot budgetState = schedulerBudgetState();
          decision = adaptiveBudget.decide(budgetState);
          scheduled = budgetScheduler.apply(decision);
        }
        complete(RoutePipelineFunctions.RunStage.SCHEDULER_DECISION);
        if (!scheduled) {
          if (!verifiedRoutes().isEmpty() && synthesisGatePassed()) {
            pendingMetaReview = null;
            workflowCursor = CURSOR_SYNTHESIS;
            persistUnchecked("scheduler_decision", false);
            return SchedulerExit.READY_TO_SYNTHESIZE;
          }
          String stopCode =
              decision == null || decision.stopReason().isBlank()
                  ? schedulerStopCode()
                  : normalizedLowerCaseCode(decision.stopReason());
          String stopDetail = schedulerStopDetail(stopCode);
          recordSchedulerStop(stopCode, stopDetail);
          eventSchedulerAction("STOP", true, stopCode + ": " + stopDetail);
          persistUnchecked("scheduler_decision", false);
          return SchedulerExit.STOPPED;
        }
        recomputeNeighbors();
        workflowCursor = CURSOR_SCHEDULER_EXPLORE;
        persistUnchecked("scheduler_decision", false);
        continue;
      }

      if (CURSOR_SCHEDULER_EXPLORE.equals(workflowCursor)) {
        exploreUnstartedRoutes(false);
        workflowCursor = CURSOR_SCHEDULER_INTEGRATE;
        persistUnchecked("scheduler_exploration", false);
        continue;
      }
      if (CURSOR_SCHEDULER_INTEGRATE.equals(workflowCursor)) {
        integrateCommittedRoutes();
        finishActiveSchedulerBudgetEnvelope();
        workflowCursor = CURSOR_SCHEDULER_BROKER;
        persistUnchecked("scheduler_integration", false);
        continue;
      }
      if (CURSOR_SCHEDULER_BROKER.equals(workflowCursor)) {
        distributeVerifiedClaims();
        pendingMetaReview = null;
        workflowCursor = CURSOR_SCHEDULER_INSPIRATION;
        persistUnchecked("scheduler_round", false);
        continue;
      }
      if (CURSOR_SYNTHESIS.equals(workflowCursor)) {
        return SchedulerExit.READY_TO_SYNTHESIZE;
      }
      throw new IllegalStateException("invalid scheduler cursor: " + workflowCursor);
    }
  }

  static int inspirationRoundToRun(
      int currentRound,
      int maximumRounds,
      DesktopSolveCheckpoint.InspirationRoundProgress progress) {
    if (progress != null) {
      if (progress.roundIndex() != currentRound) {
        throw new IllegalStateException("inspiration checkpoint belongs to another scheduler round");
      }
      return currentRound;
    }
    return currentRound >= maximumRounds ? -1 : currentRound + 1;
  }

  private boolean synthesisGatePassed() {
    Set<String> verifiedRouteIds =
        verifiedRoutes().stream()
            .filter(route -> !route.claimIds.isEmpty())
            .map(route -> route.routeId)
            .collect(java.util.stream.Collectors.toSet());
    List<String> openCore =
        proofGraph.obligations().stream()
            .filter(obligation -> !"closed".equals(obligation.status()))
            .filter(
                obligation ->
                    obligation.kind() == ObligationKind.MAIN_GOAL
                        || obligation.priority() >= 0.75d
                            && obligation.routeIds().stream().anyMatch(verifiedRouteIds::contains))
            .map(ProofObligation::obligationId)
            .toList();
    List<String> needsReverify = new ArrayList<>(proofGraph.snapshot().needsReverify());
    var decision =
        proofControl
            .gates()
            .synthesisReadiness(
                proofControlMode(),
                openCore,
                needsReverify,
                List.of(),
                goalLinks.values().stream().toList(),
                List.of(),
                !verifiedRoutes().isEmpty(),
                openCore);
    boolean passed = decision.verdict() != ProofControlModels.GateVerdict.BLOCK;
    event(
        "synthesis_readiness_gate",
        "scheduler_decision",
        null,
        passed ? "verified" : "unverified",
        passed
            ? "Goal, scope, dependency, conflict, and common-mode readiness gates passed"
            : String.join("; ", decision.reasons()),
        decision.id());
    return passed;
  }

  private boolean reserveInitialExplorationBudget() {
    return budgetHost.reserveInitial();
  }

  private void finishActiveSchedulerBudgetEnvelope() {
    budgetScheduler.finish();
  }

  private BudgetStateSnapshot schedulerBudgetState() {
    return budgetHost.state();
  }

  private void eventSchedulerAction(String action, boolean applied, String reason) {
    event(
        "scheduler_action",
        "scheduler_decision",
        null,
        applied ? "completed" : "rejected",
        action + ": " + reason,
        "scheduler://round-"
            + roundIndex.get()
            + "/"
            + action.toLowerCase(Locale.ROOT));
  }

  static List<BudgetAction> compatibleSchedulerActions(
      List<BudgetAction> actions, int maximumActions) {
    if (maximumActions <= 0) {
      throw new IllegalArgumentException("maximumActions must be positive");
    }
    List<BudgetAction> selected = new ArrayList<>();
    Set<String> claimedTargets = new LinkedHashSet<>();
    for (BudgetAction action : actions) {
      if (action.action() != ActionKind.DEEPEN
          && action.action() != ActionKind.REVISE
          && action.action() != ActionKind.WIDEN) {
        continue;
      }
      String targetId = Objects.toString(action.targetId(), "").strip();
      String conflictKey =
          action.action() == ActionKind.WIDEN
              ? "scheduler-global-widen"
              : targetId.isEmpty() ? "route-unbound" : "route:" + targetId;
      if (claimedTargets.add(conflictKey)) {
        selected.add(action);
      }
      if (selected.size() == maximumActions) {
        break;
      }
    }
    return List.copyOf(selected);
  }

  private void bindMetaTarget(MetaReview review) {
    if (review == null || review.selectedTargetId() == null || review.selectedTargetId().isBlank()) {
      return;
    }
    String selected = review.selectedTargetId().strip();
    ProofObligation obligation = findObligation(selected).orElse(null);
    RouteState route = findRouteTarget(selected).orElse(null);
    if (obligation != null && route == null) {
      route = routeForObligation(obligation).orElse(null);
    }
    if (route != null && obligation == null) {
      obligation = minimumOpenObligation(route.routeId).orElse(null);
    }
    if (route == null || obligation == null) {
      event(
          "meta_target_unresolved",
          "scheduler_decision",
          null,
          "rejected",
          "Meta selected_target_id did not resolve to a live route, obligation, or checkpoint",
          selected);
      return;
    }
    ActionKind recommended =
        review.assessments().stream()
            .filter(assessment -> selected.equals(assessment.targetId()))
            .map(CandidateAssessment::recommendedAction)
            .findFirst()
            .orElse(route.failure == null ? ActionKind.DEEPEN : ActionKind.REVISE);
    String action = recommended == ActionKind.REVISE ? "REVISE" : "DEEPEN";
    if (enqueueProofTask("meta-review", route.routeId, obligation.obligationId(), action)) {
      event(
          "meta_target_bound",
          "scheduler_decision",
          null,
          "completed",
          "Bound Meta target to route "
              + route.routeId
              + ", obligation "
              + obligation.obligationId()
              + ", and committed checkpoint "
              + (route.checkpoint == null ? "pending" : route.checkpoint.checkpointId()),
          selected);
    }
  }

  private Optional<RouteState> findRouteTarget(String targetId) {
    return routes.stream()
        .filter(
            route ->
                targetId.equals(route.routeId)
                    || targetId.equals(route.strategy.strategyId())
                    || route.attempt != null && targetId.equals(route.attempt.attemptId())
                    || route.deltaId != null && targetId.equals(route.deltaId)
                    || route.checkpoint != null
                        && targetId.equals(route.checkpoint.checkpointId())
                    || route.revisionHistory.stream()
                        .map(DesktopSolveCheckpoint.AttemptRevisionCheckpoint::checkpoint)
                        .filter(Objects::nonNull)
                        .anyMatch(checkpoint -> targetId.equals(checkpoint.checkpointId())))
        .findFirst();
  }

  private Optional<ProofObligation> findObligation(String obligationId) {
    if (obligationId == null || obligationId.isBlank()) {
      return Optional.empty();
    }
    return proofGraph.obligations().stream()
        .filter(obligation -> obligation.obligationId().equals(obligationId))
        .findFirst();
  }

  private Optional<RouteState> routeForObligation(ProofObligation obligation) {
    return obligation.routeIds().stream()
        .filter(routeRegistry::exists)
        .flatMap(routeId -> routes.stream().filter(route -> route.routeId.equals(routeId)))
        .filter(this::routeEligibleForWork)
        .filter(route -> !"verified".equals(route.status))
        .findFirst()
        .or(
            () ->
                routes.stream()
                    .filter(this::routeEligibleForWork)
                    .filter(route -> !"verified".equals(route.status))
                    .min(
                        java.util.Comparator.comparingDouble(
                                (RouteState route) ->
                                    proofGraph.canonicalProofDebt(route.routeId))
                            .reversed()
                            .thenComparing(route -> route.routeId)));
  }

  private Optional<ProofObligation> minimumOpenObligation(String routeId) {
    return proofGraph.obligations().stream()
        .filter(obligation -> Set.of("open", "tentative", "blocked").contains(obligation.status()))
        .filter(
            obligation ->
                obligation.routeIds().contains(routeId)
                    || obligation.routeIds().contains("run"))
        .sorted(
            java.util.Comparator.comparing(
                    (ProofObligation obligation) ->
                        obligation.kind() == ObligationKind.MAIN_GOAL ? 1 : 0)
                .thenComparingInt(obligation -> obligation.dependencyIds().size())
                .thenComparingInt(obligation -> obligation.statement().length())
                .thenComparing(ProofObligation::priority, java.util.Comparator.reverseOrder())
                .thenComparing(ProofObligation::obligationId))
        .findFirst();
  }

  private boolean enqueueProofTask(
      String source, String routeId, String obligationId, String requestedAction) {
    CanonicalObligationRecord canonicalTarget =
        proofGraph.canonicalTargetForObligation(obligationId).orElse(null);
    BottleneckFamilyRecord family =
        canonicalTarget == null
            ? null
            : proofGraph
                .bottleneckFamilyForCanonical(canonicalTarget.canonicalTargetId())
                .orElse(null);
    FocusedRecoveryActionType controlAction = proofTaskControlAction(source, family);
    String familyId = family == null ? "" : family.familyId();
    String canonicalTargetId =
        canonicalTarget == null ? "" : canonicalTarget.canonicalTargetId();
    FocusedExpansionDecision control =
        proofGraphConvergence.decideExpansion(
            controlAction,
            canonicalTarget != null,
            proofGraph.activeCanonicalTargetCount(routeId),
            proofGraph.activeCanonicalTargetCount(),
            familyId,
            canonicalTargetId);
    proofGraphConvergence.recordExpansionDecision(
        controlAction, control.allowed(), familyId, canonicalTargetId);
    if (!control.allowed()) {
      if (control.deferred()) {
        deferredExpansions.record(
            problemHash,
            roundIndex.get(),
            routeId,
            obligationId,
            canonicalTargetId,
            controlAction,
            control);
      }
      event(
          "proof_task_deferred_by_graph_control",
          "scheduler_decision",
          null,
          "rejected",
          control.code(),
          obligationId);
      return false;
    }
    boolean automatic = automaticProofTaskSource(source);
    ProofTaskScope scope =
        automatic && family != null
            ? ProofTaskScope.BOTTLENECK_FAMILY
            : automatic && canonicalTarget != null
                ? ProofTaskScope.CANONICAL_TARGET
                : ProofTaskScope.ROUTE_OCCURRENCE;
    String scopeId =
        switch (scope) {
          case BOTTLENECK_FAMILY -> family.familyId();
          case CANONICAL_TARGET -> canonicalTarget.canonicalTargetId();
          case ROUTE_OCCURRENCE -> routeId + ":" + obligationId;
        };
    String actionKey = proofTaskActionKey(source, requestedAction, automatic);
    if (pendingProofTasks.stream()
        .anyMatch(
            task ->
                task.scope() == scope
                    && task.actionKey().equals(actionKey)
                    && switch (scope) {
                      case BOTTLENECK_FAMILY -> task.familyId().equals(scopeId);
                      case CANONICAL_TARGET -> task.canonicalTargetId().equals(scopeId);
                      case ROUTE_OCCURRENCE ->
                          task.routeId().equals(routeId)
                              && task.obligationId().equals(obligationId);
                    })) {
      return false;
    }
    if (!proofGraph.acquireCanonicalTaskLease(scope, scopeId, actionKey)) {
      return false;
    }
    String taskId =
        "proof-task-"
            + CanonicalJson.stableHash(
                    Map.of(
                        "scope", scope.name(),
                        "scope_id", scopeId,
                        "action_key", actionKey))
                .substring(0, 20);
    pendingProofTasks.add(
        new DesktopSolveCheckpoint.ScheduledProofTask(
            taskId,
            source,
            routeId,
            obligationId,
            canonicalTarget == null ? "" : canonicalTarget.canonicalTargetId(),
            family == null ? "" : family.familyId(),
            scope,
            actionKey,
            requestedAction,
            roundIndex.get()));
    return true;
  }

  private static boolean automaticProofTaskSource(String source) {
    String normalized = source == null ? "" : source.toLowerCase(Locale.ROOT);
    return normalized.contains("focused-recovery")
        || normalized.contains("focused-skeptic")
        || normalized.contains("focused-prover")
        || normalized.contains("exact-falsification")
        || normalized.contains("proof-debt")
        || normalized.contains("meta-review")
        || normalized.contains("inspiration")
        || normalized.contains("bridge");
  }

  private static FocusedRecoveryActionType proofTaskControlAction(
      String source, BottleneckFamilyRecord family) {
    return FocusedRecoveryActionType.classifyTaskSource(source, family != null);
  }

  private int reconsiderDeferredExpansions() {
    List<DeferredExpansionRecord> deferred = deferredExpansions.activeDeferredRecords();
    if (deferred.isEmpty()) {
      return 0;
    }
    Map<String, DeferredExpansionRecord> byId =
        deferred.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    DeferredExpansionRecord::deferredId,
                    java.util.function.Function.identity(),
                    (left, right) -> left,
                    LinkedHashMap::new));
    List<DeferredExpansionReactivationCandidate> candidates =
        deferred.stream().map(this::deferredReactivationCandidate).toList();
    List<DeferredExpansionReactivationDecision> decisions =
        deferredReactivationPlanner.plan(
            candidates, proofGraphConvergence.controlMode(), proofGraphConvergence.config());
    int reactivated = 0;
    for (DeferredExpansionReactivationDecision decision : decisions) {
      DeferredExpansionRecord record = byId.get(decision.deferredId());
      if (record == null) {
        throw new IllegalStateException("reactivation planner returned an unknown deferred record");
      }
      switch (decision.outcome()) {
        case KEEP_DEFERRED -> deferredExpansions.markEvaluated(record.deferredId(), roundIndex.get());
        case SATISFY_BY_ACTIVE_TARGET ->
            satisfyDeferredExpansion(record, decision.reason());
        case RETIRE -> retireDeferredExpansion(record, decision.reason());
        case REACTIVATE -> {
          if (reactivateDeferredExpansion(record, decision.reason())) {
            reactivated++;
          }
        }
      }
    }
    return reactivated;
  }

  private DeferredExpansionReactivationCandidate deferredReactivationCandidate(
      DeferredExpansionRecord record) {
    CanonicalObligationRecord target =
        proofGraph.allCanonicalTargets().stream()
            .filter(item -> item.canonicalTargetId().equals(record.canonicalTargetId()))
            .findFirst()
            .orElse(null);
    ProofObligation obligation =
        proofGraph.obligations().stream()
            .filter(item -> item.obligationId().equals(record.obligationId()))
            .findFirst()
            .orElse(null);
    boolean rawExists =
        proofGraph.rawObligationOccurrences().stream()
            .anyMatch(
                occurrence ->
                    occurrence.obligationId().equals(record.obligationId())
                        && occurrence.canonicalTargetId().equals(record.canonicalTargetId()));
    RouteState route =
        routes.stream().filter(item -> item.routeId.equals(record.routeId())).findFirst().orElse(null);
    boolean routePermanentlyUnavailable =
        route == null
            || route.metaAbandoned
            || Set.of("abandoned", "failed").contains(route.status)
            || "verified".equals(route.status) && !hasUnresolvedRouteObligation(route);
    boolean routeSchedulable =
        route != null && !routePermanentlyUnavailable && routeEligibleForWork(route);
    String familyId =
        target == null
            ? ""
            : proofGraph
                .bottleneckFamilyForCanonical(target.canonicalTargetId())
                .map(BottleneckFamilyRecord::familyId)
                .orElse("");
    boolean negativeAllowed =
        obligation != null && negativeKnowledgeAllowsDeferredTarget(obligation, record.actionType());
    return new DeferredExpansionReactivationCandidate(
        record,
        proofGraph.activeCanonicalTargetCount(record.routeId()),
        proofGraph.activeCanonicalTargetCount(),
        problemHash.equals(record.problemHash()),
        rawExists,
        target != null,
        target == null
            ? CanonicalObligationStatus.OPEN
            : proofGraph.canonicalStatus(target.canonicalTargetId()),
        target == null
            ? CanonicalObligationSchedulingState.RETIRED
            : target.schedulingState(),
        routeSchedulable,
        routePermanentlyUnavailable,
        negativeAllowed,
        target != null
            && proofGraphConvergence.selectsCurrentBinding(
                familyId, target.canonicalTargetId()),
        target == null ? 0.0d : proofGraph.representativeCentrality(target.canonicalTargetId()),
        target == null ? 0.0d : proofGraph.representativePriority(target.canonicalTargetId()));
  }

  private boolean negativeKnowledgeAllowsDeferredTarget(
      ProofObligation obligation, FocusedRecoveryActionType actionType) {
    NegativeCandidateIntent intent =
        actionType == FocusedRecoveryActionType.EXACT_FALSIFICATION
            ? NegativeCandidateIntent.FALSIFICATION_ONLY
            : NegativeCandidateIntent.PROOF_TARGET;
    return java.util.Arrays.stream(NegativeKnowledgeTargetType.values())
        .map(
            targetType ->
                new NegativeKnowledgeCandidate(
                    obligation.problemHash(),
                    targetType,
                    obligation.statement(),
                    obligation.normalizedStatement(),
                    obligation.assumptions(),
                    obligation.quantifiers(),
                    List.of(),
                    negativeKnowledgeScope(),
                    proofGraph
                        .canonicalTargetForObligation(obligation.obligationId())
                        .map(record -> record.signature().polarity())
                        .orElse(""),
                    NegativeKnowledgeSurface.PROOF_OBLIGATION_CREATION,
                    intent))
        .map(candidate -> negativeKnowledgeGate.evaluate(candidate, roundIndex.get()))
        .allMatch(NegativeKnowledgeDecision::allowed);
  }

  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification = "Transactional rollback must preserve the original deterministic failure")
  private void retireDeferredExpansion(DeferredExpansionRecord record, String reason) {
    ProofGraphSnapshot graphBefore = proofGraph.snapshot();
    var ledgerBefore = deferredExpansions.snapshot();
    List<DesktopSolveCheckpoint.ScheduledProofTask> tasksBefore = List.copyOf(pendingProofTasks);
    try {
      if (!record.canonicalTargetId().isBlank() && !record.obligationId().isBlank()) {
        var transition =
            proofGraph.retireDeferredCanonicalTarget(
                record.canonicalTargetId(),
                record.obligationId(),
                record.schedulingState(),
                roundIndex.get(),
                reason);
        boolean missingTargetRetirement =
            "TARGET_MISSING".equals(reason)
                && (transition.code() == CanonicalSchedulingTransitionCode.TARGET_NOT_FOUND
                    || transition.code()
                        == CanonicalSchedulingTransitionCode.OCCURRENCE_NOT_FOUND);
        if (!transition.transitioned() && !missingTargetRetirement) {
          throw new IllegalStateException(
              "deferred retirement graph transition failed: " + transition.code());
        }
      }
      deferredExpansions.markRetired(record.deferredId(), roundIndex.get(), reason);
      event(
          "deferred_expansion_retired",
          "scheduler_decision",
          null,
          "completed",
          reason,
          record.deferredId());
    } catch (RuntimeException exception) {
      rollbackDeferredMutation(graphBefore, ledgerBefore, tasksBefore);
      throw exception;
    }
  }

  private void satisfyDeferredExpansion(DeferredExpansionRecord record, String reason) {
    deferredExpansions.markSatisfiedByActiveTarget(
        record.deferredId(), roundIndex.get(), reason);
    event(
        "deferred_expansion_satisfied_by_active_target",
        "scheduler_decision",
        null,
        "completed",
        reason,
        record.deferredId());
  }

  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification = "Transactional rollback must preserve the original deterministic failure")
  private boolean reactivateDeferredExpansion(DeferredExpansionRecord record, String reason) {
    ProofGraphSnapshot graphBefore = proofGraph.snapshot();
    var ledgerBefore = deferredExpansions.snapshot();
    List<DesktopSolveCheckpoint.ScheduledProofTask> tasksBefore = List.copyOf(pendingProofTasks);
    try {
      CanonicalObligationRecord target =
          proofGraph.allCanonicalTargets().stream()
              .filter(item -> item.canonicalTargetId().equals(record.canonicalTargetId()))
              .findFirst()
              .orElseThrow();
      BottleneckFamilyRecord family =
          proofGraph.bottleneckFamilyForCanonical(target.canonicalTargetId()).orElse(null);
      String familyId = family == null ? "" : family.familyId();
      FocusedExpansionDecision gate =
          proofGraphConvergence.decideExpansion(
              record.actionType(),
              true,
              proofGraph.activeCanonicalTargetCount(record.routeId()),
              proofGraph.activeCanonicalTargetCount(),
              familyId,
              target.canonicalTargetId());
      proofGraphConvergence.recordExpansionDecision(
          record.actionType(), gate.allowed(), familyId, target.canonicalTargetId());
      if (!gate.allowed()) {
        deferredExpansions.markEvaluated(record.deferredId(), roundIndex.get());
        return false;
      }
      var transition =
          proofGraph.reactivateCanonicalTarget(
              target.canonicalTargetId(),
              record.obligationId(),
              record.schedulingState(),
              roundIndex.get(),
              reason);
      if (transition.code() == CanonicalSchedulingTransitionCode.ALREADY_ACTIVE) {
        deferredExpansions.markSatisfiedByActiveTarget(
            record.deferredId(), roundIndex.get(), "CANONICAL_TARGET_ALREADY_ACTIVE");
        return false;
      }
      if (transition.code() == CanonicalSchedulingTransitionCode.TERMINAL_TARGET) {
        deferredExpansions.markRetired(record.deferredId(), roundIndex.get(), "TARGET_TERMINAL");
        return false;
      }
      if (transition.code() != CanonicalSchedulingTransitionCode.REACTIVATED) {
        throw new IllegalStateException("deferred graph transition failed: " + transition.code());
      }
      failDeferredReactivationAt(DeferredReactivationFailurePoint.AFTER_GRAPH_TRANSITION);

      ProofTaskScope scope =
          family == null ? ProofTaskScope.CANONICAL_TARGET : ProofTaskScope.BOTTLENECK_FAMILY;
      String scopeId = family == null ? target.canonicalTargetId() : family.familyId();
      String actionKey =
          "deferred-reactivation:" + record.deferredId() + ":" + roundIndex.get();
      String source = "deferred-reactivation:" + record.deferredId();
      DesktopSolveCheckpoint.ScheduledProofTask existing =
          pendingProofTasks.stream().filter(task -> task.source().equals(source)).findFirst().orElse(null);
      if (existing != null) {
        deferredExpansions.markReactivated(
            record.deferredId(), roundIndex.get(), reason, existing.taskId());
        return true;
      }
      if (!proofGraph.acquireCanonicalTaskLease(scope, scopeId, actionKey)) {
        throw new IllegalStateException("deferred reactivation task lease is unavailable");
      }
      failDeferredReactivationAt(DeferredReactivationFailurePoint.AFTER_TASK_LEASE);
      String taskId =
          "proof-task-"
              + CanonicalJson.stableHash(
                      Map.of(
                          "scope", scope.name(),
                          "scope_id", scopeId,
                          "action_key", actionKey))
                  .substring(0, 20);
      pendingProofTasks.add(
          new DesktopSolveCheckpoint.ScheduledProofTask(
              taskId,
              source,
              record.routeId(),
              record.obligationId(),
              target.canonicalTargetId(),
              familyId,
              scope,
              actionKey,
              record.actionType() == FocusedRecoveryActionType.EXACT_FALSIFICATION
                  ? "FALSIFY"
                  : "REPAIR",
              roundIndex.get()));
      failDeferredReactivationAt(DeferredReactivationFailurePoint.AFTER_PENDING_TASK);
      deferredExpansions.markReactivated(
          record.deferredId(), roundIndex.get(), reason, taskId);
      event(
          "deferred_expansion_reactivated",
          "scheduler_decision",
          null,
          "completed",
          reason,
          record.deferredId());
      return true;
    } catch (RuntimeException exception) {
      rollbackDeferredMutation(graphBefore, ledgerBefore, tasksBefore);
      throw exception;
    }
  }

  private void rollbackDeferredMutation(
      ProofGraphSnapshot graphBefore,
      io.github.aililuola.mathproofmesh.proofgraph.DeferredExpansionSnapshot ledgerBefore,
      List<DesktopSolveCheckpoint.ScheduledProofTask> tasksBefore) {
    proofGraph = ProofGraphStore.restore(graphBefore, ProofGraphPolicy.defaults());
    deferredExpansions = DeferredExpansionLedger.restore(ledgerBefore);
    pendingProofTasks.clear();
    pendingProofTasks.addAll(tasksBefore);
    installNegativeKnowledgeRuntime();
  }

  private void failDeferredReactivationAt(DeferredReactivationFailurePoint point) {
    if (deferredReactivationFailurePoint == point) {
      throw new IllegalStateException("injected deferred reactivation failure: " + point);
    }
  }

  void setDeferredReactivationFailurePointForTest(DeferredReactivationFailurePoint point) {
    deferredReactivationFailurePoint =
        point == null ? DeferredReactivationFailurePoint.NONE : point;
  }

  private String proofTaskActionKey(String source, String requestedAction, boolean automatic) {
    String normalized = source == null ? "" : source.toLowerCase(Locale.ROOT);
    if (normalized.contains("focused-recovery")) {
      return "focused-recovery:"
          + proofGraphConvergence
              .focusedRecoveryPlan()
              .map(FocusedRecoveryPlan::episodeId)
              .orElse("none")
          + ":"
          + roundIndex.get();
    }
    return automatic ? "repair" : requestedAction.toLowerCase(Locale.ROOT).strip();
  }

  private boolean schedulePendingProofTask() {
    while (!pendingProofTasks.isEmpty()) {
      DesktopSolveCheckpoint.ScheduledProofTask task = pendingProofTasks.getFirst();
      RouteState route =
          routes.stream()
              .filter(candidate -> candidate.routeId.equals(task.routeId()))
              .findFirst()
              .orElse(null);
      ProofObligation obligation = findObligation(task.obligationId()).orElse(null);
      if (route == null
          || obligation == null
          || !Set.of("open", "tentative", "blocked").contains(obligation.status())
          || !routeEligibleForWork(route)) {
        pendingProofTasks.removeFirst();
        continue;
      }
      route.focusObligationId = obligation.obligationId();
      route.focusedCanonicalTargetId = task.canonicalTargetId();
      route.focusedBottleneckFamilyId = task.familyId();
      route.focusSource = task.source();
      boolean scheduled;
      if (route.attempt == null && route.segmentCount == 0 && "pending".equals(route.status)) {
        route.integrated = false;
        scheduled = true;
      } else if ("REVISE".equals(task.requestedAction())) {
        scheduled = reviseFailedRoute(route.routeId);
      } else {
        scheduled = deepenRoute(route.routeId);
      }
      pendingProofTasks.removeFirst();
      event(
          "proof_task_scheduled",
          "scheduler_decision",
          route.author.id(),
          scheduled ? "completed" : "rejected",
          task.source()
              + " targeted obligation "
              + obligation.obligationId()
              + " with "
              + task.requestedAction(),
          task.taskId());
      if (scheduled) {
        return true;
      }
    }
    return false;
  }

  private void enqueueDebtRepairTaskIfStalled() {
    if (proofDebtHistory.size() < 3 || !pendingProofTasks.isEmpty()) {
      return;
    }
    int last = proofDebtHistory.size() - 1;
    if (proofDebtHistory.get(last) < proofDebtHistory.get(last - 1)
        || proofDebtHistory.get(last - 1) < proofDebtHistory.get(last - 2)) {
      return;
    }
    proofGraph.coreOpenObligations().stream()
        .filter(obligation -> obligation.kind() != ObligationKind.MAIN_GOAL)
        .min(
            java.util.Comparator.comparingInt(
                    (ProofObligation obligation) -> obligation.dependencyIds().size())
                .thenComparingInt(obligation -> obligation.statement().length())
                .thenComparing(ProofObligation::priority, java.util.Comparator.reverseOrder()))
        .ifPresent(
            obligation ->
                routeForObligation(obligation)
                    .ifPresent(
                        route ->
                            enqueueProofTask(
                                "proof-debt-stall",
                                route.routeId,
                                obligation.obligationId(),
                                route.failure == null ? "DEEPEN" : "REVISE")));
  }

  private String schedulerStopCode() {
    if (ledger.remainingCalls() <= 0) {
      return "call_budget_exhausted";
    }
    if (roundIndex.get() >= config.budget().maxRounds()) {
      return "round_budget_exhausted";
    }
    boolean canDeepen = routes.stream().anyMatch(this::canDeepenRoute);
    boolean canRevise = routes.stream().anyMatch(this::canReviseRoute);
    if (routes.size() >= config.budget().maxPaths()
        && !canDeepen
        && !canRevise
        && hasOpenObligations()) {
      return "route_cap_no_revisable_candidate";
    }
    return hasOpenObligations() ? "no_candidate" : "strategy_space_exhausted";
  }

  private String schedulerStopDetail(String code) {
    return switch (code) {
      case "call_budget_exhausted" ->
          "No model call remains; unresolved obligations are retained for resume.";
      case "round_budget_exhausted" ->
          "No scheduler round remains; unresolved obligations are retained for resume.";
      case "route_cap_no_revisable_candidate" ->
          "The independent-route cap is full and every existing route exhausted its revision allowance.";
      case "strategy_space_exhausted" ->
          "All tracked proof obligations are closed or refuted, but no synthesis-ready proof survived verification.";
      default ->
          "Open obligations remain, but no admissible DEEPEN, REVISE, or WIDEN candidate exists.";
    };
  }

  private void recordSchedulerStop(String code, String detail) {
    BudgetConfig budget = config.budget();
    long usedTokens = ledger.totals().totalTokens();
    int remainingTokens =
        budget.maxTotalTokens() == null
            ? 0
            : safeInt(Math.max(0L, budget.maxTotalTokens().longValue() - usedTokens));
    double remainingCost =
        budget.maxCostUsd() == null
            ? 0.0d
            : Math.max(0.0d, budget.maxCostUsd() - ledger.totals().costUsd().doubleValue());
    schedulerStop =
        new DesktopSolveCheckpoint.SchedulerStop(
            code,
            detail,
            routes.size(),
            independentRouteCount(),
            budget.maxPaths(),
            safeInt(ledger.remainingCalls()),
            remainingTokens,
            remainingCost,
            Math.max(0, budget.maxRounds() - roundIndex.get()),
            openObligationCount());
  }

  private int independentRouteCount() {
    return (int)
        routes.stream()
            .map(
                route -> {
                  String signature = topology.dependencySignature(route.strategy);
                  return signature.isBlank() ? route.strategy.strategyId() : signature;
                })
            .distinct()
            .count();
  }

  private boolean canDeepenRoute(RouteState route) {
    return routeEligibleForWork(route)
        && !"abandoned".equals(route.status)
        && route.failure == null
        && (!"verified".equals(route.status) || hasUnresolvedRouteObligation(route))
        && route.revisionCount < config.budget().maxRevisions();
  }

  private boolean canReviseRoute(RouteState route) {
    return routeEligibleForWork(route)
        && !"verified".equals(route.status)
        && !"abandoned".equals(route.status)
        && route.failure != null
        && route.revisionCount < config.budget().maxRevisions();
  }

  private boolean hasOpenObligations() {
    return openObligationCount() > 0;
  }

  private boolean hasUnresolvedRouteObligation(RouteState route) {
    return minimumOpenObligation(route.routeId).isPresent();
  }

  private int openObligationCount() {
    return (int)
        proofGraph.obligations().stream()
            .filter(obligation -> Set.of("open", "tentative", "blocked").contains(obligation.status()))
            .count();
  }

  private int closedObligationCount() {
    return (int)
        proofGraph.obligations().stream()
            .filter(obligation -> "closed".equals(obligation.status()))
            .count();
  }

  private AttemptEvidence.FailureClass attemptFailureClass(RouteState route) {
    if (route.failure == null) {
      return Set.of("verified", "pending", "waiting", "partial", "submitted")
              .contains(route.status)
          ? AttemptEvidence.FailureClass.NONE
          : AttemptEvidence.FailureClass.STRATEGY;
    }
    return switch (route.failure.failureClass()) {
      case EXECUTION -> AttemptEvidence.FailureClass.EXECUTION;
      case FRAMING -> AttemptEvidence.FailureClass.PROBLEM_INTEGRITY;
      case PLAN, BRIDGE -> AttemptEvidence.FailureClass.STRUCTURAL;
    };
  }

  private boolean widenRoutes() {
    if (proofGraphConvergence.controlMode() == ProofGraphControlMode.FOCUSED_RECOVERY) {
      proofGraphConvergence.recordGenericExpansionAttempt(false);
      eventSchedulerAction(
          "NEW_ROUTE", false, "generic route widening deferred during focused recovery");
      return false;
    }
    if (nextStrategyIndex.get() >= admittedStrategies.size()
        || routes.size() >= config.budget().maxPaths()) {
      return false;
    }
    int requested =
        Math.min(
            config.scheduler().widenPathsPerAction(),
            Math.min(
                admittedStrategies.size() - nextStrategyIndex.get(),
                config.budget().maxPaths() - routes.size()));
    int added = 0;
    while (added < requested
        && nextStrategyIndex.get() < admittedStrategies.size()
        && routes.size() < config.budget().maxPaths()) {
      StrategyCard candidate = admittedStrategies.get(nextStrategyIndex.getAndIncrement());
      try {
        negativeKnowledgeGate.requireAllAllowed(
            negativeKnowledgeCandidates(
                candidate,
                strategyBlueprints.get(candidate.strategyId()),
                NegativeKnowledgeSurface.ROUTE_WIDENING),
            roundIndex.get());
        if (!strategyPortfolioAllowsWidening(candidate)) {
          event(
              "widen_candidate_rejected",
              "scheduler_decision",
              null,
              "rejected",
              "WIDEN candidate violates the active mechanism or unresolved-claim portfolio",
              "strategy://" + candidate.strategyId());
          continue;
        }
        addRoute(candidate, 0, NegativeKnowledgeSurface.ROUTE_WIDENING);
      } catch (NegativeKnowledgeBlockedException exception) {
        recordNegativeKnowledgeRejection(
            "widen_candidate_rejected",
            "scheduler_decision",
            candidate.title(),
            exception);
        continue;
      }
      added++;
    }
    return added > 0;
  }

  private boolean deepenRoute(String targetRouteId) {
    RouteState target =
        routes.stream()
            .filter(route -> targetRouteId != null && targetRouteId.equals(route.routeId))
            .filter(this::routeEligibleForWork)
            .findFirst()
            .orElseGet(
                () ->
                    routes.stream()
                        .filter(route -> !"verified".equals(route.status))
                        .filter(this::routeEligibleForWork)
                        .findFirst()
                        .orElse(null));
    if (target == null
        || target.revisionCount >= config.budget().maxRevisions()) {
      return false;
    }
    StrategyCard revision = revisionStrategy(target, "deepen");
    return prepareRouteRevision(
        target,
        revision,
        "DEEPEN",
        StrategyArchive.RevisionReason.BRIDGE_INSERTION);
  }

  private boolean reviseFailedRoute(String targetRouteId) {
    RouteState target =
        routes.stream()
            .filter(route -> targetRouteId == null || targetRouteId.equals(route.routeId))
            .filter(route -> !"verified".equals(route.status))
            .filter(this::routeEligibleForWork)
            .filter(route -> route.revisionCount < config.budget().maxRevisions())
            .findFirst()
            .orElseGet(
                () ->
                    routes.stream()
                        .filter(route -> !"verified".equals(route.status))
                        .filter(this::routeEligibleForWork)
                        .filter(route -> route.revisionCount < config.budget().maxRevisions())
                        .findFirst()
                        .orElse(null));
    if (target == null) {
      return false;
    }
    StrategyCard revision = revisionStrategy(target, "revise");
    StrategyArchive.RevisionReason reason =
        target.failure != null
                && target.failure.failureClass() == ProofControlModels.FailureClass.FRAMING
            ? StrategyArchive.RevisionReason.SCOPE_REPAIR
            : StrategyArchive.RevisionReason.PLAN_FAILURE;
    return prepareRouteRevision(target, revision, "REVISE", reason);
  }

  private boolean prepareRouteRevision(
      RouteState target,
      StrategyCard revision,
      String action,
      StrategyArchive.RevisionReason reason) {
    StrategyRevisionKind revisionKind = StrategyRevisionKind.LOCAL_REPAIR;
    LocalRepairPlan repairPlan = localRepairPlan(target, revision.bottleneck());
    if (proofGraphConvergence.controlMode() == ProofGraphControlMode.FOCUSED_RECOVERY
        && !routeMatchesFocusedRecovery(target)) {
      proofGraphConvergence.recordGenericExpansionAttempt(false);
      eventSchedulerAction(
          action, false, "unrelated route revision deferred during focused recovery");
      return false;
    }
    ProofControlModels.Strategy control =
        localRepairControlStrategy(target.strategy, revision, target.routeId);
    ProofControlModels.Obligation goal = controlGoal();
    StrategyBlueprintCompiler.Compilation blueprint =
        proofControl.blueprintCompiler().compile(problemHash, control, goal);
    ProofControlModels.ScopeSignature scope =
        proofControl.scopeGuard().extract("goal-scope", request.problem(), List.of(), 1.0d);
    ProofControlModels.GoalLink link =
        proofControl
            .goalAlignment()
            .assess(
                control.id(),
                request.problem(),
                scope,
                goal,
                scope,
                proofControl.scopeGuard(),
                (source, destination) -> source.equals(destination));
    try {
      negativeKnowledgeGate.requireAllAllowed(
          negativeKnowledgeCandidates(
              revision, blueprint, NegativeKnowledgeSurface.ROUTE_REVISION),
          roundIndex.get());
    } catch (NegativeKnowledgeBlockedException exception) {
      recordNegativeKnowledgeRejection(
          "revision_rejected",
          "scheduler_decision",
          revision.title(),
          exception);
      eventSchedulerAction(action, false, "revision conflicts with permanent Negative Knowledge");
      return false;
    }
    boolean duplicate =
        routes.stream()
            .filter(route -> route != target)
            .map(route -> topology.mathNormalize(topology.strategyText(route.strategy)))
            .anyMatch(topology.mathNormalize(topology.strategyText(revision))::equals);
    boolean commonMode =
        routes.stream()
            .filter(route -> route != target)
            .anyMatch(
                route ->
                    topology.sharesUnverifiedDependency(
                        revision,
                        route.strategy,
                        Math.max(0.82d, config.topology().strategySimilarityThreshold())));
    var admission =
        proofControl
            .routeAdmission()
            .evaluate(proofControlMode(), control, blueprint, link, duplicate, commonMode);
    if (!"accepted".equals(blueprint.blueprint().status())
        || admission.blocksRuntime(proofControlMode())) {
      eventSchedulerAction(
          action,
          false,
          "revision failed route admission"
              + (admission.reasons().isEmpty()
                  ? ""
                  : ": " + String.join("; ", admission.reasons())));
      return false;
    }
    strategyArchive.registerChild(control, target.strategy.strategyId(), reason);
    strategyBlueprints.put(revision.strategyId(), blueprint);
    goalLinks.put(revision.strategyId(), link);
    archiveCurrentAttempt(target, action);
    ensureSeedCheckpoint(target);
    int nextRevision = target.revisionCount + 1;
    target.checkpoint =
        checkpoints.branchForStrategy(
            target.checkpoint.checkpointId(),
            target.routeId + "-revision-" + nextRevision,
            revision.strategyId());
    target.strategy = revision;
    target.revisionCount = nextRevision;
    target.attempt = null;
    target.skepticReview = null;
    target.toolAudit = null;
    target.structuralReview = null;
    target.detailedReview = null;
    target.crossProviderReview = null;
    target.teamResult = null;
    target.escalation = null;
    target.validationExecution = null;
    target.delta = null;
    target.deltaId = null;
    target.failure = null;
    target.status = "pending";
    target.failureReason = "";
    target.nearMissId = null;
    target.segmentCount = 0;
    target.noProgressSegments = 0;
    target.reviewComplete = false;
    target.checkpointProcessed = false;
    target.integrated = false;
    addBlueprintObligations(target);
    event(
        "attempt_revision_scheduled",
        "scheduler_decision",
        target.author.id(),
        "completed",
        action
            + " applied "
            + revisionKind.name()
            + " while preserving the prior attempt and opened revision "
            + nextRevision,
        target.checkpoint.checkpointId());
    LocalRepairApplyReceipt receipt = localRepairReceipt(repairPlan, revision);
    mathematicalArtifactBroker.bindEffectTarget(
        target.routeId,
        BrokerArtifactUseKind.TRIGGERS_LOCAL_REPAIR,
        receipt.repairId(),
        Set.of(),
        Set.of(receipt.exactFocusedObligationId()));
    event(
        "local_repair_applied",
        "scheduler_decision",
        target.author.id(),
        "completed",
        "Applied a same-object, same-target bridge repair outside the semantic pivot ledger",
        receipt.repairId());
    return true;
  }

  private void archiveCurrentAttempt(RouteState route, String action) {
    route.revisionHistory.add(
        new DesktopSolveCheckpoint.AttemptRevisionCheckpoint(
            route.revisionCount,
            action,
            route.strategy,
            route.attempt,
            route.detailedReview,
            route.toolAudit,
            route.checkpoint,
            route.delta,
            route.deltaId,
            route.status,
            route.failureReason,
            route.claimIds,
            route.segmentCount));
  }

  private StrategyCard revisionStrategy(RouteState target, String kind) {
    String hint =
        target.nearMissId == null
            ? target.failureReason
            : nearMisses.relevant(target.routeId).stream()
                .findFirst()
                .map(nearMisses::promptHint)
                .orElse(target.failureReason);
    if (target.focusObligationId != null && !target.focusObligationId.isBlank()) {
      ProofObligation focus = findObligation(target.focusObligationId).orElse(null);
      if (focus != null) {
        hint = "Resolve exactly this obligation before any broader claim: " + focus.statement();
      }
    }
    LocalRepairPlan repair = localRepairPlan(target, hint);
    List<String> expectedLemmas = new ArrayList<>(target.strategy.expectedLemmas());
    if (!expectedLemmas.contains(repair.updatedExpectedLemma())) {
      expectedLemmas.add(repair.updatedExpectedLemma());
    }
    return new StrategyCard(
        null,
        repair.bridgeStatement(),
        target.strategy.calculationChecks(),
        target.strategy.calculationEvidenceRefs(),
        target.strategy.computationHints(),
        target.strategy.coreIdea(),
        target.strategy.criticalClaims(),
        target.strategy.estimatedCost(),
        Math.max(0.05d, target.strategy.estimatedSuccess() * 0.9d),
        expectedLemmas,
        repair.updatedFalsificationTest(),
        target.strategy.independenceBasis()
            + "; local-repair="
            + repair.repairId()
            + "; committed-state "
            + kind,
        target.strategy.inspirationProposalId(),
        target.strategy.keyOriginalStep(),
        List.of(target.strategy.strategyId()),
        target.strategy.prerequisites(),
        target.strategy.strategyId() + "-" + kind + "-r" + roundIndex.get(),
        target.strategy.tags(),
        target.strategy.title() + " (" + kind + " " + roundIndex.get() + ")");
  }

  private LocalRepairPlan localRepairPlan(RouteState target, String hint) {
    String focusId =
        target.focusObligationId == null || target.focusObligationId.isBlank()
            ? routeObligationId(target.routeId)
            : target.focusObligationId;
    String bridge =
        findObligation(focusId)
            .map(ProofObligation::statement)
            .filter(value -> !value.isBlank())
            .orElseGet(
                () ->
                    hint == null || hint.isBlank()
                        ? target.strategy.bottleneck()
                        : hint.strip());
    String lemma = "Establish the focused bridge: " + bridge;
    String falsification =
        "Try to falsify exactly the focused bridge before reusing it: " + bridge;
    return new LocalRepairPlan(
        null,
        target.strategy.strategyId(),
        focusId,
        bridge,
        lemma,
        falsification,
        "A local dependency is missing while object, target, and direction remain fixed.");
  }

  private LocalRepairApplyReceipt localRepairReceipt(
      LocalRepairPlan plan, StrategyCard revision) {
    return new LocalRepairApplyReceipt(
        plan.repairId(),
        plan.sourceStrategyId(),
        revision.strategyId(),
        plan.exactFocusedObligationId(),
        roundIndex.get(),
        true);
  }

  private ProofControlModels.Strategy localRepairControlStrategy(
      StrategyCard source, StrategyCard revision, String routeId) {
    ProofControlModels.Strategy sourceControl = controlStrategy(source, routeId);
    ProofControlModels.Strategy revisionControl = controlStrategy(revision, routeId);
    LinkedHashSet<String> preservedObjects =
        new LinkedHashSet<>(sourceControl.domainObjects());
    StrategyArchive.Lineage sourceLineage = strategyArchive.lineage().get(source.strategyId());
    if (sourceLineage != null) {
      StrategyArchive.Entry epochRoot =
          strategyArchive.snapshot().originals().get(sourceLineage.rootStrategyId());
      if (epochRoot != null) {
        preservedObjects.addAll(epochRoot.domainObjects());
      }
    }
    preservedObjects.addAll(revisionControl.domainObjects());
    return new ProofControlModels.Strategy(
        revisionControl.id(),
        revisionControl.title(),
        revisionControl.mechanism(),
        revisionControl.prerequisites(),
        revisionControl.criticalClaims(),
        revisionControl.expectedLemmas(),
        revisionControl.falsificationTests(),
        List.copyOf(preservedObjects),
        revisionControl.routeId());
  }

  PivotDelta compileSemanticPivotProposal(SemanticPivotProposal proposal) {
    RouteState route = requirePivotRoute(proposal.routeId());
    return semanticPivotCompiler.compile(proposal, pivotObstructionReferences(route));
  }

  SemanticPivotRecord applySemanticPivot(
      PivotDelta delta, SemanticPivotReviewBatch review) {
    return applySemanticPivot(delta, review, null);
  }

  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification = "The meta-pivot projection is restored before an atomic apply failure is propagated.")
  private SemanticPivotRecord applySemanticPivot(
      PivotDelta delta,
      SemanticPivotReviewBatch review,
      String metaPivotIntentId) {
    RouteState route = requirePivotRoute(delta.routeId());
    StrategyBlueprintCompiler.Compilation proposedBlueprint =
        proofControl
            .blueprintCompiler()
            .compile(problemHash, controlStrategy(delta.proposedStrategy(), route.routeId), controlGoal());
    PivotStructuralSignature sourceSignature = pivotSignature(route, null, null);
    PivotStructuralSignature proposedSignature =
        pivotSignature(route, delta, proposedBlueprint);
    PivotAuthorityContext authority = pivotAuthority(route, delta);
    ProofControlModels.GoalLink proposedGoalLink =
        pivotGoalLink(delta.proposedStrategy(), route.routeId);

    SemanticPivotController.Preparation preparation =
        semanticPivots.prepare(
            delta,
            sourceSignature,
            proposedSignature,
            authority,
            review == null ? "missing-proposer" : review.proposerAgentId(),
            review,
            config.budget().verificationPassThreshold(),
            () ->
                semanticPivotExternalGateFailures(
                    route, delta, proposedBlueprint, proposedGoalLink));
    if (!preparation.admitted()) {
      event(
          "semantic_pivot_rejected",
          "semantic_pivot_review",
          review == null ? null : review.reviewerAgentId(),
          "rejected",
          String.join(",", preparation.failureCodes()),
          delta.pivotId());
      return preparation.record();
    }
    MetaPivotController.Snapshot metaBefore = proofControl.metaPivot().snapshot();
    if (metaPivotIntentId != null) {
      proofControl
          .metaPivot()
          .admit(
              metaPivotIntentId,
              true,
              "semantic-pivot-review://" + review.reportId());
    }
    SemanticPivotRecord applied;
    SemanticPivotSnapshot pivotBeforeApply = semanticPivots.ledger().snapshot();
    try {
      applied =
          semanticPivots.apply(
              preparation.plan(),
              plan ->
                  applySemanticPivotAtomically(
                      route,
                      plan,
                      proposedBlueprint,
                      proposedGoalLink,
                      pivotBeforeApply));
    } catch (RuntimeException exception) {
      if (metaPivotIntentId != null) {
        proofControl.metaPivot().restore(metaBefore);
      }
      throw exception;
    }
    if (metaPivotIntentId != null) {
      proofControl
          .metaPivot()
          .execute(
              metaPivotIntentId,
              delta.transformationTypes().stream().map(Enum::name).toList(),
              applied.applyReceipt(),
              List.of(),
              "Independent review admitted and the semantic delta was atomically applied");
    }
    event(
        "semantic_pivot_applied",
        "semantic_pivot_apply",
        review.reviewerAgentId(),
        "completed",
        "Applied a reviewed non-empty mathematical strategy-state delta",
        applied.applyReceipt().receiptId());
    Set<String> pivotClaimIds =
        delta.claimUseChanges().stream()
            .map(change -> change.claimId())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    Set<String> pivotObligationIds = new LinkedHashSet<>();
    delta.obligationChanges().stream()
        .map(change -> change.obligationId())
        .forEach(pivotObligationIds::add);
    delta.obstructionRefs().stream()
        .map(ref -> ref.boundCanonicalTargetId())
        .filter(java.util.Objects::nonNull)
        .forEach(pivotObligationIds::add);
    mathematicalArtifactBroker.bindEffectTarget(
        route.routeId,
        BrokerArtifactUseKind.TRIGGERS_SEMANTIC_PIVOT,
        delta.pivotId(),
        pivotClaimIds,
        pivotObligationIds);
    return applied;
  }

  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification = "Recoverable provider failures are audited; cancellation and programming failures propagate.")
  private SemanticPivotRecord runSemanticPivotCycle(
      MetaPivotController.Pivot pivot, List<InspirationProposal> produced) {
    if (pivot == null || produced == null || produced.isEmpty()) {
      return null;
    }
    RouteState route =
        routes.stream()
            .filter(candidate -> candidate.routeId.equals(pivot.routeId()))
            .findFirst()
            .orElse(null);
    if (route == null || !semanticPivotMechanismRequested(pivot.requestedMechanisms())) {
      return null;
    }
    if (!route.activeSemanticPivotId.isBlank()) {
      SemanticPivotRecord prior = semanticPivots.ledger().get(route.activeSemanticPivotId);
      if (prior.applyReceipt() != null
          && prior.applyReceipt().appliedRound() == pivot.round()) {
        proofControl
            .metaPivot()
            .admit(
                pivot.pivotId(),
                true,
                "semantic-pivot-replay://" + prior.pivotId());
        return prior;
      }
    }
    Map<String, PivotObstructionRef> obstructionRefs = pivotObstructionReferences(route);
    if (obstructionRefs.isEmpty()) {
      return null;
    }
    InspirationProposal sourceProposal = produced.getFirst();
    AgentRuntime proposer = requireAgent(sourceProposal.sourceAgentId());
    StructuredCallResult<SemanticPivotProposal> proposalCall;
    try {
      proposalCall =
          callStage(
              "semantic-pivot-proposal-" + pivot.pivotId(),
              "semantic_pivot_proposal",
              SemanticPivotProposal.class,
              semanticPivotProposalContext(route, pivot, produced, obstructionRefs),
              proposer,
              "breadth",
              "Drafting one typed semantic strategy-state delta");
    } catch (RuntimeException failure) {
      if (!isRecoverableInspirationAgentFailure(failure)) {
        throw failure;
      }
      event(
          "semantic_pivot_proposal_failed",
          "semantic_pivot_proposal",
          proposer.id(),
          "warning",
          inspirationFailureSummary(failure),
          pivot.pivotId());
      return null;
    }

    SemanticPivotProposal proposal;
    PivotDelta delta;
    try {
      proposal = bindSemanticPivotProposal(proposalCall.value(), proposer, route);
      delta = semanticPivotCompiler.compile(proposal, obstructionRefs);
    } catch (IllegalArgumentException failure) {
      event(
          "semantic_pivot_proposal_rejected",
          "semantic_pivot_proposal",
          proposer.id(),
          "rejected",
          failure.getMessage(),
          proposalCall.value().proposalId());
      return null;
    }

    AgentRuntime reviewer =
        selectIndependentAgent(Set.of(proposer.id()), "detailed_verifier");
    StructuredCallResult<SemanticPivotReviewBatch> reviewCall;
    try {
      reviewCall =
          callStage(
              "semantic-pivot-review-" + delta.pivotId(),
              "semantic_pivot_review",
              SemanticPivotReviewBatch.class,
              semanticPivotReviewContext(route, delta, proposer.id()),
              reviewer,
              "verification",
              "Independently reviewing one semantic strategy-state delta");
    } catch (RuntimeException failure) {
      if (!isRecoverableInspirationAgentFailure(failure)) {
        throw failure;
      }
      event(
          "semantic_pivot_review_failed",
          "semantic_pivot_review",
          reviewer.id(),
          "warning",
          inspirationFailureSummary(failure),
          delta.pivotId());
      return null;
    }
    SemanticPivotReviewBatch review =
        new SemanticPivotReviewBatch(
            reviewCall.value().reportId(),
            reviewer.id(),
            proposer.id(),
            reviewCall.value().decisions(),
            reviewCall.responseArtifactRef(),
            reviewCall.usage());
    return applySemanticPivot(delta, review, pivot.pivotId());
  }

  private Map<String, Object> semanticPivotProposalContext(
      RouteState route,
      MetaPivotController.Pivot pivot,
      List<InspirationProposal> produced,
      Map<String, PivotObstructionRef> obstructionRefs) {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put(
        "immutable_root_goal",
        Map.of(
            "source_statement", rootGoal().sourceStatement(),
            "source_statement_hash", rootGoal().sourceStatementHash(),
            "problem_hash", problemHash,
            "editable", false));
    context.put("route_id", route.routeId);
    context.put("source_strategy", route.strategy);
    context.put("source_structural_signature", pivotSignature(route, null, null));
    context.put("trusted_obstruction_refs", obstructionRefs.values());
    context.put(
        "verified_facts",
        typedMemory.facts().stream()
            .limit(24)
            .map(fact -> Map.of("message_id", fact.messageId(), "statement", fact.statement()))
            .toList());
    context.put(
        "permanent_negative_summaries",
        negativeKnowledgeRegistry.records().stream()
            .filter(NegativeKnowledgeRecord::permanent)
            .filter(record -> record.problemHash().equals(problemHash))
            .limit(24)
            .map(
                record ->
                    Map.of(
                        "negative_id", record.negativeId(),
                        "target_type", record.targetType(),
                        "statement", record.statement(),
                        "scope_limitations", record.scopeLimitations()))
            .toList());
    context.put(
        "active_canonical_targets",
        proofGraph.activeCanonicalOpenTargets().stream()
            .filter(target -> target.routeIds().contains(route.routeId))
            .limit(24)
            .toList());
    context.put(
        "active_research_findings",
        researchCheckpointLedger().activeFindings(route.routeId).stream().limit(24).toList());
    context.put(
        "allowed_transformation_types",
        List.of(
                PivotTransformationType.OBJECT_REPLACEMENT,
                PivotTransformationType.TARGET_REFORMULATION,
                PivotTransformationType.DIRECTION_REVERSAL,
                PivotTransformationType.REPRESENTATION_CHANGE,
                PivotTransformationType.ASSUMPTION_CHANGE,
                PivotTransformationType.DECOMPOSITION_CHANGE,
                PivotTransformationType.DUALIZATION,
                PivotTransformationType.AUXILIARY_OBJECT_INTRODUCTION)
            .stream()
            .map(Enum::name)
            .toList());
    context.put(
        "focused_recovery_plan",
        proofGraphConvergence.focusedRecoveryPlan().<Object>map(value -> value).orElse(Map.of()));
    context.put("pivot_intent", pivot);
    context.put("bounded_inspiration_proposals", produced.stream().limit(8).toList());
    context.put(
        "authority_rule",
        "Return a non-authoritative draft. Leave claimed_pivot_id and "
            + "claimed_structural_delta_hash empty; never change the immutable root goal or "
            + "claim verified/fact/refutation/permanent-negative authority. "
            + "ADD_AS_PROPOSED_CLAIM requires a complete proposed_claim payload with a normalized "
            + "statement hash and still enters issue-003 review only as PROPOSED.");
    return Map.copyOf(context);
  }

  private Map<String, Object> semanticPivotReviewContext(
      RouteState route, PivotDelta delta, String proposerAgentId) {
    StrategyBlueprintCompiler.Compilation proposedBlueprint =
        proofControl
            .blueprintCompiler()
            .compile(problemHash, controlStrategy(delta.proposedStrategy(), route.routeId), controlGoal());
    Map<String, Object> context = new LinkedHashMap<>();
    context.put(
        "immutable_root_goal",
        Map.of(
            "source_statement", rootGoal().sourceStatement(),
            "source_statement_hash", rootGoal().sourceStatementHash(),
            "problem_hash", problemHash,
            "editable", false));
    context.put("compiled_pivot_delta", delta);
    context.put("source_structural_signature", pivotSignature(route, null, null));
    context.put("proposed_structural_signature", pivotSignature(route, delta, proposedBlueprint));
    context.put("trusted_authority_projection", pivotAuthority(route, delta));
    context.put("proposer_agent_id", proposerAgentId);
    context.put(
        "review_rule",
        "Review coherence and authority boundaries only. Return exactly one decision for the "
            + "supplied pivot and do not verify any new mathematical claim.");
    return Map.copyOf(context);
  }

  private SemanticPivotProposal bindSemanticPivotProposal(
      SemanticPivotProposal source, AgentRuntime proposer, RouteState route) {
    if (!problemHash.equals(source.problemHash())
        || !rootGoal().sourceStatementHash().equals(source.rootGoalHash())
        || !route.routeId.equals(source.routeId())
        || !route.strategy.strategyId().equals(source.sourceStrategyId())) {
      throw new PivotCompilationException(
          "PIVOT_IDENTITY_MISMATCH",
          "provider proposal changed problem, root-goal, route, or source-strategy identity");
    }
    return new SemanticPivotProposal(
        source.proposalId(),
        proposer.id(),
        source.problemHash(),
        source.rootGoalHash(),
        source.routeId(),
        source.sourceStrategyId(),
        source.transformationTypes(),
        source.obstructionIds(),
        source.objectChanges(),
        source.directionChanges(),
        source.assumptionChanges(),
        source.claimUseChanges(),
        source.obligationChanges(),
        source.proposedStrategy(),
        source.rationale(),
        source.claimedPivotId(),
        source.claimedStructuralDeltaHash());
  }

  private static boolean semanticPivotMechanismRequested(List<String> mechanisms) {
    Set<String> eligible =
        Set.of(
            InspirationMechanism.REPRESENTATION_SWITCH.value(),
            InspirationMechanism.REVERSE_GOAL_ANALYSIS.value(),
            InspirationMechanism.AUXILIARY_CONSTRUCTION.value(),
            InspirationMechanism.META_REPLAN.value(),
            InspirationMechanism.INSPIRATION_COMPOSITION.value(),
            MetaDirectiveAction.SWITCH_REPRESENTATION.value(),
            MetaDirectiveAction.REWRITE_PLAN.value());
    return mechanisms.stream().anyMatch(eligible::contains);
  }

  private List<String> semanticPivotExternalGateFailures(
      RouteState route,
      PivotDelta delta,
      StrategyBlueprintCompiler.Compilation blueprint,
      ProofControlModels.GoalLink goalLink) {
    LinkedHashSet<String> failures = new LinkedHashSet<>();
    if (route.checkpoint == null) {
      failures.add("MISSING_SOURCE_CHECKPOINT");
    }
    if (strategyArchive.lineage().containsKey(delta.proposedStrategyId())) {
      failures.add("STRATEGY_EPOCH_ID_CONFLICT");
    }
    List<NegativeKnowledgeCandidate> negativeCandidates =
        new ArrayList<>(
            negativeKnowledgeCandidates(
                delta.proposedStrategy(), blueprint, NegativeKnowledgeSurface.ROUTE_REVISION));
    delta.obligationChanges().stream()
        .filter(change -> change.action() == PivotObligationAction.ADD_NEW_OBLIGATION)
        .map(PivotObligationChange::proposedStatement)
        .forEach(
            statement -> {
              for (NegativeKnowledgeTargetType targetType : NegativeKnowledgeTargetType.values()) {
                negativeCandidates.add(
                    negativeKnowledgeCandidate(
                        statement,
                        targetType,
                        NegativeKnowledgeSurface.PROOF_OBLIGATION_CREATION,
                        NegativeCandidateIntent.PROOF_TARGET));
              }
            });
    negativeKnowledgeGate.evaluateAll(negativeCandidates, roundIndex.get()).stream()
        .filter(decision -> !decision.allowed())
        .forEach(ignored -> failures.add("PERMANENT_NEGATIVE_CONFLICT"));

    ProofControlModels.Strategy control = controlStrategy(delta.proposedStrategy(), route.routeId);
    boolean duplicate =
        routes.stream()
            .filter(candidate -> candidate != route)
            .map(candidate -> topology.mathNormalize(topology.strategyText(candidate.strategy)))
            .anyMatch(topology.mathNormalize(topology.strategyText(delta.proposedStrategy()))::equals);
    boolean commonMode =
        routes.stream()
            .filter(candidate -> candidate != route)
            .anyMatch(
                candidate ->
                    topology.sharesUnverifiedDependency(
                        delta.proposedStrategy(),
                        candidate.strategy,
                        Math.max(0.82d, config.topology().strategySimilarityThreshold())));
    var admission =
        proofControl
            .routeAdmission()
            .evaluate(proofControlMode(), control, blueprint, goalLink, duplicate, commonMode);
    if (!"accepted".equals(blueprint.blueprint().status())
        || admission.blocksRuntime(proofControlMode())) {
      failures.add("GOAL_OR_ROUTE_ADMISSION_BLOCK");
    }
    for (PivotObligationChange change : delta.obligationChanges()) {
      if (change.action() != PivotObligationAction.ADD_NEW_OBLIGATION) {
        continue;
      }
      if (findObligation(change.obligationId()).isPresent()) {
        failures.add("OBLIGATION_ID_CONFLICT");
        continue;
      }
      ProofObligation obligation = pivotObligation(route, delta, change);
      ObligationCreationContext context = pivotObligationContext(route, delta, change);
      Optional<String> existing = proofGraph.existingCanonicalTargetId(obligation, context);
      String canonicalTargetId = existing.orElse("");
      String familyId =
          existing
              .flatMap(proofGraph::bottleneckFamilyForCanonical)
              .map(BottleneckFamilyRecord::familyId)
              .orElse("");
      FocusedExpansionDecision decision =
          proofGraphConvergence.decideExpansion(
              FocusedRecoveryActionType.FAMILY_BRIDGE_REPAIR,
              existing.isPresent(),
              proofGraph.activeCanonicalTargetCount(route.routeId),
              proofGraph.activeCanonicalTargetCount(),
              familyId,
              canonicalTargetId);
      if (!decision.allowed()) {
        failures.add(
            decision.code().contains("CAPACITY")
                ? "CAPACITY_OR_QUOTA_BLOCK"
                : "FOCUSED_RECOVERY_BINDING_MISMATCH");
      }
    }
    return List.copyOf(failures);
  }

  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification = "Every staged projection is restored before the injected or runtime failure is propagated.")
  private SemanticPivotApplyReceipt applySemanticPivotAtomically(
      RouteState route,
      SemanticPivotApplyPlan plan,
      StrategyBlueprintCompiler.Compilation blueprint,
      ProofControlModels.GoalLink goalLink,
      SemanticPivotSnapshot pivotBeforeApply) {
    PivotRouteSnapshot routeBefore = PivotRouteSnapshot.capture(route);
    StrategyArchive.Snapshot archiveBefore = strategyArchive.snapshot();
    ProofGraphSnapshot graphBefore = proofGraph.snapshot();
    var convergenceBefore = proofGraphConvergence.snapshot();
    var deferredBefore = deferredExpansions.snapshot();
    ContinuationFunctions.CheckpointLedgerSnapshot checkpointsBefore = checkpoints.snapshot();
    LemmaMemorySnapshot lemmaMemoryBefore = lemmaMemory.snapshot();
    var claimLifecycleBefore = proofControl.claims().snapshot();
    List<DesktopSolveCheckpoint.ScheduledProofTask> tasksBefore =
        List.copyOf(pendingProofTasks);
    List<StrategyCard> admittedBefore = admittedStrategies;
    Map<String, StrategyBlueprintCompiler.Compilation> blueprintsBefore =
        Map.copyOf(strategyBlueprints);
    Map<String, ProofControlModels.GoalLink> goalLinksBefore = Map.copyOf(goalLinks);
    String stageBefore = currentStage;
    boolean checkpointPersistAttempted = false;
    try {
      failSemanticPivotAt(SemanticPivotFailurePoint.AFTER_LEDGER_STAGED);
      PivotDelta delta = plan.delta();
      archiveCurrentAttempt(route, "SEMANTIC_PIVOT");
      strategyArchive.archivePivotEpoch(
          controlStrategy(delta.proposedStrategy(), route.routeId),
          delta.sourceStrategyId(),
          delta.pivotId(),
          roundIndex.get());
      strategyBlueprints.put(delta.proposedStrategyId(), blueprint);
      goalLinks.put(delta.proposedStrategyId(), goalLink);
      admittedStrategies = appendDistinctStrategy(admittedStrategies, delta.proposedStrategy());
      failSemanticPivotAt(SemanticPivotFailurePoint.AFTER_STRATEGY_EPOCH);

      route.strategy = delta.proposedStrategy();
      route.activeStrategyEpochId = delta.proposedStrategyId();
      route.activeSemanticPivotId = delta.pivotId();
      route.semanticPivotIds.add(delta.pivotId());
      applyPivotRouteProjection(route, delta);
      materializePivotProposedClaims(route, delta);
      resetRouteForSemanticPivot(route);
      failSemanticPivotAt(SemanticPivotFailurePoint.AFTER_ROUTE_SWITCH);

      List<String> addedObligations = new ArrayList<>();
      for (PivotObligationChange change : delta.obligationChanges()) {
        if (change.action() != PivotObligationAction.ADD_NEW_OBLIGATION) {
          continue;
        }
        ControlledObligationWrite write =
            addControlledObligation(
                pivotObligation(route, delta, change),
                pivotObligationContext(route, delta, change),
                FocusedRecoveryActionType.FAMILY_BRIDGE_REPAIR);
        if (!write.decision().allowed()) {
          throw new IllegalStateException("pivot obligation control changed after preflight");
        }
        addedObligations.add(change.obligationId());
      }
      if (!addedObligations.isEmpty()) {
        route.focusObligationId = addedObligations.getFirst();
        route.focusedCanonicalTargetId = canonicalTargetId(route.focusObligationId);
        route.focusedBottleneckFamilyId =
            route.focusedCanonicalTargetId == null
                ? ""
                : proofGraph
                    .bottleneckFamilyForCanonical(route.focusedCanonicalTargetId)
                    .map(BottleneckFamilyRecord::familyId)
                    .orElse("");
        route.focusSource = "semantic-pivot:" + delta.pivotId();
      }
      failSemanticPivotAt(SemanticPivotFailurePoint.AFTER_OBLIGATION_CANONICALIZATION);

      List<String> taskIds = new ArrayList<>();
      for (String obligationId : addedObligations) {
        if (enqueueProofTask(
            "semantic-pivot:" + delta.pivotId(), route.routeId, obligationId, "DEEPEN")) {
          pendingProofTasks.stream()
              .filter(task -> task.obligationId().equals(obligationId))
              .map(DesktopSolveCheckpoint.ScheduledProofTask::taskId)
              .findFirst()
              .ifPresent(taskIds::add);
        }
      }
      failSemanticPivotAt(SemanticPivotFailurePoint.AFTER_PENDING_TASK);

      route.checkpoint =
          checkpoints.branchForStrategy(
              routeBefore.checkpoint().checkpointId(),
              route.routeId + "-pivot-" + route.semanticPivotIds.size(),
              delta.proposedStrategyId());
      failSemanticPivotAt(SemanticPivotFailurePoint.AFTER_CHECKPOINT_BRANCH);
      SemanticPivotApplyReceipt receipt =
          SemanticPivotApplyReceipt.applied(
              delta, addedObligations, taskIds, roundIndex.get());
      semanticPivots.ledger().commitApply(receipt);
      // The authoritative state file must contain either the pre-Pivot state or full APPLIED state.
      failSemanticPivotAt(SemanticPivotFailurePoint.BEFORE_APPLIED_CHECKPOINT_PERSIST);
      checkpointPersistAttempted = true;
      persistUnchecked("semantic_pivot_apply", false);
      return receipt;
    } catch (RuntimeException exception) {
      routeBefore.restore(route);
      strategyArchive.restore(archiveBefore);
      proofGraph = ProofGraphStore.restore(graphBefore, ProofGraphPolicy.defaults());
      proofGraphConvergence =
          ProofGraphConvergenceMonitor.restore(
              ProofGraphConvergenceConfig.defaults(), convergenceBefore);
      deferredExpansions = DeferredExpansionLedger.restore(deferredBefore);
      pendingProofTasks.clear();
      pendingProofTasks.addAll(tasksBefore);
      admittedStrategies = admittedBefore;
      strategyBlueprints.clear();
      strategyBlueprints.putAll(blueprintsBefore);
      goalLinks.clear();
      goalLinks.putAll(goalLinksBefore);
      checkpoints.restore(checkpointsBefore);
      lemmaMemory = LemmaMemory.restore(lemmaMemoryBefore);
      proofControl.claims().load(claimLifecycleBefore);
      semanticPivots.ledger().restore(pivotBeforeApply);
      installNegativeKnowledgeRuntime();
      if (checkpointPersistAttempted) {
        try {
          persistUnchecked(stageBefore, false);
        } catch (RuntimeException rollbackFailure) {
          exception.addSuppressed(rollbackFailure);
        }
      } else {
        currentStage = stageBefore;
      }
      throw exception;
    }
  }

  private void applyPivotRouteProjection(RouteState route, PivotDelta delta) {
    for (MathematicalObjectChange change : delta.objectChanges()) {
      switch (change.disposition()) {
        case RETAIN -> {
          // Explicitly retained in the current epoch.
        }
        case RETIRE_FROM_ACTIVE_STRATEGY ->
            route.activeMathematicalObjectIds.remove(change.oldObjectId());
        case REPLACE -> {
          route.activeMathematicalObjectIds.remove(change.oldObjectId());
          route.activeMathematicalObjectIds.add(change.newObjectId());
        }
        case ADD -> route.activeMathematicalObjectIds.add(change.newObjectId());
      }
    }
    delta.directionChanges().stream()
        .map(PivotDirectionChange::newDirectionSignature)
        .reduce((first, second) -> second)
        .ifPresent(value -> route.activeDirectionSignature = value);
    for (var change : delta.claimUseChanges()) {
      if (change.action() == PivotClaimUsageAction.RETIRE_FROM_ACTIVE_DEPENDENCY) {
        route.retiredActiveClaimIds.add(change.claimId());
      } else {
        route.retiredActiveClaimIds.remove(change.claimId());
      }
    }
    for (PivotObligationChange change : delta.obligationChanges()) {
      if (change.action() == PivotObligationAction.RETIRE_FROM_STRATEGY_FOCUS) {
        route.retiredStrategyFocusObligationIds.add(change.obligationId());
      } else {
        route.retiredStrategyFocusObligationIds.remove(change.obligationId());
      }
    }
  }

  private void materializePivotProposedClaims(RouteState route, PivotDelta delta) {
    for (var change : delta.claimUseChanges()) {
      if (change.action() != PivotClaimUsageAction.ADD_AS_PROPOSED_CLAIM) {
        continue;
      }
      PivotProposedClaimDraft draft =
          Objects.requireNonNull(change.proposedClaim(), "audited proposed Claim draft");
      String sourceAttemptId = "semantic-pivot-attempt-" + delta.pivotId();
      List<ProofControlModels.DependencyRef> dependencyRefs =
          draft.dependencyClaimIds().stream()
              .map(
                  dependency ->
                      new ProofControlModels.DependencyRef(
                          ProofControlModels.DependencyKind.LOCAL_CLAIM,
                          dependency,
                          sourceAttemptId,
                          delta.pivotId(),
                          route.routeId,
                          null,
                          null))
              .toList();
      LinkedHashSet<String> tags = new LinkedHashSet<>(draft.tags());
      tags.add("source-semantic-pivot:" + delta.pivotId());
      ClaimCard claim =
          new ClaimCard(
              draft.assumptions(),
              draft.claimId(),
              draft.statement(),
              "",
              "Requires independent issue-003 Claim review.",
              draft.dependencyClaimIds(),
              List.of(),
              draft.proofStepRefs().stream()
                  .map(
                      reference ->
                          new EvidenceRef(
                              reference,
                              null,
                              null,
                              "Support reference supplied by the non-authoritative Pivot draft."))
                  .toList(),
              List.of(),
              List.of(),
              0.5d,
              route.author.id(),
              sourceAttemptId,
              delta.pivotId(),
              draft.statement(),
              ClaimStatus.PROPOSED,
              List.copyOf(tags),
              null);
      lemmaMemory.addMany(List.of(claim));
      proofControl
          .claims()
          .register(
              claim.claimId(),
              sourceAttemptId,
              delta.pivotId(),
              dependencyRefs,
              AttemptArtifactKind.LOCAL_LEMMA,
              AttemptStatus.PARTIAL,
              "semantic-pivot-proposed");
      if (route.pendingPivotProposedClaims.stream()
          .noneMatch(existing -> existing.claimId().equals(claim.claimId()))) {
        route.pendingPivotProposedClaims.add(claim);
      }
    }
  }

  private void resetRouteForSemanticPivot(RouteState route) {
    route.attempt = null;
    route.skepticReview = null;
    route.toolAudit = null;
    route.structuralReview = null;
    route.detailedReview = null;
    route.crossProviderReview = null;
    route.claimReview = null;
    route.teamResult = null;
    route.escalation = null;
    route.validationExecution = null;
    route.delta = null;
    route.deltaId = null;
    route.failure = null;
    route.status = "pending";
    route.failureReason = "";
    route.nearMissId = null;
    route.segmentCount = 0;
    route.noProgressSegments = 0;
    route.reviewComplete = false;
    route.checkpointProcessed = false;
    route.integrated = false;
  }

  private ProofObligation pivotObligation(
      RouteState route, PivotDelta delta, PivotObligationChange change) {
    return new ProofObligation(
        change.assumptions(),
        0.7d,
        "",
        change.dependencyIds(),
        List.of(),
        List.of(),
        null,
        change.proposedKind(),
        topology.mathNormalize(change.proposedStatement()),
        change.obligationId(),
        0.8d,
        problemHash,
        List.of(),
        List.of(route.routeId),
        change.proposedStatement(),
        "open");
  }

  private ObligationCreationContext pivotObligationContext(
      RouteState route, PivotDelta delta, PivotObligationChange change) {
    return new ObligationCreationContext(
        problemHash,
        route.routeId,
        delta.proposedStrategyId(),
        ObligationSourceType.STRATEGY_BLUEPRINT,
        "semantic-pivot://" + delta.pivotId() + "/" + change.obligationId(),
        change.dependencyIds(),
        "",
        Map.of("semantic_pivot_id", delta.pivotId()),
        route.focusedBottleneckFamilyId.isBlank()
            ? topology.mathNormalize(change.proposedStatement())
            : route.focusedBottleneckFamilyId,
        change.reason(),
        BottleneckRelationType.REFINEMENT,
        ObligationOccurrenceSchedulingState.ACTIVE,
        roundIndex.get());
  }

  private PivotStructuralSignature pivotSignature(
      RouteState route,
      PivotDelta delta,
      StrategyBlueprintCompiler.Compilation proposedBlueprint) {
    StrategyCard strategy = delta == null ? route.strategy : delta.proposedStrategy();
    Set<String> objects = new LinkedHashSet<>(route.activeMathematicalObjectIds);
    Set<String> targets =
        proofGraph.activeCanonicalOpenTargets().stream()
            .filter(target -> target.routeIds().contains(route.routeId))
            .map(CanonicalObligationRecord::canonicalTargetId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    Set<String> retainedClaims =
        route.claimIds.stream()
            .filter(id -> !route.retiredActiveClaimIds.contains(id))
            .filter(this::verifiedClaim)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    Set<String> proposedClaims = new LinkedHashSet<>();
    Set<String> obligations =
        proofGraph.obligations().stream()
            .filter(obligation -> obligation.routeIds().contains(route.routeId))
            .filter(obligation -> "open".equals(obligation.status()))
            .filter(
                obligation ->
                    !route.retiredStrategyFocusObligationIds.contains(
                        obligation.obligationId()))
            .map(ProofObligation::statement)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    String direction = route.activeDirectionSignature;
    if (delta != null) {
      for (MathematicalObjectChange change : delta.objectChanges()) {
        if (change.disposition() == PivotObjectDisposition.RETIRE_FROM_ACTIVE_STRATEGY) {
          objects.remove(change.oldObjectId());
        } else if (change.disposition() == PivotObjectDisposition.REPLACE) {
          objects.remove(change.oldObjectId());
          objects.add(change.newObjectId());
        } else if (change.disposition() == PivotObjectDisposition.ADD) {
          objects.add(change.newObjectId());
        }
      }
      for (var change : delta.claimUseChanges()) {
        switch (change.action()) {
          case RETAIN_AS_VERIFIED_FACT -> retainedClaims.add(change.claimId());
          case RETIRE_FROM_ACTIVE_DEPENDENCY -> retainedClaims.remove(change.claimId());
          case ADD_AS_PROPOSED_CLAIM -> proposedClaims.add(change.claimStatementHash());
        }
      }
      for (PivotObligationChange change : delta.obligationChanges()) {
        if (change.action() == PivotObligationAction.RETIRE_FROM_STRATEGY_FOCUS) {
          findObligation(change.obligationId())
              .map(ProofObligation::statement)
              .ifPresent(obligations::remove);
          if (change.canonicalTargetId() != null) {
            targets.remove(change.canonicalTargetId());
          }
        } else if (change.action() == PivotObligationAction.ADD_NEW_OBLIGATION) {
          obligations.add(change.proposedStatement());
          targets.add(
              change.canonicalTargetId() == null
                  ? "proposed-target-"
                      + CanonicalJson.stableHash(
                              ProofIdentity.normalizeText(change.proposedStatement()))
                          .substring(0, 16)
                  : change.canonicalTargetId());
        }
      }
      direction =
          delta.directionChanges().stream()
              .map(PivotDirectionChange::newDirectionSignature)
              .reduce((first, second) -> second)
              .orElse(direction);
    }
    Set<String> assumptions =
        strategy.prerequisites().stream()
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    return pivotSignatures.create(
        strategy,
        objects,
        targets,
        assumptions,
        retainedClaims,
        proposedClaims,
        obligations,
        direction,
        delta == null
            ? strategyBlueprints.get(strategy.strategyId())
            : proposedBlueprint);
  }

  private PivotAuthorityContext pivotAuthority(RouteState route, PivotDelta delta) {
    Map<String, PivotObstructionRef> references = pivotObstructionReferences(route);
    Map<String, PivotAuthorityContext.KnownObstruction> known = new LinkedHashMap<>();
    references.forEach(
        (id, reference) ->
            known.put(
                id,
                new PivotAuthorityContext.KnownObstruction(reference, problemHash, true)));
    Set<String> activeTargets =
        proofGraph.activeCanonicalOpenTargets().stream()
            .filter(target -> target.routeIds().contains(route.routeId))
            .map(CanonicalObligationRecord::canonicalTargetId)
            .collect(java.util.stream.Collectors.toSet());
    Set<String> knownObligations =
        proofGraph.obligations().stream()
            .map(ProofObligation::obligationId)
            .collect(java.util.stream.Collectors.toSet());
    Map<String, String> knownClaimStatementHashes = new LinkedHashMap<>();
    typedMemory
        .facts()
        .forEach(
            fact ->
                putAuthoritativeClaimStatementHash(
                    knownClaimStatementHashes, fact.messageId(), fact.statement()));
    lemmaMemory
        .claims()
        .forEach(
            claim ->
                putAuthoritativeClaimStatementHash(
                    knownClaimStatementHashes, claim.claimId(), claim.statement()));
    Set<String> knownClaims =
        java.util.stream.Stream.concat(
                proofControl.claims().entries().stream().map(entry -> entry.claimId()),
                knownClaimStatementHashes.keySet().stream())
            .collect(java.util.stream.Collectors.toSet());
    Set<String> verifiedClaims =
        proofControl.claims().entries().stream()
            .filter(
                entry ->
                    entry.state()
                        == io.github.aililuola.mathproofmesh.proofcontrol.ClaimLifecycleController.State.EXTERNALLY_ADMITTED_FACT)
            .map(entry -> entry.claimId())
            .collect(java.util.stream.Collectors.toSet());
    LinkedHashSet<String> negativeConflicts = new LinkedHashSet<>();
    List<NegativeKnowledgeCandidate> pivotCandidates =
        new ArrayList<>(
            negativeKnowledgeCandidates(
                delta.proposedStrategy(),
                proofControl
                    .blueprintCompiler()
                    .compile(
                        problemHash,
                        controlStrategy(delta.proposedStrategy(), route.routeId),
                        controlGoal()),
                NegativeKnowledgeSurface.ROUTE_REVISION));
    delta.obligationChanges().stream()
        .filter(change -> change.action() == PivotObligationAction.ADD_NEW_OBLIGATION)
        .map(PivotObligationChange::proposedStatement)
        .forEach(
            statement -> {
              for (NegativeKnowledgeTargetType targetType : NegativeKnowledgeTargetType.values()) {
                pivotCandidates.add(
                    negativeKnowledgeCandidate(
                        statement,
                        targetType,
                        NegativeKnowledgeSurface.PROOF_OBLIGATION_CREATION,
                        NegativeCandidateIntent.PROOF_TARGET));
              }
            });
    negativeKnowledgeGate
        .evaluateAll(pivotCandidates, roundIndex.get())
        .stream()
        .filter(decision -> !decision.allowed())
        .flatMap(decision -> decision.matchedNegativeIds().stream())
        .forEach(negativeConflicts::add);
    FocusedRecoveryPlan focused = proofGraphConvergence.focusedRecoveryPlan().orElse(null);
    boolean capacityAvailable =
        delta.obligationChanges().stream()
            .filter(change -> change.action() == PivotObligationAction.ADD_NEW_OBLIGATION)
            .allMatch(
                change -> {
                  ProofObligation obligation = pivotObligation(route, delta, change);
                  ObligationCreationContext context = pivotObligationContext(route, delta, change);
                  Optional<String> existing = proofGraph.existingCanonicalTargetId(obligation, context);
                  String canonicalTargetId = existing.orElse("");
                  String familyId =
                      existing
                          .flatMap(proofGraph::bottleneckFamilyForCanonical)
                          .map(BottleneckFamilyRecord::familyId)
                          .orElse("");
                  return proofGraphConvergence
                      .decideExpansion(
                          FocusedRecoveryActionType.FAMILY_BRIDGE_REPAIR,
                          existing.isPresent(),
                          proofGraph.activeCanonicalTargetCount(route.routeId),
                          proofGraph.activeCanonicalTargetCount(),
                          familyId,
                          canonicalTargetId)
                      .allowed();
                });
    return new PivotAuthorityContext(
        problemHash,
        rootGoal().sourceStatementHash(),
        route.routeId,
        route.strategy.strategyId(),
        known,
        route.activeMathematicalObjectIds,
        activeTargets,
        knownObligations,
        verifiedClaims,
        knownClaims,
        knownClaimStatementHashes,
        negativeConflicts,
        focused == null ? null : focused.selectedFamilyId(),
        focused == null ? Set.of() : focused.selectedCanonicalTargetIds(),
        proofGraphConvergence.controlMode() == ProofGraphControlMode.FOCUSED_RECOVERY,
        capacityAvailable);
  }

  private static void putAuthoritativeClaimStatementHash(
      Map<String, String> hashes, String claimId, String statement) {
    String statementHash = PivotProposedClaimDraft.statementHash(statement);
    hashes.merge(
        claimId,
        statementHash,
        (left, right) -> PivotProposedClaimDraft.hashesEqual(left, right) ? left : "");
  }

  private Map<String, PivotObstructionRef> pivotObstructionReferences(RouteState route) {
    Map<String, PivotObstructionRef> references = new LinkedHashMap<>();
    if (route.failure != null) {
      String statement =
          route.failure.firstErrorFingerprint() == null
              ? route.failureReason
              : route.failure.firstErrorFingerprint();
      putPivotObstruction(
          references,
          route,
          route.failure.id(),
          PivotEvidenceAuthority.FAILURE_FINGERPRINT,
          "failure://" + route.failure.id(),
          route.focusedCanonicalTargetId,
          statement);
    }
    attemptArtifacts.records().stream()
        .filter(record -> record.routeId().equals(route.routeId))
        .filter(record -> record.status() == AttemptArtifactStatus.APPLIED_COUNTEREXAMPLE)
        .forEach(
            record ->
                putPivotObstruction(
                    references,
                    route,
                    record.artifactId(),
                    PivotEvidenceAuthority.VERIFIED_COUNTEREXAMPLE,
                    "attempt-artifact://" + record.artifactId(),
                    canonicalTargetId(record.targetObligationId()),
                    record.statement()));
    researchCheckpointLedger().activeFindings(route.routeId).stream()
        .filter(record -> record.kind() == ResearchFindingKind.SHARP_OBSTRUCTION)
        .forEach(
            record ->
                putPivotObstruction(
                    references,
                    route,
                    record.findingId(),
                    PivotEvidenceAuthority.SHARP_OBSTRUCTION_CANDIDATE,
                    "research-finding://" + record.findingId(),
                    canonicalTargetId(record.targetObligationId()),
                    record.statement()));
    proofGraph.obligations().stream()
        .filter(obligation -> obligation.routeIds().contains(route.routeId))
        .filter(obligation -> "refuted".equals(obligation.status()))
        .forEach(
            obligation ->
                putPivotObstruction(
                    references,
                    route,
                    obligation.obligationId(),
                    PivotEvidenceAuthority.EXACT_REFUTED_OBLIGATION,
                    "obligation://" + obligation.obligationId(),
                    canonicalTargetId(obligation.obligationId()),
                    obligation.statement()));
    negativeKnowledgeRegistry.records().stream()
        .filter(NegativeKnowledgeRecord::permanent)
        .filter(record -> record.problemHash().equals(problemHash))
        .forEach(
            record ->
                putPivotObstruction(
                    references,
                    route,
                    record.negativeId(),
                    PivotEvidenceAuthority.PERMANENT_NEGATIVE,
                    "negative-knowledge://" + record.negativeId(),
                    route.focusedCanonicalTargetId,
                    record.statement()));
    proofGraph.allBottleneckFamilies().stream()
        .filter(
            family ->
                family.canonicalTargetIds().contains(route.focusedCanonicalTargetId)
                    || family.familyId().equals(route.focusedBottleneckFamilyId))
        .forEach(
            family ->
                putPivotObstruction(
                    references,
                    route,
                    family.familyId(),
                    PivotEvidenceAuthority.BOTTLENECK_FAMILY,
                    "bottleneck-family://" + family.familyId(),
                    family.representativeCanonicalTargetId(),
                    family.label()));
    return Map.copyOf(references);
  }

  private void putPivotObstruction(
      Map<String, PivotObstructionRef> target,
      RouteState route,
      String id,
      PivotEvidenceAuthority authority,
      String sourceRef,
      String canonicalTargetId,
      String statement) {
    if (id == null || id.isBlank() || statement == null || statement.isBlank()) {
      return;
    }
    target.putIfAbsent(
        id,
        new PivotObstructionRef(
            id,
            authority,
            sourceRef,
            route.routeId,
            route.strategy.strategyId(),
            canonicalTargetId,
            CanonicalJson.stableHash(ProofIdentity.normalizeText(statement))));
  }

  private String canonicalTargetId(String obligationId) {
    if (obligationId == null || obligationId.isBlank()) {
      return null;
    }
    return proofGraph
        .canonicalTargetForObligation(obligationId)
        .map(CanonicalObligationRecord::canonicalTargetId)
        .orElse(null);
  }

  private Set<String> strategyCanonicalTargetIds() {
    String canonicalRoot = canonicalTargetId(MAIN_GOAL_ID);
    return canonicalRoot == null || canonicalRoot.isBlank()
        ? Set.of("main-goal:" + rootGoal().sourceStatementHash())
        : Set.of(canonicalRoot);
  }

  private boolean verifiedClaim(String claimId) {
    return proofControl.claims().entries().stream()
        .anyMatch(
            entry ->
                entry.claimId().equals(claimId)
                    && entry.state()
                        == io.github.aililuola.mathproofmesh.proofcontrol.ClaimLifecycleController.State.EXTERNALLY_ADMITTED_FACT);
  }

  private ProofControlModels.GoalLink pivotGoalLink(StrategyCard strategy, String routeId) {
    ProofControlModels.ScopeSignature scope =
        proofControl.scopeGuard().extract("goal-scope", rootGoal().sourceStatement(), List.of(), 1.0d);
    return proofControl
        .goalAlignment()
        .assess(
            strategy.strategyId(),
            rootGoal().sourceStatement(),
            scope,
            controlGoal(),
            scope,
            proofControl.scopeGuard(),
            String::equals);
  }

  private RouteState requirePivotRoute(String routeId) {
    return routes.stream()
        .filter(route -> route.routeId.equals(routeId))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown semantic pivot route: " + routeId));
  }

  private static List<StrategyCard> appendDistinctStrategy(
      List<StrategyCard> strategies, StrategyCard strategy) {
    if (strategies.stream().anyMatch(value -> value.strategyId().equals(strategy.strategyId()))) {
      return strategies;
    }
    return java.util.stream.Stream.concat(strategies.stream(), java.util.stream.Stream.of(strategy))
        .toList();
  }

  private void failSemanticPivotAt(SemanticPivotFailurePoint point) {
    if (semanticPivotHardCrashPoint == point) {
      semanticPivotHardCrashPoint = SemanticPivotFailurePoint.NONE;
      throw new SimulatedSemanticPivotProcessTermination(point);
    }
    if (semanticPivotFailurePoint == point) {
      semanticPivotFailurePoint = SemanticPivotFailurePoint.NONE;
      throw new IllegalStateException("injected semantic pivot failure: " + point);
    }
  }

  void setSemanticPivotFailurePointForTest(SemanticPivotFailurePoint point) {
    semanticPivotFailurePoint = point == null ? SemanticPivotFailurePoint.NONE : point;
  }

  void setSemanticPivotHardCrashPointForTest(SemanticPivotFailurePoint point) {
    semanticPivotHardCrashPoint = point == null ? SemanticPivotFailurePoint.NONE : point;
  }

  private void runInspiration(InspirationSnapshot requestedSnapshot) {
    stage(
        RoutePipelineFunctions.RunStage.INSPIRATION,
        "Detecting stalls and running independently reviewed inspiration mechanisms");
    if (proofGraphConvergence.controlMode() == ProofGraphControlMode.FOCUSED_RECOVERY
        && inspirationProgress == null) {
      proofGraphConvergence.recordGenericExpansionAttempt(false);
      enqueueFocusedRecoveryTask();
      event(
          "generic_inspiration_deferred_by_graph_control",
          "scheduler_inspiration",
          null,
          "rejected",
          "Focused recovery admits only actions bound to the selected family or canonical target",
          proofGraphConvergence
              .focusedRecoveryPlan()
              .map(FocusedRecoveryPlan::episodeId)
              .orElse("focused-recovery"));
      return;
    }
    InspirationSnapshot snapshot = requestedSnapshot;
    Map<String, InspirationTriggerType> triggerTypes;
    List<InspirationTask> boundedTasks;
    MetaPivotController.Pivot pivot;
    int proposalCountBefore;
    int verifiedFactsBefore;
    int closedObligationsBefore;
    double pivotDebtBefore;

    if (inspirationProgress != null) {
      if (inspirationProgress.roundIndex() != roundIndex.get()) {
        throw new IllegalStateException("inspiration checkpoint belongs to another scheduler round");
      }
      snapshot = inspirationProgress.snapshot();
      triggerTypes = inspirationProgress.triggerTypes();
      boundedTasks = inspirationProgress.tasks();
      pivot = restoreInspirationPivot(inspirationProgress.pivot());
      proposalCountBefore = inspirationProgress.proposalCountBefore();
      verifiedFactsBefore = inspirationProgress.verifiedFactsBefore();
      closedObligationsBefore = inspirationProgress.closedObligationsBefore();
      pivotDebtBefore = inspirationProgress.proofDebtBefore();
      if (proposalCountBefore > inspirationProposals.size()) {
        throw new IllegalStateException("inspiration checkpoint proposal boundary is invalid");
      }
      event(
          "inspiration_checkpoint_resumed",
          "inspiration",
          null,
          "completed",
          "Resumed the exact unfinished inspiration task and proposal-slot frontier",
          "inspiration-round-" + roundIndex.get());
    } else {
      List<InspirationTrigger> triggers = inspirationTriggers.detect(snapshot);
      triggerTypes = new LinkedHashMap<>();
      triggers.forEach(trigger -> triggerTypes.put(trigger.triggerId(), trigger.triggerType()));
      Map<InspirationMechanism, Integer> selectionCounts =
          new EnumMap<>(InspirationMechanism.class);
      inspirationProposals.forEach(
          proposal -> selectionCounts.merge(proposal.mechanism(), 1, Integer::sum));
      Map<String, InspirationOutcomeLedger.SelectionProfile> profiles = new LinkedHashMap<>();
      for (InspirationTrigger trigger : triggers) {
        profiles.putAll(
            inspirationLedger.selectionProfiles(
                trigger.triggerType(),
                snapshot.domain(),
                new LinkedHashSet<>(inspirationRegistry.enabledSchedulable())));
      }
      List<InspirationTask> tasks =
          new ArrayList<>(
              inspirationTriggers.schedule(triggers, snapshot, selectionCounts, profiles));

      var metaDecision = metaStrategist.decide(snapshot);
      var directive = metaDirectives.fromDecision(metaDecision, snapshot);
      var directiveAudit = metaDirectives.audit(directive, snapshot);
      String directiveTrigger =
          triggers.isEmpty()
              ? "meta-trigger-r" + roundIndex.get()
              : triggers.getFirst().triggerId();
      var directiveExecution =
          metaDirectives.execute(directive, directiveAudit, snapshot, directiveTrigger);
      if (directive.action() == MetaDirectiveAction.COOLDOWN_ROUTE
          || directive.action() == MetaDirectiveAction.ABANDON_ROUTE) {
        applyMetaRouteControls(directiveExecution.execution().affectedRouteIds());
      }
      tasks.addAll(directiveExecution.generatedTasks());
      event(
          "meta_directive_audited",
          "inspiration",
          null,
          directiveAudit.accepted() ? "completed" : "rejected",
          directive.action().value() + ": " + directiveAudit.reason(),
          directive.directiveId());

      pivot = null;
      if (directiveAudit.accepted() && directiveExecution.businessMutation()) {
        List<String> mechanisms =
            directiveExecution.generatedTasks().stream()
                .map(task -> task.mechanism().value())
                .distinct()
                .toList();
        if (mechanisms.isEmpty()) {
          mechanisms = List.of(directive.action().value());
        }
        String targetRoute =
            directive.routeIds().stream().filter(routeRegistry::exists).findFirst().orElse("run");
        pivot = proofControl.metaPivot().request(targetRoute, roundIndex.get(), mechanisms);
      }

      boundedTasks =
          tasks.stream()
              .distinct()
              .filter(
                  task -> {
                    boolean coolingDown =
                        inspirationLedger.inNoGainCooldown(
                            task.mechanism(),
                            roundIndex.get(),
                            INSPIRATION_NO_GAIN_ROUNDS,
                            INSPIRATION_COOLDOWN_ROUNDS);
                    if (coolingDown) {
                      event(
                          "inspiration_mechanism_cooldown",
                          "inspiration",
                          null,
                          "warning",
                          task.mechanism().value()
                              + " produced no verified gain in consecutive rounds",
                          task.taskId());
                    }
                    return !coolingDown;
                  })
              .limit(config.scheduler().maxActionsPerRound())
              .toList();
      proposalCountBefore = inspirationProposals.size();
      verifiedFactsBefore = typedMemory.facts().size();
      closedObligationsBefore = closedObligationCount();
      pivotDebtBefore = totalProofDebt();
      inspirationProgress =
          new DesktopSolveCheckpoint.InspirationRoundProgress(
              roundIndex.get(),
              snapshot,
              boundedTasks,
              Map.of(),
              triggerTypes,
              List.of(),
              Map.of(),
              pivot,
              proposalCountBefore,
              verifiedFactsBefore,
              closedObligationsBefore,
              pivotDebtBefore);
      persistInspirationProgress();
    }

    for (InspirationTask task : boundedTasks) {
      if (inspirationProgress.taskCompleted(task.taskId())) {
        event(
            "inspiration_task_checkpoint_skipped",
            "inspiration",
            null,
            "completed",
            "Skipped a task already committed by the current inspiration round",
            task.taskId());
        continue;
      }
      executeInspirationTask(task, snapshot, triggerTypes);
    }
    if (pivot != null) {
      List<InspirationProposal> produced =
          inspirationProposals.subList(proposalCountBefore, inspirationProposals.size());
      List<String> completedMechanisms =
          produced.stream().map(proposal -> proposal.mechanism().value()).distinct().toList();
      SemanticPivotRecord semanticPivot = runSemanticPivotCycle(pivot, produced);
      MetaPivotController.Pivot evaluated;
      if (semanticPivot != null && semanticPivot.applyReceipt() != null) {
        proofControl
            .metaPivot()
            .admit(
                pivot.pivotId(),
                true,
                "semantic-pivot-receipt://" + semanticPivot.applyReceipt().receiptId());
        proofControl
            .metaPivot()
            .execute(
                pivot.pivotId(),
                completedMechanisms,
                semanticPivot.applyReceipt(),
                List.of(),
                "independently reviewed semantic state delta was atomically applied");
        MetaPivotController.GainEvidence gain =
            new MetaPivotController.GainEvidence(
                Math.max(0, typedMemory.facts().size() - verifiedFactsBefore),
                Math.max(0, closedObligationCount() - closedObligationsBefore),
                0,
                pivotDebtBefore,
                totalProofDebt());
        evaluated = proofControl.metaPivot().evaluate(pivot.pivotId(), true, gain);
        semanticPivots
            .ledger()
            .evaluate(
                semanticPivot.pivotId(),
                evaluated.outcome().effect(),
                evaluated.outcome().reason());
      } else {
        evaluated =
            proofControl
                .metaPivot()
                .recordProposal(
                    pivot.pivotId(),
                    completedMechanisms,
                    produced.stream().map(InspirationProposal::proposalId).toList(),
                    produced.isEmpty()
                        ? "audited intent produced no proposal"
                        : "proposal material did not pass typed semantic delta review and apply");
      }
      metaPivots.removeIf(existing -> existing.pivotId().equals(evaluated.pivotId()));
      metaPivots.add(evaluated);
      event(
          "meta_pivot_evaluated",
          "inspiration",
          null,
          evaluated.outcome().effect() == ProofControlModels.MetaPivotEffect.EFFECTIVE
              ? "completed"
              : "warning",
          evaluated.outcome().reason(),
          evaluated.pivotId());
    }
    inspirationOutcomes.clear();
    inspirationOutcomes.addAll(inspirationLedger.snapshot().values());
    inspirationProgress = null;
    complete(RoutePipelineFunctions.RunStage.INSPIRATION);
  }

  private MetaPivotController.Pivot restoreInspirationPivot(
      MetaPivotController.Pivot persisted) {
    if (persisted == null) {
      return null;
    }
    MetaPivotController.Pivot restored =
        proofControl
            .metaPivot()
            .request(persisted.routeId(), persisted.round(), persisted.requestedMechanisms());
    if (persisted.outcome() == null) {
      return restored;
    }
    return proofControl
        .metaPivot()
        .recordProposal(
            restored.pivotId(),
            persisted.outcome().completedMechanisms(),
            persisted.outcome().materialStateRefs(),
            persisted.outcome().reason());
  }

  private void persistInspirationProgress() {
    inspirationOutcomes.clear();
    inspirationOutcomes.addAll(inspirationLedger.snapshot().values());
    persistUnchecked("inspiration_progress", false);
  }

  private void applyMetaRouteControls(List<String> affectedRouteIds) {
    for (String routeId : affectedRouteIds) {
      MetaDirectiveController.RouteControl control = metaDirectives.route(routeId);
      if (control == null) {
        continue;
      }
      routes.stream()
          .filter(route -> route.routeId.equals(routeId))
          .findFirst()
          .ifPresent(
              route -> {
                route.cooldownUntilRound = control.cooldownUntilRound();
                route.metaAbandoned = control.abandoned();
                route.metaControlReason = control.reason();
                if (control.abandoned() && !"verified".equals(route.status)) {
                  route.status = "abandoned";
                  route.failureReason = control.reason();
                }
                event(
                    control.abandoned() ? "meta_route_abandoned" : "meta_route_cooled_down",
                    "inspiration",
                    route.author.id(),
                    "completed",
                    control.reason(),
                    route.routeId);
              });
    }
  }

  private boolean routeEligibleForWork(RouteState route) {
    return !route.metaAbandoned
        && (route.cooldownUntilRound < 0 || roundIndex.get() >= route.cooldownUntilRound);
  }

  private boolean routeMatchesFocusedRecovery(RouteState route) {
    return proofGraphConvergence
        .focusedRecoveryPlan()
        .map(
            plan ->
                plan.selects(
                        route.focusedBottleneckFamilyId,
                        route.focusedCanonicalTargetId)
                    || proofGraph.allCanonicalTargets().stream()
                        .filter(
                            target ->
                                plan.selectedCanonicalTargetIds()
                                    .contains(target.canonicalTargetId()))
                        .anyMatch(target -> target.routeIds().contains(route.routeId)))
        .orElse(false);
  }

  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification =
          "Cancellation, global budget, and circuit failures must retain their typed run-level "
              + "semantics after optional proposal failures are isolated and audited.")
  private void executeInspirationTask(
      InspirationTask task,
      InspirationSnapshot snapshot,
      Map<String, InspirationTriggerType> triggerTypes) {
    InspirationAssignmentPlan planned = inspirationProgress.assignmentPlans().get(task.taskId());
    if (planned == null) {
      planned =
          inspirationAssignments.plan(
              task,
              "inspiration_proposer",
              inspirationAgentCandidates(),
              roundIndex.get(),
              List.of(task.mechanism().value()),
              true,
              Math.min(
                  config.topology().inspiration().activeProposalsPerTask(),
                  task.maxProposals()));
      inspirationProgress = inspirationProgress.withAssignmentPlan(planned);
      persistInspirationProgress();
    }
    InspirationAssignmentPlan plan = remainingInspirationAssignments(planned, inspirationProgress);
    if (plan.assignments().isEmpty()) {
      event(
          planned.assignments().isEmpty()
              ? "inspiration_assignment_skipped"
              : "inspiration_assignment_checkpoint_skipped",
          "inspiration",
          null,
          planned.assignments().isEmpty() ? "warning" : "completed",
          planned.assignments().isEmpty()
              ? plan.deferredReason()
              : "All proposal slots for this task were already committed",
          task.taskId());
      markInspirationTaskCompleted(task.taskId());
      return;
    }
    io.github.aililuola.mathproofmesh.contract.InspirationCallReservation reservation;
    try {
      reservation = inspirationEngine.reserveCycle(task, plan, snapshot, 0, 1);
    } catch (IllegalStateException insufficientBudget) {
      event(
          "inspiration_budget_rejected",
          "inspiration",
          null,
          "rejected",
          insufficientBudget.getMessage(),
          task.taskId());
      markInspirationTaskCompleted(task.taskId());
      return;
    }
    int callsBefore = safeInt(ledger.totals().calls());
    boolean interrupted = false;
    try {
      for (InspirationProposalAssignment assignment : plan.assignments()) {
        AgentRuntime proposer = requireAgent(assignment.proposerAgentId());
        AgentRuntime reviewer =
            pool.selectReviewer(
                "detailed_verifier", proposer.id(), List.of(task.mechanism().value()));
        InspirationEngine.ExecutionResult result;
        try {
          result =
              inspirationEngine.execute(
                  task,
                  assignment,
                  snapshot,
                  inspirationProposals.stream()
                      .map(InspirationProposal::noveltySignature)
                      .toList(),
                  proofGraph.obligations().stream()
                      .filter(obligation -> !"closed".equals(obligation.status()))
                      .map(ProofObligation::obligationId)
                      .collect(java.util.stream.Collectors.toSet()),
                  reviewer.id(),
                  typedMemory.facts().stream().map(MessageEnvelope::statement).toList(),
                  typedMemory.negatives().stream().map(MessageEnvelope::statement).toList(),
                  (promptContext, activeAssignment) ->
                      generateInspirationProposal(
                          task, activeAssignment, proposer, promptContext));
        } catch (RuntimeException failure) {
          if (!isRecoverableInspirationAgentFailure(failure)) {
            throw failure;
          }
          event(
              "inspiration_agent_stage_failed",
              "inspiration",
              proposer.id(),
              "warning",
              inspirationFailureSummary(failure),
              task.taskId() + ":slot:" + assignment.proposalSlot());
          markInspirationProposalSlotCompleted(task.taskId(), assignment.proposalSlot());
          continue;
        }
        if (result.proposal() == null) {
          markInspirationProposalSlotCompleted(task.taskId(), assignment.proposalSlot());
          continue;
        }
        InspirationProposal proposal = result.proposal();
        inspirationProposals.add(proposal);
        double debtBefore = totalProofDebt();
        InspirationTriggerType triggerType =
            triggerTypes.getOrDefault(task.triggerId(), InspirationTriggerType.MANUAL);
        inspirationLedger.register(
            proposal,
            snapshot,
            triggerType,
            List.of(ObligationKind.SUBGOAL),
            debtBefore,
            proposal.targetRouteIds(),
            proposal.generatedObligations());
        inspirationLedger.recordUsage(proposal.proposalId(), "proposer", 1, 0);
        inspirationLedger.recordMaterialization(
            proposal.proposalId(), result.materialization().action(), false);
        materializeInspiration(result);
        event(
            "inspiration_" + result.materialization().action(),
            "inspiration",
            proposer.id(),
            result.businessMutation() ? "completed" : "rejected",
            result.reason(),
            proposal.proposalId());
        markInspirationProposalSlotCompleted(task.taskId(), assignment.proposalSlot());
      }
    } catch (RuntimeException failure) {
      interrupted = true;
      throw failure;
    } finally {
      int calls = Math.max(0, safeInt(ledger.totals().calls()) - callsBefore);
      inspirationEngine.reconcileReservation(
          reservation.reservationId(), Map.of("proposer", calls), interrupted);
    }
    markInspirationTaskCompleted(task.taskId());
  }

  static InspirationAssignmentPlan remainingInspirationAssignments(
      InspirationAssignmentPlan plan,
      DesktopSolveCheckpoint.InspirationRoundProgress progress) {
    if (progress == null) {
      return plan;
    }
    List<InspirationProposalAssignment> remaining =
        plan.assignments().stream()
            .filter(
                assignment ->
                    !progress.proposalSlotCompleted(
                        assignment.taskId(), assignment.proposalSlot()))
            .toList();
    return new InspirationAssignmentPlan(
        remaining,
        plan.deferredReason(),
        plan.eligibleAgentIds(),
        plan.mechanism(),
        remaining.size(),
        plan.roundIndex(),
        plan.taskId());
  }

  private void markInspirationProposalSlotCompleted(String taskId, int proposalSlot) {
    inspirationProgress = inspirationProgress.markProposalSlot(taskId, proposalSlot);
    persistInspirationProgress();
  }

  private void markInspirationTaskCompleted(String taskId) {
    inspirationProgress = inspirationProgress.markTaskCompleted(taskId);
    persistInspirationProgress();
  }

  static boolean isRecoverableInspirationAgentFailure(RuntimeException failure) {
    if (failure instanceof StructuredOutputError) {
      return true;
    }
    return failure instanceof AgentCallFailure agentFailure
        && agentFailure.providerFailure().kind() != ProviderErrorKind.CANCELLED;
  }

  private static String inspirationFailureSummary(RuntimeException failure) {
    if (failure instanceof AgentCallFailure agentFailure) {
      return "Provider call failed ("
          + agentFailure.providerFailure().kind()
          + ") after bounded retries; this proposal slot was skipped";
    }
    return "Structured inspiration artifact remained invalid after bounded repair; "
        + "this proposal slot was skipped";
  }

  private InspirationProposal generateInspirationProposal(
      InspirationTask task,
      InspirationProposalAssignment assignment,
      AgentRuntime proposer,
      MechanismContextProfile.PromptContext promptContext) {
    StrategyCard draft =
        callStage(
                "inspiration-" + task.taskId() + "-slot-" + assignment.proposalSlot(),
                promptStageForMechanism(task.mechanism()),
                StrategyCard.class,
                Map.of(
                    "immutable_problem", frozenProblem,
                    "task", task,
                    "context_mode", assignment.contextMode(),
                    "bounded_context", promptContext,
                    "open_obligations", task.targetObligationIds(),
                    "required_mechanism", task.mechanism().value(),
                    "authority_rule",
                        "This is an unverified proposal. It may not claim Fact or close a checkpoint."),
                proposer,
                "breadth",
                "Generating " + task.mechanism().value() + " inspiration proposal")
            .value();
    List<String> targets =
        task.targetRouteIds().isEmpty()
            ? routes.stream().map(route -> route.routeId).limit(1).toList()
            : task.targetRouteIds();
    List<String> generated =
        draft.expectedLemmas().isEmpty()
            ? List.of(draft.bottleneck())
            : draft.expectedLemmas();
    NoveltySignature novelty =
        new NoveltySignature(
            draft.tags(),
            List.of("java-production-orchestration"),
            generated,
            List.of(task.mechanism().value()),
            1.0d,
            null,
            "java-0.8.0",
            List.of(draft.coreIdea()),
            Map.of("strategy_tags", draft.tags()),
            List.of(draft.independenceBasis()),
            task.targetObligationIds());
    return new InspirationProposal(
        null,
        null,
        null,
        assignment.contextMode(),
        Math.max(1, (int) Math.ceil(draft.estimatedCost() * 4.0d)),
        EvidenceType.UNVERIFIED_IDEA,
        draft.estimatedSuccess(),
        generated,
        null,
        task.mechanism(),
        null,
        1.0d,
        novelty,
        null,
        assignment.proposalSlot(),
        draft.independenceBasis(),
        null,
        null,
        proposer.id(),
        draft.coreIdea(),
        targets,
        task.taskId(),
        task.triggerId());
  }

  private void materializeInspiration(InspirationEngine.ExecutionResult result) {
    InspirationProposal proposal = result.proposal();
    String action = result.materialization().action();
    StrategyCard inspired = null;
    ProofControlModels.Strategy inspiredControl = null;
    StrategyBlueprintCompiler.Compilation inspiredBlueprint = null;
    if ("route_created".equals(action) && routes.size() < config.budget().maxPaths()) {
      inspired = inspiredStrategy(proposal);
      inspiredControl = controlStrategy(inspired, "route-" + (routes.size() + 1));
      inspiredBlueprint =
          proofControl.blueprintCompiler().compile(problemHash, inspiredControl, controlGoal());
    }
    RouteState targetRoute =
        proposal.targetRouteIds().stream()
            .flatMap(routeId -> routes.stream().filter(route -> route.routeId.equals(routeId)))
            .filter(this::routeEligibleForWork)
            .filter(route -> !"verified".equals(route.status))
            .findFirst()
            .orElseGet(
                () ->
                    routes.stream()
                        .filter(this::routeEligibleForWork)
                        .filter(route -> !"verified".equals(route.status))
                        .findFirst()
                        .orElse(null));
    MessageEnvelope insight = inspirationMessage(proposal);
    String statement =
        proposal.generatedObligations().stream()
            .filter(value -> value != null && !value.isBlank())
            .min(
                java.util.Comparator.comparingInt(String::length)
                    .thenComparing(java.util.Comparator.naturalOrder()))
            .orElse(proposal.statement());
    String id = proposal.proposalId() + "-obligation-1";
    List<NegativeKnowledgeCandidate> drafts = new ArrayList<>();
    if (inspired != null) {
      drafts.addAll(
          negativeKnowledgeCandidates(
              inspired,
              inspiredBlueprint,
              NegativeKnowledgeSurface.INSPIRATION_MATERIALIZATION));
    }
    for (String draft :
        java.util.stream.Stream.concat(
                java.util.stream.Stream.of(proposal.statement(), statement),
                proposal.generatedObligations().stream())
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .toList()) {
      for (NegativeKnowledgeTargetType targetType : NegativeKnowledgeTargetType.values()) {
        drafts.add(
            negativeKnowledgeCandidate(
                draft,
                targetType,
                NegativeKnowledgeSurface.INSPIRATION_MATERIALIZATION,
                NegativeCandidateIntent.POSITIVE_DEPENDENCY));
      }
    }
    try {
      negativeKnowledgeGate.requireAllAllowed(drafts, roundIndex.get());
    } catch (NegativeKnowledgeBlockedException exception) {
      recordNegativeKnowledgeRejection(
          "inspiration_materialization_rejected",
          "scheduler_inspiration",
          proposal.proposalId(),
          exception);
      return;
    }

    String prospectiveRouteId =
        inspired == null
            ? targetRoute == null ? "run" : targetRoute.routeId
            : "route-" + (routes.size() + 1);
    String prospectiveStrategyId = inspired == null ? "" : inspired.strategyId();
    List<String> routeIds = List.of(prospectiveRouteId);
    ProofObligation inspirationObligation =
        new ProofObligation(
            List.of(),
            0.5d,
            "",
            List.of(),
            List.of(),
            List.of(),
            null,
            ObligationKind.SUBGOAL,
            topology.mathNormalize(statement),
            id,
            0.6d,
            problemHash,
            List.of(),
            routeIds,
            statement,
            "open");
    ObligationCreationContext inspirationContext =
        new ObligationCreationContext(
            problemHash,
            prospectiveRouteId,
            prospectiveStrategyId,
            ObligationSourceType.INSPIRATION,
            "inspiration://" + proposal.proposalId(),
            List.of(),
            "",
            Map.of(),
            proposal.mechanism().value(),
            proposal.mechanism().value(),
            BottleneckRelationType.SHARES_UPSTREAM_BOTTLENECK,
            ObligationOccurrenceSchedulingState.ACTIVE,
            roundIndex.get());
    ObligationControlAdmission graphAdmission =
        previewObligationControl(
            inspirationObligation,
            inspirationContext,
            inspirationControlAction(proposal.mechanism().value()));
    if (findObligation(id).isEmpty()) {
      ControlledObligationWrite graphWrite =
          addObligationThroughControl(inspirationObligation, graphAdmission);
      if (!graphWrite.decision().allowed()) {
        return;
      }
    } else if (!graphAdmission.decision().allowed()) {
      return;
    }
    if (inspired != null) {
      strategyArchive.archive(
          inspiredControl, "inspiration://" + proposal.proposalId(), roundIndex.get());
      strategyBlueprints.put(inspired.strategyId(), inspiredBlueprint);
      admittedStrategies =
          java.util.stream.Stream.concat(
                  admittedStrategies.stream(), java.util.stream.Stream.of(inspired))
              .toList();
      addRoute(inspired, 0, NegativeKnowledgeSurface.INSPIRATION_MATERIALIZATION);
      targetRoute = routes.getLast();
    }
    typedMemory.addInsight(insight);
    if (targetRoute != null) {
      enqueueProofTask(
          "inspiration:" + proposal.mechanism().value(),
          targetRoute.routeId,
          id,
          targetRoute.failure == null ? "DEEPEN" : "REVISE");
    }
  }

  private static FocusedRecoveryActionType inspirationControlAction(String mechanism) {
    String normalized = mechanism == null ? "" : mechanism.toLowerCase(Locale.ROOT);
    if (normalized.contains("representation")) {
      return FocusedRecoveryActionType.REPRESENTATION_SWITCH;
    }
    if (normalized.contains("analogy")) {
      return FocusedRecoveryActionType.STRUCTURAL_ANALOGY;
    }
    if (normalized.contains("bridge")) {
      return FocusedRecoveryActionType.UNSCOPED_BRIDGE;
    }
    return FocusedRecoveryActionType.GENERIC_INSPIRATION;
  }

  private StrategyCard inspiredStrategy(InspirationProposal proposal) {
    return new StrategyCard(
        null,
        proposal.generatedObligations().getFirst(),
        List.of(),
        List.of(),
        List.of(),
        proposal.statement(),
        List.of(),
        Math.min(1.0d, proposal.estimatedCost() / 4.0d),
        proposal.expectedInformationGain(),
        proposal.generatedObligations(),
        "Try to refute the proposed mechanism on the smallest admissible instance.",
        proposal.rationaleSummary(),
        proposal.proposalId(),
        proposal.statement(),
        List.of(),
        List.of(),
        "strategy-inspired-" + proposal.proposalId(),
        proposal.noveltySignature().mechanismTags(),
        "Inspired " + proposal.mechanism().value());
  }

  private MessageEnvelope inspirationMessage(InspirationProposal proposal) {
    String sourceRoute =
        proposal.targetRouteIds().stream().filter(routeRegistry::exists).findFirst().orElse("run");
    return new MessageEnvelope(
        List.of(),
        List.of(),
        proposal.statement(),
        "",
        null,
        List.of(),
        List.of(),
        EvidenceType.UNVERIFIED_IDEA,
        MemoryTier.INSIGHT,
        "insight-" + proposal.proposalId(),
        MessageType.CLAIM_PROPOSAL,
        1.0d,
        topology.mathNormalize(proposal.statement()),
        problemHash,
        List.of(),
        null,
        roundIndex.get(),
        "1",
        List.of("unverified inspiration"),
        proposal.sourceAgentId(),
        RouteRole.PROVER,
        sourceRoute,
        proposal.statement(),
        proposal.targetRouteIds(),
        config.topology().crossRoute().messageTtlRounds(),
        List.of(),
        proposal.noveltyScore(),
        ClaimStatus.PROPOSED);
  }

  private InspirationSnapshot inspirationSnapshot(boolean manualTrigger) {
    Map<String, Double> debt = new LinkedHashMap<>();
    Map<String, Integer> stagnation = new LinkedHashMap<>();
    for (RouteState route : routes) {
      debt.put(route.routeId, proofGraph.canonicalProofDebt(route.routeId));
      stagnation.put(
          route.routeId,
          "verified".equals(route.status) ? 0 : Math.max(1, roundIndex.get()));
    }
    double total = debt.values().stream().mapToDouble(Double::doubleValue).sum();
    double prior = proofDebtHistory.isEmpty() ? total : proofDebtHistory.getLast();
    proofDebtHistory.add(total);
    sampleProofGraphConvergenceRound();
    List<ProofObligation> open =
        proofGraph.obligations().stream()
            .filter(obligation -> !"closed".equals(obligation.status()))
            .toList();
    String bottleneck = proofGraph.coreBottleneck();
    return new InspirationSnapshot(
        roundIndex.get(),
        problemHash,
        triage == null ? "unknown" : triage.problemKind().value(),
        routes.stream()
            .filter(route -> !Set.of("abandoned", "failed").contains(route.status))
            .map(route -> route.routeId)
            .toList(),
        routes.stream()
            .filter(route -> Set.of("abandoned", "failed", "unverified").contains(route.status))
            .map(route -> route.routeId)
            .toList(),
        stagnation,
        lemmaMemory.verified().size(),
        debt,
        Math.max(0.0d, prior - total),
        proofDebtHistory,
        routes.stream()
            .map(route -> route.failure)
            .filter(Objects::nonNull)
            .map(FailureControlService.Failure::firstErrorFingerprint)
            .filter(Objects::nonNull)
            .toList(),
        routeRedundancy(),
        bottleneck.isBlank() ? List.of() : List.of(bottleneck),
        safeInt(ledger.remainingCalls()),
        Math.max(
            config.scheduler().finishTransitionBufferCalls(),
            inspirationPolicy.limits().finalizationReserveCalls()),
        routes.size(),
        config.budget().maxPaths(),
        open.stream().map(ProofObligation::obligationId).toList(),
        open.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    ProofObligation::obligationId,
                    obligation -> obligation.kind().value(),
                    (left, right) -> left,
                    LinkedHashMap::new)),
        false,
        manualTrigger && inspirationPolicy.runs());
  }

  private void sampleProofGraphConvergenceRound() {
    int exactRefutations =
        (int)
            proofGraph.allCanonicalTargets().stream()
                .filter(
                    target ->
                        proofGraph.canonicalStatus(target.canonicalTargetId())
                            == CanonicalObligationStatus.REFUTED)
                .count();
    proofGraphConvergence.sample(
        roundIndex.get(),
        proofGraph,
        lemmaMemory.verified().size(),
        exactRefutations,
        proofGraphConvergence.genericExpansionBlocks(),
        immutableRootGoalHash());
    reconsiderDeferredExpansions();
    if (proofGraphConvergence.controlMode() == ProofGraphControlMode.FOCUSED_RECOVERY) {
      enqueueFocusedRecoveryTask();
    }
  }

  private void enqueueFocusedRecoveryTask() {
    FocusedRecoveryPlan plan = proofGraphConvergence.focusedRecoveryPlan().orElse(null);
    if (plan == null
        || pendingProofTasks.stream()
            .anyMatch(task -> task.source().equals("focused-recovery:" + plan.episodeId()))
        || !proofGraphConvergence.acquireFocusedTaskLease(
            FocusedRecoveryActionType.FOCUSED_PROVER, roundIndex.get())) {
      return;
    }
    Map<String, String> canonicalByObligation = new LinkedHashMap<>();
    proofGraph.rawObligationOccurrences().forEach(
        occurrence ->
            canonicalByObligation.put(
                occurrence.obligationId(), occurrence.canonicalTargetId()));
    Optional<ProofObligation> selected =
        proofGraph.obligations().stream()
            .filter(
                obligation ->
                    plan.selectedCanonicalTargetIds()
                        .contains(canonicalByObligation.get(obligation.obligationId())))
            .filter(
                obligation ->
                    Set.of("open", "tentative", "blocked").contains(obligation.status()))
            .sorted(
                java.util.Comparator.comparingDouble(ProofObligation::centrality)
                    .reversed()
                    .thenComparing(
                        ProofObligation::priority, java.util.Comparator.reverseOrder())
                    .thenComparing(ProofObligation::obligationId))
            .findFirst();
    selected
        .flatMap(
            obligation ->
                routeForObligation(obligation)
                    .map(route -> Map.entry(obligation, route)))
        .ifPresent(
            entry ->
                enqueueProofTask(
                    "focused-recovery:" + plan.episodeId(),
                    entry.getValue().routeId,
                    entry.getKey().obligationId(),
                    entry.getValue().failure == null ? "DEEPEN" : "REVISE"));
  }

  private String immutableRootGoalHash() {
    return rootGoal == null
        ? CanonicalJson.stableHash(Map.of("problem_hash", problemHash, "problem", request.problem()))
        : rootGoal.sourceStatementHash();
  }

  private double routeRedundancy() {
    if (routes.size() < 2) {
      return 0.0d;
    }
    double sum = 0.0d;
    int pairs = 0;
    for (int left = 0; left < routes.size(); left++) {
      for (int right = left + 1; right < routes.size(); right++) {
        sum +=
            topology.mathSimilarity(
                topology.strategyText(routes.get(left).strategy),
                topology.strategyText(routes.get(right).strategy));
        pairs++;
      }
    }
    return pairs == 0 ? 0.0d : Math.max(0.0d, Math.min(1.0d, sum / pairs));
  }

  private double totalProofDebt() {
    return proofGraph.globalCanonicalProofDebt();
  }

  private List<InspirationAssignmentPlanner.AgentCandidate> inspirationAgentCandidates() {
    return pool.agents().stream()
        .map(
            agent ->
                new InspirationAssignmentPlanner.AgentCandidate(
                    agent.id(),
                    new LinkedHashSet<>(agent.config().roles()),
                    agent.trust(),
                    agent.specialtyScore(List.of("proof_synthesis", "problem_decomposition")),
                    agent.trust(),
                    agent.activeCalls(),
                    safeInt(agent.metric().calls()),
                    agent.inCooldown()))
        .toList();
  }

  private AgentRuntime selectMetaReviewer() {
    return pool.agents().stream()
        .filter(agent -> agent.supportsRole("meta_reviewer"))
        .findFirst()
        .orElseGet(
            () -> pool.select("planner", Set.of(), List.of("problem_decomposition"), null, true));
  }

  private List<Map<String, Object>> schedulerRouteState() {
    return routes.stream()
        .map(
            route -> {
              Map<String, Object> item = new LinkedHashMap<>();
              item.put("route_id", route.routeId);
              item.put("strategy_id", route.strategy.strategyId());
              item.put("status", route.status);
              item.put("proof_debt", proofGraph.canonicalProofDebt(route.routeId));
              item.put("claim_ids", List.copyOf(route.claimIds));
              item.put("failure", route.failure == null ? "none" : route.failure.failureClass());
              item.put(
                  "message_utility", mathematicalArtifactBroker.utilityForRoute(route.routeId));
              return Map.copyOf(item);
            })
        .toList();
  }

  private Map<String, Double> brokerUtility() {
    Map<String, Double> result = new LinkedHashMap<>();
    routes.forEach(
        route ->
            result.put(
                route.routeId, mathematicalArtifactBroker.utilityForRoute(route.routeId)));
    return Map.copyOf(result);
  }

  private void synthesizeAndVerify() {
    AgentRuntime synthesizer =
        pool.select("synthesizer", Set.of(), List.of("proof_synthesis"), null, true);
    if (finalProof == null) {
      stage(
          RoutePipelineFunctions.RunStage.SYNTHESIS,
          "Synthesizing only the independently reviewed dependency closure");
      List<ClaimCard> reusable = lemmaMemory.verified();
      Set<String> reusableIds =
          reusable.stream().map(ClaimCard::claimId).collect(java.util.stream.Collectors.toSet());
      List<SynthesisPhaseService.VerifiedClaim> verifiedClaims =
          reusable.stream()
              .map(
                  claim ->
                      new SynthesisPhaseService.VerifiedClaim(
                          claim.claimId(),
                          claim.statement(),
                          claim.dependencies().stream().filter(reusableIds::contains).toList(),
                          claim.sourceAgentId(),
                          true,
                          true))
              .toList();
      Set<String> selected =
          verifiedRoutes().stream()
              .flatMap(route -> route.claimIds.stream())
              .filter(reusableIds::contains)
              .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
      if (selected.isEmpty()) {
        selected.addAll(reusableIds);
      }
      List<SynthesisPhaseService.NegativeEvidence> negatives =
          typedMemory.negatives().stream()
              .map(
                  message ->
                      new SynthesisPhaseService.NegativeEvidence(
                          message.evidenceType().value(),
                          message.statement(),
                          message.evidenceType() == EvidenceType.COUNTEREXAMPLE))
              .toList();
      SynthesisPhaseService.SynthesisPacket packet =
          synthesisPhase.synthesize(verifiedClaims, selected, negatives, 24_000);
      if (!packet.complete()) {
        throw new IllegalStateException("mandatory negative evidence could not fit synthesis context");
      }
      StructuredCallResult<FinalProof> call =
          callStage(
              "synthesis",
              "synthesis",
              FinalProof.class,
              Map.of(
                  "immutable_problem", frozenProblem,
                  "problem_hash", problemHash,
                  "verified_dependency_closure", packet.verifiedFactPackets(),
                  "negative_evidence", packet.negativeSelection(),
                  "admitted_attempts", verifiedRoutes().stream().map(route -> route.attempt).toList(),
                  "independent_reviews",
                      verifiedRoutes().stream().map(route -> route.detailedReview).toList(),
                  "proof_graph", proofGraph.snapshot(),
                  "computation_evidence",
                      computationTraces.stream().map(ComputationTrace::publicView).toList(),
                  "synthesis_rule",
                      "Use only verified closure; preserve all hypotheses and quantifiers; computations are bounded evidence only."),
              synthesizer,
              "synthesis",
              "Synthesizing the final proof from admitted facts");
      FinalProof source = call.value();
      finalProof =
          new FinalProof(
              source.answer(),
              source.caveats(),
              source.confidence(),
              source.dependencies(),
              problemHash,
              source.proofSteps(),
              verifiedRoutes().stream().map(route -> route.attempt.attemptId()).toList());
      complete(RoutePipelineFunctions.RunStage.SYNTHESIS);
      workflowCursor = CURSOR_FINAL_REVIEW;
      persistUnchecked("synthesis", false);
    }

    runFinalValidation(synthesizer);
  }

  private void runFinalValidation(AgentRuntime synthesizer) {
    stage(
        RoutePipelineFunctions.RunStage.BLIND_FINAL_REVIEW,
        "Running structural, blind, adversarial, deterministic, cross-provider, and tool/formal gates");
    BlindReviewPacketFactory packets = new BlindReviewPacketFactory();
    List<ObjectNode> factPackets =
        typedMemory.facts().stream()
            .map(message -> (ObjectNode) ContractObjectMapper.toTree(message))
            .toList();
    List<ObjectNode> negativePackets =
        typedMemory.negatives().stream()
            .map(message -> (ObjectNode) ContractObjectMapper.toTree(message))
            .toList();
    BlindReviewPacket blind =
        packets.build(
            frozenProblem,
            finalProof,
            factPackets,
            List.of(),
            negativePackets,
            config.topology().typedMemory().maxNegativeContext(),
            24_000);

    Set<String> excluded =
        verifiedRoutes().stream()
            .map(route -> route.author.id())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    excluded.add(synthesizer.id());
    AgentRuntime structuralReviewer = selectIndependentAgent(excluded, "final_verifier");
    List<VerificationReport> reports = new ArrayList<>();
    VerificationReport structural =
        runFinalProofReview(
            structuralReviewer,
            "final-structural",
            "structural_verification",
            VerificationStage.STRUCTURAL,
            blind,
            packets,
            "Independently checking final proof structure, dependency closure, scopes, and quantifiers");
    reports.add(structural);

    List<ComputationTrace> auditableComputations =
        computationTraces.stream()
            .filter(ComputationTrace::replayValid)
            .filter(trace -> trace.decision().decision() == ComputationDecisionStatus.ALLOW)
            .filter(trace -> trace.result() != null)
            .toList();
    List<ComputationAudit> audits = auditComputations(auditableComputations);
    List<ExperimentResult> experimentResults =
        auditableComputations.stream().map(ComputationTrace::result).toList();
    formalizationCoverage = FormalizationCoverage.measure(finalProof.proofSteps(), experimentResults);
    boolean toolOrFormalAvailable =
        !audits.isEmpty() || !formalizationCoverage.formallyCertifiedStepIds().isEmpty();
    Optional<AgentRuntime> crossProviderReviewer =
        selectCrossProviderFinalReviewer(excluded, synthesizer.provider());
    ValidationEscalationPolicy finalPolicy =
        new ValidationEscalationPolicy(
            true, true, true, true, true, true, 0.70d, true, true, true);
    EscalationPlan escalation =
        new ValidationEscalator(finalPolicy)
            .plan(
                Math.max(0.0d, 1.0d - finalProof.confidence()),
                List.of(structural.verdict().value()),
                crossProviderReviewer.isPresent(),
                toolOrFormalAvailable,
                false,
                true);
    Map<ValidationLevel, java.util.function.Supplier<ValidationStepResult>> handlers =
        new EnumMap<>(ValidationLevel.class);
    handlers.put(
        ValidationLevel.DETERMINISTIC,
        () -> deterministicFinalStep(blind));
    handlers.put(
        ValidationLevel.BLIND_SAME_MODEL,
        () ->
            runBlindFinalStep(
                ValidationLevel.BLIND_SAME_MODEL,
                "blind_final_verification",
                selectIndependentAgent(excluded, "final_verifier"),
                blind,
                packets,
                synthesizer,
                reports));
    handlers.put(
        ValidationLevel.ADVERSARIAL_BLIND,
        () ->
            runBlindFinalStep(
                ValidationLevel.ADVERSARIAL_BLIND,
                "adversarial_final_verification",
                selectIndependentAgent(excluded, "final_verifier"),
                blind,
                packets,
                synthesizer,
                reports));
    handlers.put(
        ValidationLevel.CROSS_PROVIDER,
        () ->
            crossProviderReviewer
                .map(
                    reviewer ->
                        runCrossProviderFinalStep(
                            reviewer, blind, packets, synthesizer, reports))
                .orElseGet(() -> ValidationStepResult.missing(ValidationLevel.CROSS_PROVIDER)));
    handlers.put(
        ValidationLevel.TOOL_OR_FORMAL,
        () -> toolOrFormalFinalStep(audits));
    finalValidationExecution =
        new ValidationEscalationExecutor().execute(escalation, handlers);

    finalReviewReports.clear();
    finalReviewReports.addAll(reports);
    finalReview = reports.stream().filter(report -> !finalReportPassed(report)).findFirst().orElse(reports.getLast());
    finalValidationPassed =
        finalReportPassed(structural)
            && finalValidationExecution.passed()
            && reports.stream().allMatch(this::finalReportPassed);
    event(
        "verification",
        "blind_final_review",
        finalReview.agentId(),
        finalValidationPassed ? "verified" : "unverified",
        finalValidationPassed
            ? "Every independent final validation level passed"
            : finalValidationFailureSummary(),
        finalReview.reportId());
    if (finalValidationPassed) {
      markUsedInspirationCitations();
    }
    inspirationOutcomes.clear();
    inspirationOutcomes.addAll(inspirationLedger.snapshot().values());
    proofGraph.freeze();
    complete(RoutePipelineFunctions.RunStage.BLIND_FINAL_REVIEW);
  }

  private VerificationReport runFinalProofReview(
      AgentRuntime reviewer,
      String idempotencyKey,
      String stageName,
      VerificationStage verificationStage,
      BlindReviewPacket blind,
      BlindReviewPacketFactory packets,
      String summary) {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("immutable_problem", frozenProblem);
    context.put("problem_hash", problemHash);
    context.put("candidate_final_proof", finalProof);
    context.put("blind_review_packet", packets.reviewerPayload(blind));
    context.put("proof_graph", proofGraph.snapshot());
    context.put("required_target_id", "final-proof");
    context.put("required_target_type", "final_proof");
    context.put("required_stage", verificationStage.value());
    context.put("review_rule", summary);
    StructuredCallResult<VerificationReport> call =
        callStage(
            idempotencyKey,
            stageName,
            VerificationReport.class,
            context,
            reviewer,
            "verification",
            summary);
    return bindReview(
        call.value(),
        call,
        reviewer,
        "final-proof",
        "final_proof",
        verificationStage);
  }

  private ValidationStepResult deterministicFinalStep(BlindReviewPacket blind) {
    Set<String> verifiedAttemptIds =
        verifiedRoutes().stream()
            .map(route -> route.attempt.attemptId())
            .collect(java.util.stream.Collectors.toSet());
    boolean passed =
        blind.factContextComplete()
            && blind.negativeContextComplete()
            && problemHash.equals(finalProof.problemHash())
            && !finalProof.proofSteps().isEmpty()
            && !finalProof.sourceAttemptIds().isEmpty()
            && verifiedAttemptIds.containsAll(finalProof.sourceAttemptIds())
            && "closed".equals(proofGraph.getObligation(MAIN_GOAL_ID).status());
    event(
        "deterministic_final_gate",
        "blind_final_review",
        null,
        passed ? "completed" : "unverified",
        passed
            ? "Problem identity, source closure, contexts, proof steps, and proof graph are complete"
            : "Deterministic final prerequisites are incomplete",
        MAIN_GOAL_ID);
    return passed
        ? ValidationStepResult.passed(
            ValidationLevel.DETERMINISTIC, List.of(problemHash, MAIN_GOAL_ID))
        : ValidationStepResult.failed(
            ValidationLevel.DETERMINISTIC,
            "problem identity, source closure, contexts, proof steps, or graph closure failed");
  }

  private ValidationStepResult runBlindFinalStep(
      ValidationLevel level,
      String stageName,
      AgentRuntime reviewer,
      BlindReviewPacket blind,
      BlindReviewPacketFactory packets,
      AgentRuntime synthesizer,
      List<VerificationReport> reports) {
    Map<String, Object> context = finalBlindReviewContext(level, blind, packets, synthesizer);
    StructuredCallResult<BlindVerificationReport> call =
        callStage(
            "final-" + level.name().toLowerCase(Locale.ROOT),
            stageName,
            BlindVerificationReport.class,
            context,
            reviewer,
            "verification",
            "Running " + level.name().toLowerCase(Locale.ROOT) + " final validation");
    VerificationReport report = bindBlindReview(call.value(), call, reviewer);
    reports.add(report);
    return finalValidationStep(level, report);
  }

  private ValidationStepResult runCrossProviderFinalStep(
      AgentRuntime reviewer,
      BlindReviewPacket blind,
      BlindReviewPacketFactory packets,
      AgentRuntime synthesizer,
      List<VerificationReport> reports) {
    VerificationReport report =
        runFinalProofReview(
            reviewer,
            "final-cross-provider",
            "cross_provider_final_verification",
            VerificationStage.FINAL,
            blind,
            packets,
            "Running an independent cross-provider final proof audit; synthesizer "
                + synthesizer.id()
                + " is excluded");
    reports.add(report);
    return finalValidationStep(ValidationLevel.CROSS_PROVIDER, report);
  }

  private Map<String, Object> finalBlindReviewContext(
      ValidationLevel level,
      BlindReviewPacket blind,
      BlindReviewPacketFactory packets,
      AgentRuntime synthesizer) {
    ObjectNode reviewerPayload = packets.reviewerPayload(blind);
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("immutable_problem", reviewerPayload.path("problem").deepCopy());
    context.put("problem_hash", problemHash);
    context.put("blind_review_packet", reviewerPayload);
    context.put("validation_level", level.name());
    context.put("required_target_id", "final-proof");
    context.put("required_target_type", "final_proof");
    context.put("required_stage", "final");
    context.put("synthesizer_excluded", synthesizer.id());
    context.put("recorded_computation_audits", List.copyOf(computationAudits));
    context.put("formalization_coverage", formalizationCoverage);
    return Map.copyOf(context);
  }

  private ValidationStepResult toolOrFormalFinalStep(List<ComputationAudit> audits) {
    boolean replayPassed = !audits.isEmpty() && audits.stream().allMatch(ComputationAudit::valid);
    boolean formalPassed = !formalizationCoverage.formallyCertifiedStepIds().isEmpty();
    List<String> evidence = new ArrayList<>();
    audits.stream()
        .map(ComputationAudit::replayedResultHash)
        .filter(value -> !value.isBlank())
        .forEach(evidence::add);
    evidence.addAll(formalizationCoverage.formallyCertifiedStepIds());
    event(
        "tool_or_formal_final_gate",
        "formal_chain_verification",
        null,
        replayPassed || formalPassed ? "verified" : "unverified",
        replayPassed || formalPassed
            ? "Actual computation replay or formal certification passed"
            : "No independently replayed computation or formal certificate passed",
        evidence.isEmpty() ? "formalization-coverage" : evidence.getFirst());
    return replayPassed || formalPassed
        ? ValidationStepResult.passed(ValidationLevel.TOOL_OR_FORMAL, evidence)
        : ValidationStepResult.failed(
            ValidationLevel.TOOL_OR_FORMAL,
            "actual computation replay and formal certification were both unavailable or invalid");
  }

  private ValidationStepResult finalValidationStep(
      ValidationLevel level, VerificationReport report) {
    return finalReportPassed(report)
        ? ValidationStepResult.passed(level, List.of(evidenceId(report)))
        : ValidationStepResult.failed(
            level,
            "independent final reviewer did not pass with checked dependencies and required confidence");
  }

  private boolean finalReportPassed(VerificationReport report) {
    return report != null
        && report.verdict() == VerificationVerdict.PASS
        && report.problemIntegrityOk()
        && !report.checkedDependencies().isEmpty()
        && report.confidence() >= config.budget().verificationPassThreshold();
  }

  private Optional<AgentRuntime> selectCrossProviderFinalReviewer(
      Set<String> excluded, String synthesizerProvider) {
    return pool.agents().stream()
        .filter(agent -> !excluded.contains(agent.id()))
        .filter(agent -> !agent.provider().equals(synthesizerProvider))
        .filter(
            agent ->
                agent.supportsRole("final_verifier")
                    || agent.supportsRole("detailed_verifier")
                    || agent.supportsRole("route_referee"))
        .findFirst();
  }

  private String finalValidationFailureSummary() {
    if (finalValidationExecution != null && !finalValidationExecution.diagnostics().isEmpty()) {
      return String.join("; ", finalValidationExecution.diagnostics());
    }
    return "At least one independent final verification gate did not pass";
  }

  private void markUsedInspirationCitations() {
    Set<String> sourceAttempts = new LinkedHashSet<>(finalProof.sourceAttemptIds());
    List<RouteState> sourceRoutes =
        verifiedRoutes().stream()
            .filter(route -> sourceAttempts.contains(route.attempt.attemptId()))
            .toList();
    Set<String> sourceRouteIds =
        sourceRoutes.stream().map(route -> route.routeId).collect(java.util.stream.Collectors.toSet());
    Set<String> sourceProposalIds =
        sourceRoutes.stream()
            .map(route -> route.strategy.inspirationProposalId())
            .filter(Objects::nonNull)
            .filter(value -> !value.isBlank())
            .collect(java.util.stream.Collectors.toSet());
    Map<String, InspirationOutcome> outcomes = inspirationLedger.snapshot();
    inspirationProposals.stream()
        .filter(
            proposal ->
                sourceProposalIds.contains(proposal.proposalId())
                    || proposal.targetRouteIds().stream().anyMatch(sourceRouteIds::contains))
        .map(InspirationProposal::proposalId)
        .filter(outcomes::containsKey)
        .forEach(inspirationLedger::markFinalCitation);
  }

  private AgentRuntime selectIndependentAgent(Set<String> excluded, String preferredRole) {
    return pool.agents().stream()
        .filter(agent -> !excluded.contains(agent.id()))
        .filter(agent -> agent.supportsRole(preferredRole) || agent.supportsRole("detailed_verifier"))
        .findFirst()
        .orElseGet(
            () ->
                pool.agents().stream()
                    .filter(agent -> !excluded.contains(agent.id()))
                    .findFirst()
                    .orElseThrow(
                        () -> new IllegalStateException("no independent final reviewer remains")));
  }

  private Optional<DesktopSolveCheckpoint> readCheckpoint() throws IOException {
    Path state = statePath();
    if (!Files.isRegularFile(state)) {
      return Optional.empty();
    }
    DesktopSolveCheckpoint checkpoint =
        ContractObjectMapper.read(Files.readString(state, StandardCharsets.UTF_8), DesktopSolveCheckpoint.class);
    if (checkpoint.schemaVersion() < 1 || checkpoint.schemaVersion() > STATE_SCHEMA_VERSION) {
      throw new IllegalStateException("unsupported desktop solve checkpoint schema");
    }
    if (!runId.equals(checkpoint.runId()) || !problemHash.equals(checkpoint.problemHash())) {
      throw new IllegalStateException("checkpoint immutable identity does not match this run");
    }
    return Optional.of(checkpoint);
  }

  private void restore(DesktopSolveCheckpoint checkpoint) throws IOException {
    UsageTotals persistedUsage = checkpoint.usageTotals();
    if (persistedUsage == null) {
      persistedUsage = UsageTotals.zero();
    }
    DurableProviderUsageCollector.Result collectedUsage =
        DurableProviderUsageCollector.collect(runDirectory, config, persistedUsage, List.of());
    if (collectedUsage.status().conflict()) {
      throw new IllegalStateException(
          "durable provider usage conflicts with the semantic checkpoint aggregate");
    }
    ledger.restoreCommittedUsage(collectedUsage.totals());
    budgetRuntime.restore(
        checkpoint.schemaVersion(),
        collectedUsage.totals(),
        checkpoint.budgetDecisions(),
        checkpoint.budgetEnvelopes(),
        checkpoint.budgetReservations(),
        checkpoint.budgetUsage(),
        checkpoint.pricingSnapshot(),
        checkpoint.zeroGain(),
        checkpoint.certifiedGains());
    currentStage = checkpoint.currentStage();
    runStateAnchor = checkpoint.runStateAnchor();
    roundIndex.set(checkpoint.roundIndex());
    frozenProblem = checkpoint.problem();
    rootGoal =
        frozenProblem == null
            ? null
            : RootGoalContract.freeze(
                frozenProblem.exactStatement(), exactGoalContractChecker);
    triage = checkpoint.triage();
    strategySet = checkpoint.strategySet();
    admittedStrategies = checkpoint.admittedStrategies();
    nextStrategyIndex.set(checkpoint.nextStrategyIndex());
    workflowCursor =
        checkpoint.workflowCursor().isBlank()
            ? DesktopWorkflowCursorInference.infer(checkpoint)
            : checkpoint.workflowCursor();
    pendingMetaReview = checkpoint.pendingMetaReview();
    pendingProofTasks.clear();
    pendingProofTasks.addAll(checkpoint.pendingProofTasks());
    schedulerStop = checkpoint.schedulerStop();
    finalProof = checkpoint.finalProof();
    finalReview = checkpoint.finalReview();
    finalReviewReports.clear();
    finalReviewReports.addAll(checkpoint.finalReviewReports());
    finalValidationPassed = checkpoint.finalValidationPassed();
    finalValidationExecution = checkpoint.finalValidationExecution();
    formalizationCoverage = checkpoint.formalizationCoverage();
    completedStages.clear();
    completedStages.addAll(checkpoint.completedStages());
    strategyBlueprints.clear();
    strategyBlueprints.putAll(checkpoint.strategyBlueprints());
    goalLinks.clear();
    goalLinks.putAll(checkpoint.goalLinks());
    strategyCandidates = StrategyCandidateLedger.restore(checkpoint.strategyCandidates());
    strategyMechanisms = StrategyMechanismRegistry.restore(checkpoint.strategyMechanisms());
    strategyPreflights = StrategyPreflightRegistry.restore(checkpoint.strategyPreflights());
    strategyPortfolios = StrategyPortfolioRegistry.restore(checkpoint.strategyPortfolios());
    portfolioReplenishments =
        PortfolioReplenishmentLedger.restore(checkpoint.portfolioReplenishments());
    metaPivots.clear();
    metaPivots.addAll(checkpoint.metaPivots());
    semanticPivots.ledger().restore(checkpoint.semanticPivots());
    inspirationProgress = checkpoint.inspirationProgress();
    computationAudits.clear();
    computationAudits.addAll(checkpoint.computationAudits());
    boolean legacyComputationSchema = checkpoint.schemaVersion() < 18;
    computation.restore(
        legacyComputationSchema
            ? ComputationExecutionState.empty()
            : new ComputationExecutionState(
                checkpoint.computationCapabilities(),
                checkpoint.computationExecutions(),
                checkpoint.computationArtifacts(),
                checkpoint.computationVerifications(),
                checkpoint.computationOutcomeReceipts()));
    inspirationProposals.clear();
    inspirationProposals.addAll(checkpoint.inspirationProposals());
    inspirationOutcomes.clear();
    inspirationOutcomes.addAll(checkpoint.inspirationOutcomes());
    proofDebtHistory.clear();
    proofDebtHistory.addAll(checkpoint.proofDebtHistory());
    computationTraces.clear();
    checkpoint.computations().forEach(
        value ->
            computationTraces.add(
                new ComputationTrace(
                    value.routeId(),
                    value.spec(),
                    value.decision(),
                    value.program(),
                    value.result(),
                    value.targetBinding(),
                    value.authority(),
                    value.replayValid())));
    if (legacyComputationSchema) {
      checkpoint.computations().forEach(
          value -> {
            if (value.result() != null) {
              computation
                  .executionService()
                  .importLegacy(
                      value.routeId(),
                      value.spec(),
                      value.program(),
                      value.result(),
                      value.authority(),
                      value.replayValid(),
                      checkpoint.roundIndex());
            } else {
              computation
                  .executionService()
                  .importLegacyAudit(
                      value.routeId(),
                      value.spec(),
                      value.program(),
                      checkpoint.roundIndex());
            }
          });
    }

    if (checkpoint.typedMemory() != null) {
      typedMemory = TypedMemory.restore(checkpoint.typedMemory(), memoryPolicy());
    }
    if (checkpoint.lemmaMemory() != null) {
      lemmaMemory = LemmaMemory.restore(checkpoint.lemmaMemory());
    }
    if (checkpoint.proofGraph() != null) {
      proofGraph = ProofGraphStore.restore(checkpoint.proofGraph(), ProofGraphPolicy.defaults());
    }
    proofGraphConvergence =
        ProofGraphConvergenceMonitor.restore(
            ProofGraphConvergenceConfig.defaults(), checkpoint.proofGraphConvergence());
    deferredExpansions = DeferredExpansionLedger.restore(checkpoint.deferredExpansions());
    migrateAndRevalidateLegacyCanonicalProofTasks(checkpoint.schemaVersion());
    attemptArtifacts = AttemptArtifactLedger.restore(checkpoint.attemptArtifacts());
    researchCheckpoints = ResearchCheckpointLedger.restore(checkpoint.researchCheckpoints());
    researchTasks.restore(checkpoint.researchTasks());
    researchResults.restore(checkpoint.researchResults());
    researchAuthorityMutations.restore(checkpoint.researchAuthorityMutations());
    researchEpochs.restore(
        ResearchEpochCommitProtocolMigration.migrate(
            checkpoint.schemaVersion(),
            checkpoint.researchEpochs(),
            checkpoint.researchAuthorityMutations()));
    restorablePreparedEpochIds.clear();
    checkpoint.researchEpochs().epochs().stream()
        .filter(epoch -> epoch.status() == ResearchEpochStatus.MERGE_PREPARED)
        .filter(epoch -> epoch.authority() != null)
        .map(ResearchEpochRecord::epochId)
        .forEach(restorablePreparedEpochIds::add);
    pool.restoreLeases(checkpoint.agentLeases(), runId);
    pool.restoreConcurrencyTelemetry(checkpoint.concurrencyTelemetry());
    proofControl.claims().load(checkpoint.claimLifecycle());
    installNegativeKnowledgeRuntime();
    restoreStrategyDiversityState(checkpoint);
    claimProofRevisions = new ClaimProofRevisionLedger();
    claimProofRevisions.restore(checkpoint.claimProofRevisions());
    claimCourt = new ClaimCourtLedger();
    claimCourt.restore(checkpoint.claimCourt());
    claimCourtExecutions = new ClaimCourtStageExecutionLedger();
    claimCourtExecutions.restore(checkpoint.claimCourtExecutions());
    rebuildVersionFifteenClaimProofRevisions(checkpoint);
    claimCourtExecutions.quarantineInterrupted();
    typedMemory.revalidateFactsAgainstNegativeKnowledge(roundIndex.get());
    Set<String> restoreBlockedObligations =
        new LinkedHashSet<>(proofGraph.revalidateNegativeKnowledge());
    rebuildVersionSixClaimLifecycle(checkpoint);
    pendingProofTasks.removeIf(
        task -> restoreBlockedObligations.contains(task.obligationId()));
    Set<String> restoreBlockedStrategyIds = new LinkedHashSet<>();
    admittedStrategies =
        admittedStrategies.stream()
            .filter(
                strategy -> {
                  boolean blocked =
                      negativeKnowledgeBlocksStrategy(
                          strategy,
                          strategyBlueprints.get(strategy.strategyId()),
                          NegativeKnowledgeSurface.RESTORE_REVALIDATION);
                  if (blocked) {
                    restoreBlockedStrategyIds.add(strategy.strategyId());
                  }
                  return !blocked;
                })
            .toList();
    nextStrategyIndex.set(Math.min(nextStrategyIndex.get(), admittedStrategies.size()));
    if (checkpoint.messageStore() != null) {
      messageRepository = new InMemoryMessageRepository(checkpoint.messageStore());
    }
    mathematicalArtifactBroker.restore(
        checkpoint.brokerArtifactRegistry(),
        checkpoint.brokerArtifactPublications(),
        checkpoint.brokerArtifactDeliveries(),
        checkpoint.brokerArtifactReceipts(),
        checkpoint.brokerArtifactUses(),
        checkpoint.brokerArtifactUtilities(),
        checkpoint.brokerArtifactInvalidations());
    inspirationLedger.loadHistorical(checkpoint.inspirationOutcomes());

    routes.clear();
    for (DesktopSolveCheckpoint.RouteCheckpoint saved : checkpoint.routes()) {
      AgentRuntime author = requireAgent(saved.authorAgentId());
      RiskAssessment risk =
          routeTeam.classifyRisk(
              new RouteTeam.RiskSignals(
                  !saved.strategy().criticalClaims().isEmpty(),
                  false,
                  true,
                  !saved.strategy().computationHints().isEmpty(),
                  false,
                  saved.structuralReview() != null
                      && saved.structuralReview().verdict() != VerificationVerdict.PASS,
                  false,
                  saved.revisionCount() > 0,
                  true,
                  !saved.strategy().computationHints().isEmpty()));
      RouteTeamPlan plan = teamFactory.plan(saved.routeId(), author.id(), risk);
      RouteState route =
          new RouteState(
              saved.routeId(), author, saved.strategy(), plan, saved.revisionCount());
      route.attempt = saved.attempt();
      route.skepticReview = saved.skepticReview();
      route.toolAudit = saved.toolAudit();
      route.structuralReview = saved.structuralReview();
      route.detailedReview = saved.detailedReview();
      route.crossProviderReview = saved.crossProviderReview();
      route.teamResult = saved.teamResult();
      route.escalation = saved.escalation();
      route.validationExecution = saved.validationExecution();
      route.status = saved.status();
      route.failureReason = saved.failureReason();
      route.checkpoint = saved.checkpoint();
      route.delta = saved.delta();
      route.deltaId = saved.deltaId();
      route.failure = saved.failure();
      route.claimIds.addAll(saved.claimIds());
      route.artifactIds.addAll(saved.artifactIds());
      route.salvagedVerifiedClaimIds.addAll(saved.salvagedVerifiedClaimIds());
      route.salvagedCounterexampleIds.addAll(saved.salvagedCounterexampleIds());
      route.rejectedClaimIds.addAll(saved.rejectedClaimIds());
      route.uncertainClaimIds.addAll(saved.uncertainClaimIds());
      route.courtCaseIds.addAll(saved.courtCaseIds());
      route.proofInvalidOpenClaimIds.addAll(saved.proofInvalidOpenClaimIds());
      route.repairExhaustedClaimIds.addAll(saved.repairExhaustedClaimIds());
      route.claimReview = saved.claimReview();
      route.segmentCount = saved.segmentCount();
      route.noProgressSegments = saved.noProgressSegments();
      route.cooldownUntilRound = saved.cooldownUntilRound();
      route.metaAbandoned = saved.metaAbandoned();
      route.metaControlReason = saved.metaControlReason();
      route.revisionHistory.addAll(saved.revisionHistory());
      route.focusObligationId = saved.focusObligationId();
      route.focusedCanonicalTargetId = saved.focusedCanonicalTargetId();
      route.focusedBottleneckFamilyId = saved.focusedBottleneckFamilyId();
      route.focusSource = saved.focusSource();
      route.latestResearchCheckpointId = saved.latestResearchCheckpointId();
      route.activeResearchFindingIds.addAll(saved.activeResearchFindingIds());
      route.lastCheckpointedProviderCallId = saved.lastCheckpointedProviderCallId();
      route.checkpointRecoveryCount = saved.checkpointRecoveryCount();
      route.pendingFindingReconciliation = saved.pendingFindingReconciliation();
      refreshRouteResearchProjection(route);
      route.reviewComplete = saved.reviewComplete();
      route.checkpointProcessed = saved.checkpointProcessed();
      route.integrated = saved.integrated();
      route.activeSemanticPivotId = saved.activeSemanticPivotId();
      route.semanticPivotIds.addAll(saved.semanticPivotIds());
      route.activeStrategyEpochId = saved.activeStrategyEpochId();
      route.retiredActiveClaimIds.addAll(saved.retiredActiveClaimIds());
      route.pendingPivotProposedClaims.addAll(saved.pendingPivotProposedClaims());
      route.retiredStrategyFocusObligationIds.addAll(
          saved.retiredStrategyFocusObligationIds());
      if (!saved.activeMathematicalObjectIds().isEmpty()) {
        route.activeMathematicalObjectIds.clear();
        route.activeMathematicalObjectIds.addAll(saved.activeMathematicalObjectIds());
      }
      route.activeDirectionSignature = saved.activeDirectionSignature();
      if (!"verified".equals(route.status)
          && negativeKnowledgeBlocksStrategy(
              route.strategy,
              strategyBlueprints.get(route.strategy.strategyId()),
              NegativeKnowledgeSurface.RESTORE_REVALIDATION)) {
        route.status = "abandoned";
        route.failureReason =
            "restored route was closed because its required dependency conflicts with Negative Memory";
        route.attempt = null;
        route.reviewComplete = false;
        route.checkpointProcessed = false;
        route.integrated = false;
        restoreBlockedStrategyIds.add(route.strategy.strategyId());
        event(
            "restored_route_invalidated",
            "committed_checkpoint",
            route.author.id(),
            "rejected",
            route.failureReason,
            "strategy://" + route.strategy.strategyId());
      }
      if (saved.nearMiss() != null) {
        NearMissLedger.NearMiss restoredNearMiss =
            nearMisses.record(saved.nearMiss().candidate(), true);
        if (restoredNearMiss != null) {
          route.nearMissId = restoredNearMiss.id();
          if (saved.nearMiss().repaired()) {
            nearMisses.markRepaired(restoredNearMiss.id(), "checkpoint-restore");
          }
        }
      }
      route.revisionHistory.stream()
          .map(DesktopSolveCheckpoint.AttemptRevisionCheckpoint::checkpoint)
          .filter(Objects::nonNull)
          .forEach(checkpoints::seed);
      if (route.checkpoint != null) {
        checkpoints.seed(route.checkpoint);
      }
      if (route.failure == null
          && route.failureReason != null
          && !route.failureReason.isBlank()
          && !"verified".equals(route.status)) {
        route.failure =
            failureControl.classify(
                route.routeId,
                route.attempt == null
                    ? route.strategy.strategyId()
                    : route.attempt.attemptId(),
                route.failureReason,
                route.detailedReview == null ? null : route.detailedReview.firstErrorStep(),
                reviewEvidence(route));
      }
      routes.add(route);
    }
    reconcileClaimSalvageProjections();
    Set<String> restoreBlockedRouteIds =
        routes.stream()
            .filter(route -> restoreBlockedStrategyIds.contains(route.strategy.strategyId()))
            .map(route -> route.routeId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    restoreBlockedRouteIds.stream()
        .map(proofGraph::blockRouteObligationsForNegativeKnowledge)
        .flatMap(List::stream)
        .forEach(restoreBlockedObligations::add);
    pendingProofTasks.removeIf(
        task ->
            restoreBlockedRouteIds.contains(task.routeId())
                || restoreBlockedObligations.contains(task.obligationId()));
    restoreStrategyArchive(checkpoint);
    reconcileSemanticPivotProjection();
    reconcileComputationAuthorityProjection();
    restoreBlockedStrategyIds.stream()
        .filter(strategyArchive.lineage()::containsKey)
        .forEach(
            strategyId ->
                strategyArchive.rejectChild(
                    strategyId, "negative-knowledge://restore-revalidation"));
    rebuildRouteRegistry();
    reconcileResearchEpochAuthorityCommitsAfterRestore();
    if (checkpoint.schemaVersion() < 17) {
      mathematicalArtifactBroker.migrateLegacy(
          checkpoint.messageStore(),
          problemHash,
          rootGoal == null ? problemHash : rootGoal.sourceStatementHash(),
          routeMathematicalNeedProfiles(),
          roundIndex.get());
    }
    reconsiderDeferredExpansions();
    budgetScheduler.restore(checkpoint);
    var resumeDecision =
        proofControl
            .resume()
            .plan(
                runId,
                CanonicalJson.stableHash(ContractObjectMapper.toTree(checkpoint)),
                checkpoint.terminal(),
                routes.stream()
                    .filter(route -> !route.integrated && route.attempt != null)
                    .map(route -> route.routeId)
                    .toList(),
                routes.stream()
                    .filter(route -> route.attempt == null)
                    .map(route -> route.routeId)
                    .toList(),
                List.of(),
                false);
    event(
        "resume_planned",
        "committed_checkpoint",
        null,
        "completed",
        resumeDecision.decision().name() + ": " + resumeDecision.reason(),
        resumeDecision.id());
  }

  private void reconcileComputationAuthorityProjection() {
    for (ComputationTrace saved : List.copyOf(computationTraces)) {
      if (saved.targetBinding() == null) {
        continue;
      }
      ComputationExecutionRecord record =
          computation.executionService().executions().records().stream()
              .filter(value -> value.routeId().equals(saved.routeId()))
              .filter(value -> value.requestHash().equals(saved.spec().requestHash()))
              .findFirst()
              .orElse(null);
      if (record == null
          || (record.status() != ComputationExecutionStatus.PROJECTION_READY
              && record.status()
                  != ComputationExecutionStatus.AUTHORITY_MUTATION_DURABLE
              && record.status() != ComputationExecutionStatus.AUTHORITY_APPLIED)) {
        continue;
      }
      RouteState route =
          routes.stream().filter(value -> value.routeId.equals(saved.routeId())).findFirst().orElse(null);
      if (route == null) {
        continue;
      }
      ComputationExecutionOutcome outcome =
          computation
              .executionService()
              .recoverOutcome(saved.spec(), saved.program(), saved.routeId())
              .orElse(null);
      if (outcome == null) {
        continue;
      }
      ComputationTrace restored =
          saved.result() == null
              ? new ComputationTrace(
                  saved.routeId(),
                  saved.spec(),
                  saved.decision(),
                  saved.program(),
                  outcome.result(),
                  saved.targetBinding(),
                  outcome.authority(),
                  outcome.verificationReceipt().valid())
              : saved;
      upsertComputationTrace(restored);
      Optional<ComputationAuthorityMutationReceipt> mutation =
          computation.executionService().authorityMutationReceipt(outcome.executionId());
      if (mutation.isPresent() && authorityMutationPresent(mutation.orElseThrow())) {
        computation
            .executionService()
            .completeAuthorityApplication(outcome.executionId(), roundIndex.get());
        continue;
      }
      applyComputationAuthorityAtomically(route, restored, outcome);
    }
  }

  private boolean authorityMutationPresent(ComputationAuthorityMutationReceipt receipt) {
    boolean factPresent =
        receipt.factMessageId().isEmpty()
            || typedMemory.facts().stream()
                .anyMatch(value -> value.messageId().equals(receipt.factMessageId()));
    boolean counterexamplePresent =
        receipt.counterexampleMessageId().isEmpty()
            || typedMemory.negatives().stream()
                .anyMatch(value -> value.messageId().equals(receipt.counterexampleMessageId()));
    boolean closurePresent =
        receipt.closedObligationId().isEmpty()
            || findObligation(receipt.closedObligationId())
                .map(value -> "closed".equals(value.status()))
                .orElse(false);
    boolean refutationPresent =
        receipt.refutedObligationId().isEmpty()
            || findObligation(receipt.refutedObligationId())
                .map(value -> "refuted".equals(value.status()))
                .orElse(false);
    return factPresent && counterexamplePresent && closurePresent && refutationPresent;
  }

  private void restoreStrategyArchive(DesktopSolveCheckpoint checkpoint) {
    if (checkpoint.strategyArchive() != null) {
      strategyArchive.restore(checkpoint.strategyArchive());
      return;
    }

    for (DesktopSolveCheckpoint.RouteCheckpoint saved : checkpoint.routes()) {
      List<StrategyCard> chain = new ArrayList<>();
      saved.revisionHistory().stream()
          .map(DesktopSolveCheckpoint.AttemptRevisionCheckpoint::strategy)
          .filter(Objects::nonNull)
          .forEach(chain::add);
      if (chain.isEmpty()
          || !chain.getLast().strategyId().equals(saved.strategy().strategyId())) {
        chain.add(saved.strategy());
      }
      if (chain.isEmpty()) {
        continue;
      }

      StrategyCard root = chain.getFirst();
      if (!strategyArchive.lineage().containsKey(root.strategyId())) {
        strategyArchive.archive(
            controlStrategy(root, saved.routeId()), "strategy://" + root.strategyId(), 0);
      }
      String parentId = root.strategyId();
      for (int index = 1; index < chain.size(); index++) {
        StrategyCard child = chain.get(index);
        if (!strategyArchive.lineage().containsKey(child.strategyId())) {
          String action = saved.revisionHistory().get(index - 1).action();
          strategyArchive.registerChild(
              controlStrategy(child, saved.routeId()),
              parentId,
              "DEEPEN".equals(action)
                  ? StrategyArchive.RevisionReason.BRIDGE_INSERTION
                  : StrategyArchive.RevisionReason.PLAN_FAILURE);
        }
        parentId = child.strategyId();
      }
    }

    for (int index = 0; index < admittedStrategies.size(); index++) {
      StrategyCard strategy = admittedStrategies.get(index);
      if (!strategyArchive.lineage().containsKey(strategy.strategyId())) {
        strategyArchive.archive(
            controlStrategy(strategy, "route-" + (index + 1)),
            "strategy://" + strategy.strategyId(),
            0);
      }
    }
  }

  private void reconcileSemanticPivotProjection() {
    Map<String, RouteState> byRoute =
        routes.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    route -> route.routeId,
                    java.util.function.Function.identity(),
                    (left, right) -> left,
                    LinkedHashMap::new));
    for (SemanticPivotRecord record : semanticPivots.ledger().records()) {
      if (record.status() == PivotDeltaStatus.APPLYING) {
        throw new IllegalStateException(
            "checkpoint contains a partial semantic pivot apply frontier");
      }
      if (record.status() != PivotDeltaStatus.APPLIED
          && record.status() != PivotDeltaStatus.EVALUATED) {
        continue;
      }
      if (record.applyReceipt() == null || !record.applyReceipt().applied()) {
        throw new IllegalStateException("applied semantic pivot is missing its receipt");
      }
      RouteState route = byRoute.get(record.delta().routeId());
      if (route == null
          || !route.strategy.strategyId().equals(record.delta().proposedStrategyId())
          || !strategyArchive.lineage().containsKey(record.delta().sourceStrategyId())
          || !strategyArchive.lineage().containsKey(record.delta().proposedStrategyId())) {
        throw new IllegalStateException("semantic pivot route or strategy epoch projection is invalid");
      }
      route.semanticPivotIds.add(record.pivotId());
      route.activeSemanticPivotId = record.pivotId();
      route.activeStrategyEpochId = record.delta().proposedStrategyId();
    }
  }

  private void reconcileClaimSalvageProjections() {
    Map<String, RouteState> byRoute =
        routes.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    route -> route.routeId,
                    java.util.function.Function.identity(),
                    (left, right) -> left,
                    LinkedHashMap::new));
    for (AttemptArtifactRecord artifact : attemptArtifacts.records()) {
      if (artifact.status() != AttemptArtifactStatus.PROMOTED_FACT
          && artifact.status() != AttemptArtifactStatus.APPLIED_COUNTEREXAMPLE) {
        continue;
      }
      MessageEnvelope fact =
          typedMemory
              .find(artifact.promotedMessageId())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "restored promoted artifact is missing its Fact projection"));
      if (fact.memoryTier() != MemoryTier.FACT
          || fact.verificationStatus() != ClaimStatus.VERIFIED) {
        throw new IllegalStateException(
            "restored promoted artifact has a non-authoritative Fact projection");
      }
      RouteState route = byRoute.get(artifact.routeId());
      if (route == null) {
        continue;
      }
      addDistinct(route.artifactIds, artifact.artifactId());
      addDistinct(route.claimIds, fact.messageId());
      if (artifact.status() == AttemptArtifactStatus.APPLIED_COUNTEREXAMPLE) {
        addDistinct(route.salvagedCounterexampleIds, artifact.claimId());
      } else if (artifact.kind() == AttemptArtifactKind.LOCAL_LEMMA) {
        addDistinct(route.salvagedVerifiedClaimIds, artifact.claimId());
      }
    }
  }

  private void rebuildVersionSixClaimLifecycle(DesktopSolveCheckpoint checkpoint) {
    if (checkpoint.schemaVersion() >= 7 || !proofControl.claims().entries().isEmpty()) {
      return;
    }
    Map<String, MessageEnvelope> facts =
        typedMemory.facts().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    MessageEnvelope::messageId,
                    java.util.function.Function.identity(),
                    (left, right) -> left,
                    LinkedHashMap::new));
    Map<String, MessageEnvelope> graphClaims =
        proofGraph.claimNodes().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    MessageEnvelope::messageId,
                    java.util.function.Function.identity(),
                    (left, right) -> left,
                    LinkedHashMap::new));
    Map<String, DesktopSolveCheckpoint.RouteCheckpoint> routesById =
        checkpoint.routes().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    DesktopSolveCheckpoint.RouteCheckpoint::routeId,
                    java.util.function.Function.identity(),
                    (left, right) -> left,
                    LinkedHashMap::new));
    for (ClaimCard claim : lemmaMemory.verified()) {
      MessageEnvelope fact = facts.get(claim.claimId());
      MessageEnvelope graphClaim = graphClaims.get(claim.claimId());
      if (fact == null
          || !problemHash.equals(fact.problemHash())
          || !matchingLegacyAuthority(claim, fact, graphClaim)) {
        continue;
      }
      DesktopSolveCheckpoint.RouteCheckpoint route = routesById.get(fact.sourceRouteId());
      AttemptStatus sourceAttemptStatus =
          route == null || route.attempt() == null
              ? AttemptStatus.PARTIAL
              : route.attempt().status();
      String sourceRouteStatus =
          route == null || route.status() == null || route.status().isBlank()
              ? "legacy_verified_fact"
              : route.status();
      AttemptArtifactKind kind =
          fact.messageType() == MessageType.COUNTEREXAMPLE
              ? AttemptArtifactKind.COUNTEREXAMPLE
              : claim.tags().contains("route_theorem")
                  ? AttemptArtifactKind.ROUTE_THEOREM
                  : AttemptArtifactKind.LOCAL_LEMMA;
      String sourceAttemptId =
          claim.sourceAttemptId() == null || claim.sourceAttemptId().isBlank()
              ? "legacy-attempt-" + claim.claimId()
              : claim.sourceAttemptId();
      proofControl
          .claims()
          .restoreLegacyVerifiedFact(
              claim.claimId(),
              sourceAttemptId,
              claim.sourceDeltaId(),
              List.of(),
              kind,
              sourceAttemptStatus,
              sourceRouteStatus,
              fact.messageId());
    }
  }

  private void rebuildVersionFifteenClaimProofRevisions(DesktopSolveCheckpoint checkpoint) {
    if (checkpoint.schemaVersion() >= 16 || !claimProofRevisions.records().isEmpty()) {
      return;
    }
    Map<String, DesktopSolveCheckpoint.RouteCheckpoint> byAttempt =
        checkpoint.routes().stream()
            .filter(route -> route.attempt() != null)
            .collect(
                java.util.stream.Collectors.toMap(
                    route -> route.attempt().attemptId(),
                    java.util.function.Function.identity(),
                    (left, right) -> left,
                    LinkedHashMap::new));
    for (ClaimCard claim : lemmaMemory.claims()) {
      if (claim.proofSteps().isEmpty()
          || claim.sourceAttemptId() == null
          || claim.sourceAttemptId().isBlank()) {
        continue;
      }
      DesktopSolveCheckpoint.RouteCheckpoint route = byAttempt.get(claim.sourceAttemptId());
      String routeId = route == null ? "legacy-route-" + claim.claimId() : route.routeId();
      String authorId =
          claim.sourceAgentId() == null || claim.sourceAgentId().isBlank()
              ? route == null ? "legacy-author" : route.authorAgentId()
              : claim.sourceAgentId();
      ClaimCard bound =
          new ClaimCard(
              claim.assumptions(),
              claim.claimId(),
              claim.conclusion(),
              claim.contentHash(),
              claim.counterexampleRisk(),
              claim.dependencies(),
              claim.dependencyRefs(),
              claim.evidenceRefs(),
              claim.proofSteps(),
              claim.scopeLimitations(),
              claim.selfConfidence(),
              authorId,
              claim.sourceAttemptId(),
              claim.sourceDeltaId(),
              claim.statement(),
              claim.status(),
              claim.tags(),
              claim.verificationConfidence());
      FrozenClaimSnapshot frozen =
          claimFreezeService.freeze(
              problemHash,
              rootGoal().sourceStatementHash(),
              routeId,
              bound,
              FrozenClaimSemanticContext.legacyIncomplete(effectiveClaimScope(bound)));
      claimProofRevisions.createOriginal(frozen, bound.proofSteps(), bound.evidenceRefs());
    }
  }

  private static boolean matchingLegacyAuthority(
      ClaimCard claim, MessageEnvelope fact, MessageEnvelope graphClaim) {
    return fact != null
        && graphClaim != null
        && fact.problemHash().equals(graphClaim.problemHash())
        && fact.statement().equals(claim.statement())
        && fact.assumptions().equals(claim.assumptions())
        && fact.conclusion().equals(claim.conclusion())
        && fact.memoryTier() == MemoryTier.FACT
        && fact.verificationStatus() == ClaimStatus.VERIFIED
        && graphClaim.memoryTier() == MemoryTier.FACT
        && graphClaim.verificationStatus() == ClaimStatus.VERIFIED
        && fact.contentHash().equals(graphClaim.contentHash());
  }

  private void persist(String stage, boolean terminal) throws IOException {
    currentStage = stage;
    List<DesktopSolveCheckpoint.RouteCheckpoint> routeState =
        routes.stream()
            .map(
                route ->
                    new DesktopSolveCheckpoint.RouteCheckpoint(
                        route.routeId,
                        route.author.id(),
                        route.strategy,
                        route.attempt,
                        route.skepticReview,
                        route.toolAudit,
                        route.structuralReview,
                        route.detailedReview,
                        route.crossProviderReview,
                        route.teamResult,
                        route.escalation,
                        route.validationExecution,
                        route.status,
                        route.failureReason,
                        route.checkpoint,
                        route.delta,
                        route.deltaId,
                        route.failure,
                        nearMissFor(route),
                        route.claimIds,
                        route.artifactIds,
                        route.salvagedVerifiedClaimIds,
                        route.salvagedCounterexampleIds,
                        route.rejectedClaimIds,
                        route.uncertainClaimIds,
                        new ArrayList<>(route.courtCaseIds),
                        new ArrayList<>(route.proofInvalidOpenClaimIds),
                        new ArrayList<>(route.repairExhaustedClaimIds),
                        route.claimReview,
                        route.segmentCount,
                        route.noProgressSegments,
                        route.revisionCount,
                        route.cooldownUntilRound,
                        route.metaAbandoned,
                        route.metaControlReason,
                        route.revisionHistory,
                        route.focusObligationId,
                        route.focusedCanonicalTargetId,
                        route.focusedBottleneckFamilyId,
                        route.focusSource,
                        route.latestResearchCheckpointId,
                        route.activeResearchFindingIds,
                        route.lastCheckpointedProviderCallId,
                        route.checkpointRecoveryCount,
                        route.pendingFindingReconciliation,
                        route.reviewComplete,
                        route.checkpointProcessed,
                        route.integrated,
                        route.activeSemanticPivotId,
                        new ArrayList<>(route.semanticPivotIds),
                        route.activeStrategyEpochId,
                        new ArrayList<>(route.retiredActiveClaimIds),
                        new ArrayList<>(route.pendingPivotProposedClaims),
                        new ArrayList<>(route.retiredStrategyFocusObligationIds),
                        new ArrayList<>(route.activeMathematicalObjectIds),
                        route.activeDirectionSignature))
            .toList();
    List<DesktopSolveCheckpoint.ComputationCheckpoint> computations =
        computationTraces.stream()
            .map(
                trace ->
                    new DesktopSolveCheckpoint.ComputationCheckpoint(
                        trace.routeId(),
                        trace.spec(),
                        trace.decision(),
                        trace.program(),
                        trace.result(),
                        trace.targetObligationId(),
                        trace.authority(),
                        trace.replayValid(),
                        trace.targetBinding()))
            .toList();
    ComputationExecutionState computationState = computation.snapshot();
    DesktopSolveCheckpoint checkpoint =
        new DesktopSolveCheckpoint(
            STATE_SCHEMA_VERSION,
            runId,
            problemHash,
            stage,
            roundIndex.get(),
            ledger.totals(),
            frozenProblem,
            triage,
            strategySet,
            admittedStrategies,
            nextStrategyIndex.get(),
            routeState,
            inspirationProposals,
            inspirationOutcomes,
            computations,
            lemmaMemory.snapshot(),
            typedMemory.snapshot(),
            proofGraph.snapshot(),
            attemptArtifacts.snapshot(),
            proofControl.claims().snapshot(),
            claimProofRevisions.snapshot(),
            claimCourt.snapshot(),
            claimCourtExecutions.snapshot(),
            researchCheckpointLedger().snapshot(),
            researchEpochs.snapshot(),
            researchTasks.snapshot(),
            researchResults.snapshot(),
            researchAuthorityMutations.snapshot(),
            pool.leaseSnapshot(),
            pool.concurrencyTelemetrySnapshot(),
            messageRepository.snapshot(),
            mathematicalArtifactBroker.registrySnapshot(),
            mathematicalArtifactBroker.publicationSnapshot(),
            mathematicalArtifactBroker.deliverySnapshot(),
            mathematicalArtifactBroker.receiptSnapshot(),
            mathematicalArtifactBroker.useSnapshot(),
            mathematicalArtifactBroker.utilitySnapshot(),
            mathematicalArtifactBroker.invalidationSnapshot(),
            proofDebtHistory,
            strategyArchive.snapshot(),
            strategyBlueprints,
            goalLinks,
            strategyCandidates.snapshot(),
            strategyMechanisms.snapshot(),
            strategyPreflights.snapshot(),
            strategyPortfolios.snapshot(),
            portfolioReplenishments.snapshot(),
            metaPivots,
            semanticPivots.ledger().snapshot(),
            inspirationProgress,
            pendingMetaReview,
            pendingProofTasks,
            schedulerStop,
            workflowCursor,
            finalProof,
            finalReview,
            finalReviewReports,
            finalValidationPassed,
            finalValidationExecution,
            formalizationCoverage,
            computationAudits,
            computationState.capabilities(),
            computationState.executions(),
            computationState.artifacts(),
            computationState.verifications(),
            computationState.outcomeReceipts(),
            new ArrayList<>(completedStages),
            proofGraphConvergence.snapshot(),
            deferredExpansions.snapshot(),
            runStateAnchor,
            budgetRuntime.decisionSnapshot(),
            budgetRuntime.envelopeSnapshot(),
            budgetRuntime.reservationSnapshot(),
            budgetRuntime.usageSnapshot(),
            budgetRuntime.pricing(),
            budgetRuntime.zeroGainSnapshot(),
            budgetRuntime.certifiedGainSnapshot(),
            terminal);
    Path structured = runDirectory.resolve("structured");
    Files.createDirectories(structured);
    writeJsonAtomically(statePath(), checkpoint);
    DesktopDurableBoundaryObserver.from(progress).afterDurableBoundary(
        DesktopDurableBoundary.CHECKPOINT_V22, statePath());
    if ("research_epoch_committed".equals(stage)) {
      failAuthoritativeConcurrencyAt(
          AuthoritativeConcurrencyFailurePoint.AFTER_ATOMIC_CHECKPOINT_MOVE);
    }
    failSemanticPivotAt(SemanticPivotFailurePoint.DURING_CHECKPOINT_PERSIST);
    writeJsonAtomically(structured.resolve("proof-graph.json"), checkpoint.proofGraph());
    writeJsonAtomically(structured.resolve("lemma-memory.json"), checkpoint.lemmaMemory());
    writeJsonAtomically(structured.resolve("typed-memory.json"), checkpoint.typedMemory());
    writeJsonAtomically(
        structured.resolve("attempt-artifacts.json"), checkpoint.attemptArtifacts());
    writeJsonAtomically(
        structured.resolve("claim-lifecycle.json"), checkpoint.claimLifecycle());
    writeJsonAtomically(
        structured.resolve("research-checkpoints.json"), checkpoint.researchCheckpoints());
    writeJsonAtomically(
        structured.resolve("research-finding-audit.json"),
        checkpoint.researchCheckpoints().audit());
    writeJsonAtomically(structured.resolve("research-epochs.json"), checkpoint.researchEpochs());
    writeJsonAtomically(structured.resolve("research-tasks.json"), checkpoint.researchTasks());
    writeJsonAtomically(structured.resolve("research-results.json"), checkpoint.researchResults());
    writeJsonAtomically(
        structured.resolve("research-authority-mutations.json"),
        checkpoint.researchAuthorityMutations());
    writeJsonAtomically(structured.resolve("agent-leases.json"), checkpoint.agentLeases());
    writeJsonAtomically(
        structured.resolve("concurrency-telemetry.json"), checkpoint.concurrencyTelemetry());
    writeJsonAtomically(
        structured.resolve("proof-graph-convergence.json"),
        checkpoint.proofGraphConvergence());
    writeJsonAtomically(
        structured.resolve("deferred-proof-expansions.json"),
        checkpoint.deferredExpansions());
    writeJsonAtomically(structured.resolve("message-store.json"), checkpoint.messageStore());
    writeJsonAtomically(
        structured.resolve("broker-artifact-registry.json"), checkpoint.brokerArtifactRegistry());
    writeJsonAtomically(
        structured.resolve("broker-artifact-publications.json"),
        checkpoint.brokerArtifactPublications());
    writeJsonAtomically(
        structured.resolve("broker-artifact-deliveries.json"),
        checkpoint.brokerArtifactDeliveries());
    writeJsonAtomically(
        structured.resolve("broker-artifact-receipts.json"), checkpoint.brokerArtifactReceipts());
    writeJsonAtomically(
        structured.resolve("broker-artifact-uses.json"), checkpoint.brokerArtifactUses());
    writeJsonAtomically(
        structured.resolve("broker-artifact-utilities.json"), checkpoint.brokerArtifactUtilities());
    writeJsonAtomically(
        structured.resolve("broker-artifact-invalidations.json"),
        checkpoint.brokerArtifactInvalidations());
    writeJsonAtomically(structured.resolve("strategy-archive.json"), checkpoint.strategyArchive());
    writeJsonAtomically(
        structured.resolve("inspiration-progress.json"), checkpoint.inspirationProgress());
    writeJsonAtomically(
        structured.resolve("inspiration-outcomes.json"), checkpoint.inspirationOutcomes());
    writeJsonAtomically(structured.resolve("strategy-blueprints.json"), checkpoint.strategyBlueprints());
    writeJsonAtomically(structured.resolve("goal-links.json"), checkpoint.goalLinks());
    writeJsonAtomically(
        structured.resolve("strategy-candidates.json"), checkpoint.strategyCandidates());
    writeJsonAtomically(
        structured.resolve("strategy-mechanisms.json"), checkpoint.strategyMechanisms());
    writeJsonAtomically(
        structured.resolve("strategy-preflights.json"), checkpoint.strategyPreflights());
    writeJsonAtomically(
        structured.resolve("strategy-portfolios.json"), checkpoint.strategyPortfolios());
    writeJsonAtomically(
        structured.resolve("portfolio-replenishments.json"),
        checkpoint.portfolioReplenishments());
    writeJsonAtomically(structured.resolve("meta-pivots.json"), checkpoint.metaPivots());
    writeJsonAtomically(
        structured.resolve("semantic-pivots.json"), checkpoint.semanticPivots());
    writeJsonAtomically(structured.resolve("computation-audits.json"), checkpoint.computationAudits());
    writeJsonAtomically(
        structured.resolve("final-validation-execution.json"), checkpoint.finalValidationExecution());
    writeJsonAtomically(
        structured.resolve("final-review-reports.json"), checkpoint.finalReviewReports());
    writeJsonAtomically(
        structured.resolve("formalization-coverage.json"), checkpoint.formalizationCoverage());
  }

  private NearMissLedger.NearMiss nearMissFor(RouteState route) {
    if (route.nearMissId == null || route.nearMissId.isBlank()) {
      return null;
    }
    return nearMisses.relevant(route.routeId).stream()
        .filter(value -> value.id().equals(route.nearMissId))
        .findFirst()
        .orElse(null);
  }

  @SuppressFBWarnings(
      value = "PATH_TRAVERSAL_IN",
      justification =
          "Checkpoint destinations are fixed children of the validated per-run directory; the "
              + "temporary file must share that directory for an atomic replacement.")
  private static void writeJsonAtomically(Path destination, Object value) throws IOException {
    Path directory = Objects.requireNonNull(destination.getParent(), "checkpoint parent directory");
    Path fileName = Objects.requireNonNull(destination.getFileName(), "checkpoint file name");
    Files.createDirectories(directory);
    Path temporary = Files.createTempFile(directory, "." + fileName, ".tmp");
    try {
      Files.writeString(
          temporary, ContractObjectMapper.write(value), StandardCharsets.UTF_8);
      replaceCheckpointWithRetry(temporary, destination);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static void replaceCheckpointWithRetry(Path temporary, Path destination)
      throws IOException {
    IOException lastFailure = null;
    for (int attempt = 1; attempt <= CHECKPOINT_MOVE_ATTEMPTS; attempt++) {
      try {
        Files.move(
            temporary,
            destination,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
        return;
      } catch (AtomicMoveNotSupportedException unsupported) {
        try {
          Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
          return;
        } catch (IOException exception) {
          lastFailure = exception;
        }
      } catch (IOException exception) {
        lastFailure = exception;
      }
      if (attempt < CHECKPOINT_MOVE_ATTEMPTS) {
        try {
          Thread.sleep(CHECKPOINT_MOVE_RETRY_MILLIS * attempt);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          IOException interrupted =
              new IOException("interrupted while retrying checkpoint persistence", exception);
          interrupted.addSuppressed(lastFailure);
          throw interrupted;
        }
      }
    }
    throw lastFailure;
  }

  private synchronized void persistUnchecked(String stage, boolean terminal) {
    if (activeResearchWorker.get() != null
        || activeClaimCourtWorker.get() != null
        || activeEpochAuthorityCommit.get() != null) {
      return;
    }
    try {
      persist(stage, terminal);
    } catch (IOException exception) {
      throw new IllegalStateException("semantic solve checkpoint could not be persisted", exception);
    }
  }

  private Path statePath() {
    return runDirectory.resolve("structured").resolve("desktop-solve-state.json");
  }

  private RunExecutionBackend.RunExecutionResult resultFromCurrentState() {
    boolean passed =
        finalProof != null
            && finalReview != null
            && finalValidationPassed
            && finalReview.verdict() == VerificationVerdict.PASS
            && finalReview.problemIntegrityOk();
    String report = renderReport(finalProof, finalReview);
    return new RunExecutionBackend.RunExecutionResult(
        passed ? "completed" : "unverified",
        "report",
        passed
            ? "完整迁移求解管线已完成，且全部独立终审门禁通过。"
            : "完整迁移求解管线已执行，但最终独立验证未全部通过。",
        routeViews(),
        verifiedClaimIds(),
        DesktopLiveRunExecutionBackend.boundedUtf8(report, MAX_REPORT_BYTES),
        safeInt(ledger.totals().calls()),
        DesktopLiveRunExecutionBackend.executionUsage(ledger.totals()));
  }

  private List<RouteState> verifiedRoutes() {
    return routes.stream()
        .filter(route -> "verified".equals(route.status))
        .filter(route -> route.attempt != null)
        .toList();
  }

  private List<RouteView> routeViews() {
    return routes.stream()
        .map(
            route ->
                new RouteView(
                    route.routeId,
                    route.status,
                    "verified".equals(route.status)
                        ? "All route-team, checkpoint, claim, and graph gates passed"
                        : route.failureReason == null || route.failureReason.isBlank()
                            ? "Route has not produced an admitted proof"
                            : route.failureReason,
                    route.claimIds))
        .toList();
  }

  private List<String> verifiedClaimIds() {
    return typedMemory.facts().stream()
        .map(MessageEnvelope::messageId)
        .distinct()
        .toList();
  }

  private String renderReport(FinalProof proof, VerificationReport review) {
    StringBuilder report = new StringBuilder();
    report.append("### Runtime controls\n\n");
    report.append("- Profile: `").append(runtime.profile()).append("`\n");
    report.append("- Provider/model: `deepseek/deepseek-v4-pro`\n");
    report.append("- Reasoning effort: `max`\n");
    report.append("- Credential isolation: `5 DPAPI-backed agent keys`\n");
    report.append("- Sandboxed Python: `").append(sandboxEnabled ? "enabled" : "disabled").append("`\n");
    report.append("- Resume state: `").append(statePath()).append("`\n\n");
    report.append("### Migrated semantic pipeline\n\n");
    for (RoutePipelineFunctions.RunStage stage : RoutePipelineFunctions.FIXED_STAGES) {
      report
          .append("- ")
          .append(stage.name())
          .append(": `")
          .append(completedStages.contains(stage.name()) ? "executed" : "pending")
          .append("`\n");
    }
    report.append("\n### Route authority\n\n");
    for (RouteState route : routes) {
      report
          .append("- `")
          .append(route.routeId)
          .append("`: ")
          .append(route.status)
          .append(", segments=")
          .append(route.segmentCount)
          .append(", revisions=")
          .append(route.revisionHistory.size())
          .append(", checkpoint=`")
          .append(route.checkpoint == null ? "none" : route.checkpoint.checkpointId())
          .append("`, claims=")
          .append(route.claimIds.size())
          .append('\n');
    }
    report.append("\n### State projections\n\n");
    report.append("- Lemma claims: ").append(lemmaMemory.claims().size()).append('\n');
    report.append("- Verified reusable claims: ").append(lemmaMemory.verified().size()).append('\n');
    report.append("- Fact/Insight/Negative memory: ")
        .append(typedMemory.facts().size())
        .append('/')
        .append(typedMemory.insights().size())
        .append('/')
        .append(typedMemory.negatives().size())
        .append('\n');
    report.append("- Proof graph obligations/claims/edges: ")
        .append(proofGraph.obligations().size())
        .append('/')
        .append(proofGraph.claimNodes().size())
        .append('/')
        .append(proofGraph.edges().size())
        .append('\n');
    report.append("- Broker messages/receipts/utilities: ")
        .append(messageRepository.snapshot().messages().size())
        .append('/')
        .append(messageRepository.snapshot().receipts().size())
        .append('/')
        .append(messageRepository.snapshot().utilities().size())
        .append('\n');
    report.append("- Inspiration proposals/outcomes: ")
        .append(inspirationProposals.size())
        .append('/')
        .append(inspirationOutcomes.size())
        .append('\n');
    report.append("- Strategy blueprints/goal links/meta pivots: ")
        .append(strategyBlueprints.size())
        .append('/')
        .append(goalLinks.size())
        .append('/')
        .append(metaPivots.size())
        .append('\n');
    report.append("- Independent route mechanisms: ")
        .append(independentRouteCount())
        .append('/')
        .append(routes.size())
        .append('\n');
    report.append("- Preserved attempt revisions: ")
        .append(routes.stream().mapToInt(route -> route.revisionHistory.size()).sum())
        .append('\n');
    report.append("- Independent computation replay audits: ")
        .append(computationAudits.size())
        .append('\n');
    if (formalizationCoverage != null) {
      report.append("- Formalization coverage: ")
          .append(formalizationCoverage.formallyCertifiedStepIds().size())
          .append('/')
          .append(formalizationCoverage.totalStepCount())
          .append(" steps\n");
    }
    report.append('\n');
    if (!computationTraces.isEmpty()) {
      report.append("### Bounded computation evidence\n\n");
      for (ComputationTrace trace : computationTraces) {
        report
            .append("- `")
            .append(trace.routeId())
            .append("`: `")
            .append(trace.decision().decision().value())
            .append("` - ")
            .append(trace.decision().reason());
        if (trace.result() != null) {
          report
              .append("; outcome `")
              .append(trace.result().outcome().value())
              .append("`, cases ")
            .append(trace.result().casesChecked());
        }
        report
            .append("; authority `")
            .append(trace.authority().name().toLowerCase(Locale.ROOT))
            .append("`; replay `")
            .append(trace.replayValid() ? "passed" : "disabled")
            .append('`');
        report.append('\n');
      }
      report.append('\n');
    }
    if (schedulerStop != null) {
      report.append("### Scheduler stop diagnostics\n\n");
      report.append("- Code: `").append(schedulerStop.code()).append("`\n");
      report.append("- Detail: ").append(schedulerStop.detail()).append('\n');
      report.append("- Routes/independent/cap: ")
          .append(schedulerStop.routeCount())
          .append('/')
          .append(schedulerStop.independentRouteCount())
          .append('/')
          .append(schedulerStop.routeCap())
          .append('\n');
      report.append("- Remaining calls/tokens/cost/rounds: ")
          .append(schedulerStop.remainingCalls())
          .append('/')
          .append(schedulerStop.remainingTokens())
          .append('/')
          .append(String.format(Locale.ROOT, "%.4f", schedulerStop.remainingCostUsd()))
          .append('/')
          .append(schedulerStop.remainingRounds())
          .append('\n');
      report.append("- Open obligations: ").append(schedulerStop.openObligations()).append("\n\n");
    }
    if (proof != null) {
      report.append("### Final answer\n\n").append(proof.answer()).append("\n\n");
    } else {
      report.append("### Final answer\n\n");
      report.append("No candidate reached the final verification gate.\n\n");
    }
    if (review != null) {
      report.append("### Independent final review\n\n");
      report.append("- Verdict: `").append(review.verdict().value()).append("`\n");
      report.append("- Problem integrity: `").append(review.problemIntegrityOk()).append("`\n");
      report.append("- Full validation ladder passed: `")
          .append(finalValidationPassed)
          .append("`\n");
      if (finalValidationExecution != null) {
        report.append("- Executed validation levels: ")
            .append(
                finalValidationExecution.steps().stream()
                    .map(step -> step.level().name())
                    .toList())
            .append('\n');
      }
      report.append("- Feedback: ").append(review.conciseFeedback()).append("\n\n");
    }
    report.append("### Budget\n\n");
    report.append("- Calls used/max/remaining: ")
        .append(ledger.totals().calls())
        .append('/')
        .append(config.budget().maxTotalCalls())
        .append('/')
        .append(Math.max(0L, ledger.remainingCalls()))
        .append('\n');
    report.append("- Tokens used/max/remaining: ")
        .append(ledger.totals().totalTokens())
        .append('/')
        .append(config.budget().maxTotalTokens() == null ? "unlimited" : config.budget().maxTotalTokens())
        .append('/')
        .append(
            config.budget().maxTotalTokens() == null
                ? "unlimited"
                : Math.max(
                    0L,
                    config.budget().maxTotalTokens().longValue()
                        - ledger.totals().totalTokens()))
        .append('\n');
    report.append("- Estimated cost used/max/remaining (USD): ")
        .append(ledger.totals().costUsd().toPlainString())
        .append('/')
        .append(config.budget().maxCostUsd() == null ? "unlimited" : config.budget().maxCostUsd())
        .append('/')
        .append(
            config.budget().maxCostUsd() == null
                ? "unlimited"
                : String.format(
                    Locale.ROOT,
                    "%.4f",
                    Math.max(
                        0.0d,
                        config.budget().maxCostUsd()
                            - ledger.totals().costUsd().doubleValue())))
        .append('\n');
    report.append("- Scheduler rounds used/max/remaining: ")
        .append(roundIndex.get())
        .append('/')
        .append(config.budget().maxRounds())
        .append('/')
        .append(Math.max(0, config.budget().maxRounds() - roundIndex.get()))
        .append('\n');
    return String.valueOf(DesktopApiModel.redact(report.toString()));
  }

  private Map<String, Object> strategyGenerationGuidance() {
    Map<String, Object> guidance = new LinkedHashMap<>();
    guidance.put(
        "migration_rule",
        "Preserve the full Python proof workflow: explicit obligations, independent routes, continuations, computation authority, skeptical review, checkpoints, and final verification.");
    guidance.putAll(new GenericStrategyGenerationPolicy().guidance());
    guidance.put(
        "authority_rules",
        List.of(
            "A bounded computation may refute a universal claim but not prove it.",
            "not_refuted is an exploration hint and never a Fact.",
            "Routes sharing an unresolved load-bearing lemma count as one independent mechanism.",
            "A route must resolve its focused obligation before claiming the main theorem."));
    var domainProvider = new GreedyGcdDomainStrategySeedProvider();
    if (domainProvider.supports(frozenProblem)) {
      guidance.put("optional_domain_strategy_seeds", domainProvider.seeds(frozenProblem));
    }
    return Map.copyOf(guidance);
  }

  private boolean isGreedyGcdSequenceProblem() {
    String statement = request.problem() == null ? "" : request.problem().toLowerCase(Locale.ROOT);
    return statement.contains("gcd")
        && statement.contains("smallest integer greater")
        && (statement.contains("a_{n+1}") || statement.contains("a_n"));
  }

  private void seedProblemSpecificReasoningGuardrails() {
    if (!isGreedyGcdSequenceProblem()) {
      return;
    }
    GreedyGcdNegativeKnowledgeSeeds.all()
        .forEach(
            seed ->
                typedMemory.addDeterministicGuardrail(
                    problemHash, seed, roundIndex.get()));
    event(
        "negative_memory_seeded",
        "freeze_problem",
        "deterministic-preflight",
        "completed",
        "Seeded four known-invalid inference patterns for the greedy gcd sequence family",
        "memory://negative/greedy-gcd-guardrails");
  }

  private boolean strategyPortfolioAllowsWidening(StrategyCard strategy) {
    StrategyBlueprintCompiler.Compilation blueprint =
        strategyBlueprints.get(strategy.strategyId());
    ProofControlModels.GoalLink link = goalLinks.get(strategy.strategyId());
    if (blueprint == null || link == null) {
      return false;
    }
    StrategyMechanismSignature signature =
        strategyMechanisms
            .signature(strategy.strategyId())
            .orElseGet(
                () ->
                    strategyMechanismAnalyzer.signature(
                        problemHash,
                        rootGoal().sourceStatementHash(),
                        strategy,
                        controlStrategy(strategy, "widen-candidate"),
                        blueprint,
                        criticalClaimContexts(
                            strategy,
                            blueprint,
                            proofControl
                                .scopeGuard()
                                .extract(
                                    "goal-scope",
                                    rootGoal().sourceStatement(),
                                    List.of(),
                                    1.0d)),
                        strategyCanonicalTargetIds()));
    StrategyMechanismProfile profile =
        strategyMechanisms
            .profile(strategy.strategyId())
            .orElseGet(() -> strategyMechanismAnalyzer.profile(strategy, blueprint));
    StrategyPreflightReport preflight =
        strategyPreflights
            .find(strategy.strategyId())
            .orElseGet(() -> trustedStrategyPreflight().evaluate(problemHash, strategy));
    Set<String> activeSignatures = new LinkedHashSet<>();
    Set<String> activeUnresolvedRequiredClaims = new LinkedHashSet<>();
    StrategyCriticalClaimPreflight livePreflight = trustedStrategyPreflight();
    routes.stream()
        .filter(route -> !"abandoned".equals(route.status))
        .forEach(
            route -> {
              strategyMechanisms
                  .signature(route.strategy.strategyId())
                  .map(StrategyMechanismSignature::structuralSignatureHash)
                  .ifPresent(activeSignatures::add);
              activeUnresolvedRequiredClaims.addAll(
                  strategyPreflights
                      .find(route.strategy.strategyId())
                      .orElseGet(
                          () -> livePreflight.evaluate(problemHash, route.strategy))
                      .unresolvedRequiredClaimKeys());
            });
    var score =
        new StrategyFeasibilityCalibrator(strategyDiversityConfig)
            .calibrate(
                strategy,
                blueprint,
                preflight,
                link.confidence(),
                activeSignatures.contains(signature.structuralSignatureHash()) ? 0.0d : 1.0d,
                1.0d,
                java.util.Collections.disjoint(
                        preflight.unresolvedRequiredClaimKeys(), activeUnresolvedRequiredClaims)
                    ? 0.0d
                    : 1.0d);
    StrategyPortfolioDecision decision =
        strategyPortfolioOptimizer.optimize(
            "widen-" + roundIndex.get() + '-' + strategy.strategyId(),
            List.of(
                new StrategyPortfolioCandidate(
                    strategy, blueprint, signature, profile, preflight, score)),
            new StrategyPortfolioConstraint(
                1,
                0,
                strategyDiversityConfig.maxExactPortfolioCandidates(),
                activeSignatures,
                activeUnresolvedRequiredClaims,
                strategyDiversityConfig.minimumAdmissibleFeasibility(),
                strategyDiversityConfig.minimumBlueprintCompleteness(),
                strategyDiversityConfig.minimumRequiredClaimEvidenceForPrimaryRoute()));
    return decision.selectedStrategyIds().contains(strategy.strategyId());
  }

  private List<NegativeKnowledgeCandidate> negativeKnowledgeCandidates(
      StrategyCard strategy,
      StrategyBlueprintCompiler.Compilation blueprint,
      NegativeKnowledgeSurface surface) {
    LinkedHashSet<String> statements = new LinkedHashSet<>();
    statements.add(strategy.coreIdea());
    statements.add(strategy.bottleneck());
    statements.addAll(strategy.expectedLemmas());
    statements.addAll(strategy.prerequisites());
    Map<String, CriticalClaimContextBinding> claimBindings =
        strategy.criticalClaimContextBindings().stream()
            .collect(Collectors.toMap(CriticalClaimContextBinding::claimId, Function.identity()));
    strategy.criticalClaims().stream()
        .filter(claim -> !claimBindings.containsKey(claim.claimId()))
        .map(io.github.aililuola.mathproofmesh.contract.CriticalClaim::statement)
        .forEach(statements::add);
    if (blueprint != null) {
      blueprint.blueprint().nodes().stream()
          .filter(node -> node.kind() != ProofControlModels.BlueprintNodeKind.TARGET)
          .map(StrategyBlueprintCompiler.Node::statement)
          .forEach(statements::add);
    }
    List<NegativeKnowledgeCandidate> candidates = new ArrayList<>();
    for (String statement : statements) {
      if (statement == null || statement.isBlank()) {
        continue;
      }
      for (NegativeKnowledgeTargetType targetType : NegativeKnowledgeTargetType.values()) {
        candidates.add(
            negativeKnowledgeCandidate(
                statement,
                targetType,
                surface,
                NegativeCandidateIntent.POSITIVE_DEPENDENCY));
      }
    }
    strategy.criticalClaims().stream()
        .filter(claim -> claimBindings.containsKey(claim.claimId()))
        .forEach(
            claim -> {
              var binding = claimBindings.get(claim.claimId());
              for (NegativeKnowledgeTargetType targetType :
                  NegativeKnowledgeTargetType.values()) {
                candidates.add(
                    new NegativeKnowledgeCandidate(
                        problemHash,
                        targetType,
                        claim.statement(),
                        topology.mathNormalize(claim.statement()),
                        binding.localAssumptions(),
                        binding.quantifiers(),
                        binding.variableBindings(),
                        binding.scopeLimitations(),
                        binding.polarity(),
                        surface,
                        NegativeCandidateIntent.POSITIVE_DEPENDENCY));
              }
            });
    return List.copyOf(candidates);
  }

  private NegativeKnowledgeCandidate negativeKnowledgeCandidate(
      String statement,
      NegativeKnowledgeTargetType targetType,
      NegativeKnowledgeSurface surface,
      NegativeCandidateIntent intent) {
    return new NegativeKnowledgeCandidate(
        problemHash,
        targetType,
        statement,
        topology.mathNormalize(statement),
        List.of(),
        List.of(),
        List.of(),
        negativeKnowledgeScope(),
        surface,
        intent);
  }

  private void recordNegativeKnowledgeRejection(
      String eventType,
      String stage,
      String subject,
      NegativeKnowledgeBlockedException exception) {
    NegativeKnowledgeDecision decision = exception.decision();
    event(
        eventType,
        stage,
        null,
        "rejected",
        subject + ": " + decision.code() + " " + decision.matchedNegativeIds(),
        decision.matchedNegativeIds().isEmpty()
            ? "negative-knowledge://quarantine"
            : "negative-knowledge://" + decision.matchedNegativeIds().getFirst());
  }

  private boolean negativeKnowledgeBlocksStrategy(
      StrategyCard strategy,
      StrategyBlueprintCompiler.Compilation blueprint,
      NegativeKnowledgeSurface surface) {
    return negativeKnowledgeGate
        .evaluateAll(
            negativeKnowledgeCandidates(strategy, blueprint, surface), roundIndex.get())
        .stream()
        .anyMatch(decision -> !decision.allowed());
  }

  private void addMainGoalObligation() {
    if (proofGraph.obligations().stream()
        .anyMatch(obligation -> MAIN_GOAL_ID.equals(obligation.obligationId()))) {
      return;
    }
    String authoritativeGoal = rootGoal().sourceStatement();
    proofGraph.addRootGoalObligation(
        new ProofObligation(
            List.of(),
            1.0d,
            "",
            List.of(),
            List.of(),
            List.of(),
            null,
            ObligationKind.MAIN_GOAL,
            topology.mathNormalize(authoritativeGoal),
            MAIN_GOAL_ID,
            1.0d,
            problemHash,
            List.of(),
            List.of("run"),
            authoritativeGoal,
            "open"));
  }

  private static Map<RouteRole, List<String>> roleCandidates(List<AgentRuntime> agents) {
    EnumMap<RouteRole, List<String>> candidates = new EnumMap<>(RouteRole.class);
    for (RouteRole role : RouteRole.values()) {
      List<String> aliases =
          switch (role) {
            case PROVER -> List.of("route_prover", "explorer");
            case SKEPTIC -> List.of("route_skeptic", "counterexample_hunter", "detailed_verifier");
            case TOOL_SPECIALIST -> List.of("tool_specialist", "experimenter");
            case REFEREE -> List.of("route_referee", "detailed_verifier", "final_verifier");
            case BRIDGE_PROVER -> List.of("bridge_prover", "explorer");
            case CONFLICT_RESOLVER -> List.of("conflict_resolver", "detailed_verifier");
            case COUNTEREXAMPLE_HUNTER -> List.of("counterexample_hunter", "route_skeptic");
          };
      candidates.put(
          role,
          agents.stream()
              .filter(agent -> aliases.stream().anyMatch(agent::supportsRole))
              .map(AgentRuntime::id)
              .distinct()
              .toList());
    }
    return Map.copyOf(candidates);
  }

  private MemoryPolicy memoryPolicy() {
    var memory = config.topology().typedMemory();
    return new MemoryPolicy(
        memory.factPassThreshold(),
        memory.maxFactContext(),
        memory.maxInsightContext(),
        memory.maxNegativeContext());
  }

  private void installNegativeKnowledgeRuntime() {
    negativeKnowledgeRegistry = typedMemory.negativeKnowledgeRegistry();
    negativeKnowledgeGate = typedMemory.negativeKnowledgeAdmissionGate();
    proofGraph.configureNegativeKnowledge(
        negativeKnowledgeRegistry, roundIndex::get, negativeKnowledgeScope());
  }

  private List<String> negativeKnowledgeScope() {
    return isGreedyGcdSequenceProblem()
        ? GreedyGcdNegativeKnowledgeSeeds.problemScope()
        : List.of();
  }

  private List<String> effectiveClaimScope(ClaimCard claim) {
    return claim.scopeLimitations().isEmpty()
        ? negativeKnowledgeScope()
        : claim.scopeLimitations();
  }

  private static InspirationPolicy inspirationPolicy(SystemConfig config) {
    var source = config.topology().inspiration();
    InspirationPolicy.Mode mode =
        source.enabled()
            ? InspirationPolicy.Mode.parse(source.mode())
            : InspirationPolicy.Mode.OFF;
    EnumSet<InspirationMechanism> mechanisms = EnumSet.noneOf(InspirationMechanism.class);
    if (source.representationSwitchboard()) {
      mechanisms.add(InspirationMechanism.REPRESENTATION_SWITCH);
    }
    if (source.analogyAgent()) {
      mechanisms.add(InspirationMechanism.STRUCTURAL_ANALOGY);
    }
    if (source.auxiliaryConstructionInventor()) {
      mechanisms.add(InspirationMechanism.AUXILIARY_CONSTRUCTION);
    }
    if (source.invariantHypothesisAgent()) {
      mechanisms.add(InspirationMechanism.INVARIANT_HYPOTHESIS);
    }
    if (source.reverseGoalAnalysis()) {
      mechanisms.add(InspirationMechanism.REVERSE_GOAL_ANALYSIS);
    }
    if (source.bridgeLemmaGenerator()) {
      mechanisms.add(InspirationMechanism.BRIDGE_LEMMA);
    }
    if (source.surpriseExploration()) {
      mechanisms.add(InspirationMechanism.SURPRISE_EXPLORATION);
    }
    if (source.persistentMetaStrategist()) {
      mechanisms.add(InspirationMechanism.META_REPLAN);
    }
    if (source.inspirationComposerEnabled()) {
      mechanisms.add(InspirationMechanism.INSPIRATION_COMPOSITION);
    }
    return new InspirationPolicy(
        mode,
        mechanisms,
        new InspirationPolicy.Limits(
            Math.max(1, source.maxInspirationTasksPerRound()),
            source.maxProposalsPerTask(),
            source.maxReviewedProposalsPerTask(),
            source.maxMaterializedProposalsPerTrigger(),
            source.maxNewRoutesPerTrigger(),
            source.maxSingleAgentProposalsPerTask(),
            source.coldContextProposalsPerTask(),
            source.protectFinalizationReserve()
                ? config.scheduler().finishTransitionBufferCalls()
                : 0,
            source.warmContextMaxFacts(),
            source.warmContextMaxNegatives(),
            source.inspirationContextMaxChars()),
        new InspirationPolicy.NoveltyRules(
            source.noveltyThreshold(),
            source.mechanismDuplicateThreshold(),
            source.noveltyRepresentationWeight(),
            source.noveltyMechanismWeight(),
            source.noveltyObjectWeight(),
            source.noveltyTransformationWeight(),
            source.noveltyPrincipleWeight(),
            source.noveltyObligationWeight()),
        new InspirationPolicy.ComposerRules(
            source.composerMaxCandidatesPerRound(),
            source.composerMaxSources(),
            source.composerMaxCombinedCost(),
            source.composerRequireQuickFalsification()),
        new InspirationPolicy.SurpriseRules(
            source.surpriseBudgetFraction(),
            source.surpriseBudgetMinCalls(),
            source.surpriseBudgetMaxCalls(),
            source.maxConsecutiveSurpriseRejections(),
            source.surpriseCooldownRounds()),
        new InspirationPolicy.AdaptiveRules(
            source.adaptiveMinObservations(),
            source.adaptiveMinExplorationRate(),
            source.adaptiveUcbWeight()),
        source.requireInspirationReferee());
  }

  private static List<MetaDirectiveController.RouteControl> routeControls(int maximumRoutes) {
    List<MetaDirectiveController.RouteControl> controls = new ArrayList<>();
    for (int index = 1; index <= maximumRoutes; index++) {
      controls.add(
          new MetaDirectiveController.RouteControl("route-" + index, true, -1, false, ""));
    }
    return List.copyOf(controls);
  }

  private ProofControlModels.Mode proofControlMode() {
    var control = config.topology().proofControl();
    return control.enabled()
        ? ProofControlModels.Mode.parse(control.mode())
        : ProofControlModels.Mode.OFF;
  }

  private RootGoalContract rootGoal() {
    if (rootGoal == null) {
      throw new IllegalStateException("root goal has not been frozen");
    }
    return rootGoal;
  }

  private ProofControlModels.Obligation controlGoal() {
    return new ProofControlModels.Obligation(
        MAIN_GOAL_ID,
        rootGoal().sourceStatement(),
        ProofControlModels.ObligationKind.MAIN_GOAL,
        ProofControlModels.ObligationStatus.OPEN,
        frozenProblem.hardConstraints(),
        List.of("run"),
        1.0d,
        1.0d);
  }

  private ProofControlModels.Strategy controlStrategy(StrategyCard strategy, String routeId) {
    List<String> criticalClaims =
        strategy.criticalClaims().isEmpty()
            ? List.of(strategy.bottleneck())
            : strategy.criticalClaims().stream()
                .map(io.github.aililuola.mathproofmesh.contract.CriticalClaim::statement)
                .toList();
    List<String> expectedLemmas =
        strategy.expectedLemmas().isEmpty()
            ? List.of(
                "Apply the mechanism '"
                    + strategy.coreIdea()
                    + "' to establish the load-bearing claim: "
                    + strategy.bottleneck())
            : strategy.expectedLemmas();
    List<String> domainSources = new ArrayList<>();
    domainSources.add(strategy.title());
    domainSources.add(strategy.coreIdea());
    domainSources.add(strategy.bottleneck());
    domainSources.addAll(strategy.expectedLemmas());
    domainSources.addAll(strategy.prerequisites());
    return new ProofControlModels.Strategy(
        strategy.strategyId(),
        strategy.title(),
        strategy.coreIdea(),
        strategy.prerequisites(),
        criticalClaims,
        expectedLemmas,
        List.of(strategy.falsificationTest()),
        ProofIdentity.domainObjects(domainSources),
        routeId);
  }

  private boolean repairLegacyDomainObjectAdmission(DesktopSolveCheckpoint checkpoint) {
    boolean legacyFalseRejection =
        checkpoint.terminal()
            && CURSOR_TERMINAL.equals(workflowCursor)
            && strategySet != null
            && !strategySet.strategies().isEmpty()
            && admittedStrategies.isEmpty()
            && routes.isEmpty()
            && !strategyBlueprints.isEmpty()
            && strategyBlueprints.values().stream()
                .allMatch(
                    compilation ->
                        !compilation.reviewReasons().isEmpty()
                            && compilation.reviewReasons().stream()
                                .allMatch("domain objects are not preserved"::equals));
    if (!legacyFalseRejection) {
      return false;
    }
    admittedStrategies = List.of();
    nextStrategyIndex.set(0);
    strategyBlueprints.clear();
    goalLinks.clear();
    completedStages.remove(RoutePipelineFunctions.RunStage.ROUTE_ADMISSION_AND_TEAM.name());
    workflowCursor = CURSOR_STRATEGY;
    currentStage = "strategy_diversity";
    event(
        "checkpoint_compatibility_repair",
        "route_admission_and_team",
        null,
        "completed",
        "Re-evaluating the committed strategy set after the domain-object admission fix",
        statePath().toString());
    return true;
  }

  private void restoreStrategyDiversityState(DesktopSolveCheckpoint checkpoint) {
    if (checkpoint.schemaVersion() >= 13 || admittedStrategies.isEmpty() || rootGoal == null) {
      return;
    }
    strategyCandidates = new StrategyCandidateLedger();
    strategyMechanisms = new StrategyMechanismRegistry();
    strategyPreflights = new StrategyPreflightRegistry();
    strategyPortfolios = new StrategyPortfolioRegistry();
    portfolioReplenishments = new PortfolioReplenishmentLedger();
    ProofControlModels.Obligation goal = controlGoal();
    int order = 0;
    for (StrategyCard strategy : admittedStrategies) {
      StrategyBlueprintCompiler.Compilation blueprint =
          strategyBlueprints.computeIfAbsent(
              strategy.strategyId(),
              ignored ->
                  proofControl
                      .blueprintCompiler()
                      .compile(
                          problemHash,
                          controlStrategy(strategy, "legacy-route-" + strategy.strategyId()),
                          goal));
      ProofControlModels.Strategy control =
          controlStrategy(strategy, "legacy-route-" + strategy.strategyId());
      StrategyMechanismSignature signature =
          strategyMechanismAnalyzer.signature(
              problemHash,
              rootGoal.sourceStatementHash(),
              strategy,
              control,
              blueprint,
              criticalClaimContexts(
                  strategy,
                  blueprint,
                  proofControl
                      .scopeGuard()
                      .extract(
                          "goal-scope", rootGoal.sourceStatement(), List.of(), 1.0d),
                  false),
              strategyCanonicalTargetIds());
      strategyCandidates.capture(
          strategyPortfolioEpisodeId(), strategy.strategyId(), order++, false);
      strategyCandidates.transition(
          strategy.strategyId(),
          StrategyCandidateStatus.LEGACY_ACTIVE,
          signature.structuralSignatureHash(),
          "",
          null,
          "MIGRATED_V12_ACTIVE_WITHOUT_RETROACTIVE_PREFLIGHT");
      strategyMechanisms.register(
          strategy.strategyId(),
          signature,
          new StrategyMechanismProfile(Set.of(StrategyMechanismPrimitive.UNKNOWN)),
          true);
    }
    String episodeId = strategyPortfolioEpisodeId();
    List<String> selectedIds =
        admittedStrategies.stream().map(StrategyCard::strategyId).sorted().toList();
    Map<String, Object> identity =
        Map.of(
            "episode_id", episodeId,
            "selected", selectedIds,
            "migration", "v12-to-v13");
    String decisionHash = CanonicalJson.stableHash(identity);
    StrategyPortfolioDecision decision =
        new StrategyPortfolioDecision(
            episodeId,
            selectedIds,
            Map.of(),
            selectedIds.size() * 10.0d,
            true,
            decisionHash,
            List.of(
                new StrategyPortfolioAuditEvent(
                    "portfolio-audit-" + decisionHash.substring(0, 20),
                    episodeId,
                    "legacy_migration",
                    selectedIds,
                    "LEGACY_ACTIVE_PRESERVED_WITHOUT_PREFLIGHT")));
    strategyPortfolios.record(decision);
    String activeHash =
        CanonicalJson.stableHash(
            Map.of(
                "admitted", selectedIds,
                "routes", checkpoint.routes().stream().map(DesktopSolveCheckpoint.RouteCheckpoint::routeId).toList()));
    strategyPortfolios.recordReceipt(
        episodeId,
        new StrategyPortfolioApplyReceipt(
            "strategy-portfolio-receipt-" + activeHash.substring(0, 20),
            "legacy-portfolio-plan-" + decisionHash.substring(0, 20),
            selectedIds,
            checkpoint.routes().stream().map(DesktopSolveCheckpoint.RouteCheckpoint::routeId).toList(),
            activeHash));
  }

  private void migrateAndRevalidateLegacyCanonicalProofTasks(int schemaVersion) {
    if (schemaVersion >= 12 || pendingProofTasks.isEmpty()) {
      return;
    }
    List<DesktopSolveCheckpoint.ScheduledProofTask> migrated = new ArrayList<>();
    for (DesktopSolveCheckpoint.ScheduledProofTask task : List.copyOf(pendingProofTasks)) {
      CanonicalObligationRecord canonical =
          proofGraph.canonicalTargetForObligation(task.obligationId()).orElse(null);
      BottleneckFamilyRecord family =
          canonical == null
              ? null
              : proofGraph
                  .bottleneckFamilyForCanonical(canonical.canonicalTargetId())
                  .orElse(null);
      boolean automatic = automaticProofTaskSource(task.source());
      FocusedRecoveryActionType controlAction = proofTaskControlAction(task.source(), family);
      String familyId = family == null ? "" : family.familyId();
      String canonicalTargetId = canonical == null ? "" : canonical.canonicalTargetId();
      FocusedExpansionDecision control =
          proofGraphConvergence.decideExpansion(
              controlAction,
              canonical != null,
              proofGraph.activeCanonicalTargetCount(task.routeId()),
              proofGraph.activeCanonicalTargetCount(),
              familyId,
              canonicalTargetId);
      if (!control.allowed()) {
        if (control.deferred()) {
          deferredExpansions.record(
              problemHash,
              roundIndex.get(),
              task.routeId(),
              task.obligationId(),
              canonicalTargetId,
              controlAction,
              control);
        }
        event(
            "restored_proof_task_deferred_by_graph_control",
            "committed_checkpoint",
            null,
            "rejected",
            control.code(),
            task.taskId());
        continue;
      }
      if (schemaVersion >= 9) {
        migrated.add(task);
        continue;
      }
      ProofTaskScope scope =
          automatic && family != null
              ? ProofTaskScope.BOTTLENECK_FAMILY
              : automatic && canonical != null
                  ? ProofTaskScope.CANONICAL_TARGET
                  : ProofTaskScope.ROUTE_OCCURRENCE;
      String scopeId =
          switch (scope) {
            case BOTTLENECK_FAMILY -> family.familyId();
            case CANONICAL_TARGET -> canonical.canonicalTargetId();
            case ROUTE_OCCURRENCE -> task.routeId() + ":" + task.obligationId();
          };
      String actionKey =
          automatic ? "repair" : task.requestedAction().toLowerCase(Locale.ROOT).strip();
      if (!proofGraph.acquireCanonicalTaskLease(scope, scopeId, actionKey)) {
        continue;
      }
      migrated.add(
          new DesktopSolveCheckpoint.ScheduledProofTask(
              task.taskId(),
              task.source(),
              task.routeId(),
              task.obligationId(),
              canonical == null ? "" : canonical.canonicalTargetId(),
              family == null ? "" : family.familyId(),
              scope,
              actionKey,
              task.requestedAction(),
              task.roundCreated()));
    }
    pendingProofTasks.clear();
    pendingProofTasks.addAll(migrated);
  }

  private RouteDescriptor routeDescriptor(RouteState route) {
    String signature = topology.mathNormalize(topology.strategyText(route.strategy));
    return new RouteDescriptor(
        null,
        0,
        0,
        null,
        null,
        route.strategy.inspirationProposalId(),
        null,
        route.attempt == null ? null : route.attempt.attemptId(),
        route.checkpoint == null ? null : route.checkpoint.checkpointId(),
        List.of(signature),
        List.of(),
        null,
        List.of(),
        0,
        route.revisionCount > 0,
        route.revisionCount > 0 ? "revision " + route.revisionCount : null,
        route.routeId,
        List.of(),
        0,
        RouteStatus.ACTIVE,
        route.strategy.strategyId(),
        signature);
  }

  private AgentRuntime requireAgent(String agentId) {
    return pool.agents().stream()
        .filter(agent -> agent.id().equals(agentId))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("assigned agent is unavailable: " + agentId));
  }

  private static ExperimentProgram sandboxProgram(
      ExperimentSpec spec, SandboxProgramDraft draft) {
    ObjectNode input = JsonNodeFactory.instance.objectNode();
    input.put("type", "object");
    input.putObject("properties").putObject("seed").put("type", "integer");
    input.putArray("required").add("seed");
    input.put("additionalProperties", true);

    ObjectNode output = JsonNodeFactory.instance.objectNode();
    output.put("type", "object");
    ObjectNode properties = output.putObject("properties");
    properties.putObject("outcome").put("type", "string");
    properties.putObject("cases_checked").put("type", "integer");
    properties.putObject("scope").put("type", "object");
    properties.putObject("exact_arithmetic").put("type", "boolean");
    properties.putObject("counterexample").put("type", "object");
    properties.putObject("certificate").put("type", "object");
    output
        .putArray("required")
        .add("outcome")
        .add("cases_checked")
        .add("scope")
        .add("exact_arithmetic");
    output.put("additionalProperties", true);
    return new ExperimentProgram(
        null,
        null,
        draft.dependencies(),
        spec.experimentId(),
        input,
        output,
        draft.source());
  }

  private static ExperimentSpec bindExperiment(
      ExperimentSpec source, String routeId, String agentId) {
    return new ExperimentSpec(
        source.arguments(),
        source.assumptions(),
        source.broadSearch(),
        source.decisionIfConfirmed(),
        source.decisionIfRefuted(),
        source.domains(),
        source.exactArithmetic(),
        null,
        source.experimentId(),
        source.maxCases(),
        source.method(),
        source.noncomputationalAlternative(),
        source.parentCheckpointId(),
        routeId,
        source.purpose(),
        source.reasoningBasis(),
        null,
        agentId,
        JsonNodeFactory.instance.objectNode(),
        source.seed(),
        source.targetClaim(),
        source.typedToolGap(),
        source.whyComputationIsNeeded(),
        source.targetClaimId(),
        source.claimEvidenceSemanticBinding());
  }

  private static ProofAttempt bindAttempt(
      ProofAttempt source,
      StructuredCallResult<?> call,
      AgentRuntime author,
      String routeId,
      String strategyId,
      String problemHash,
      int roundIndex) {
    return new ProofAttempt(
        author.id(),
        "attempt-" + routeId + "-r" + roundIndex,
        source.candidateConjectures(),
        source.checkpointIds(),
        source.deadEnds(),
        call.attemptedAgents(),
        source.falsificationChecks(),
        source.finalAnswer(),
        source.latestCheckpointId(),
        routeId,
        problemHash,
        source.proofSketch(),
        source.proofSteps(),
        source.proposedLemmas(),
        call.responseArtifactRef(),
        source.resumedFromCheckpointId(),
        roundIndex,
        source.segmentCount(),
        source.selfConfidence(),
        source.status(),
        strategyId,
        source.unresolvedGaps(),
        call.usage(),
        source.claimSemanticContextManifestVersion() == 1
            ? source.claimSemanticContextBindings()
            : List.of(),
        1);
  }

  private static VerificationReport bindReview(
      VerificationReport source,
      StructuredCallResult<?> call,
      AgentRuntime reviewer,
      String targetId,
      String targetType,
      VerificationStage stage) {
    return new VerificationReport(
        reviewer.id(),
        source.checkedDependencies(),
        source.conciseFeedback(),
        source.confidence(),
        source.failureLevel(),
        source.firstErrorStep(),
        source.issues(),
        source.problemIntegrityOk(),
        call.responseArtifactRef(),
        source.reportId(),
        stage,
        source.structuredIssues(),
        targetId,
        targetType,
        source.toolRequests(),
        source.toolResults(),
        call.usage(),
        source.verdict());
  }

  private static VerificationReport bindBlindReview(
      BlindVerificationReport source,
      StructuredCallResult<?> call,
      AgentRuntime reviewer) {
    return new VerificationReport(
        reviewer.id(),
        source.checkedDependencies(),
        source.conciseFeedback(),
        source.confidence(),
        source.failureLevel(),
        source.firstErrorStep(),
        source.issues(),
        source.problemIntegrityOk(),
        call.responseArtifactRef(),
        null,
        VerificationStage.FINAL,
        source.structuredIssues(),
        "final-proof",
        "final_proof",
        source.toolRequests(),
        source.toolResults(),
        call.usage(),
        source.verdict());
  }

  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification =
          "This private orchestration boundary must publish the matching terminal agent event "
              + "before preserving the original typed provider or structured-output failure.")
  private <T> StructuredCallResult<T> callStage(
      String idempotencyKey,
      String stage,
      Class<T> responseType,
      Map<String, ?> context,
      AgentRuntime agent,
      String budgetBucket,
      String summary) {
    ResearchWorkerContext researchWorker = activeResearchWorker.get();
    boolean fixedAssignment = requiresFixedStageAssignment(stage);
    String requiredRoleOverride = "";
    if (researchWorker != null && "independent_exploration".equals(stage)) {
      fixedAssignment = researchWorker.fixedAgentAssignment;
      requiredRoleOverride = researchWorker.requiredRole;
    }
    int outputLimit = outputTokens(config, stage);
    StageThinkingPolicy thinking = stageThinkingPolicy(config, stage, outputLimit);
    StructuredCallResult<T> result;
    AgentRuntime executingAgent = agent;
    try (AgentLease lease =
        acquireStageLease(
            idempotencyKey, stage, agent, fixedAssignment, requiredRoleOverride)) {
      executingAgent = lease.agent();
      event("agent_started", stage, executingAgent.id(), "running", summary, null);
      result =
          callStageOnce(
              idempotencyKey,
              stage,
              responseType,
              context,
              executingAgent,
              budgetBucket,
              outputLimit,
              thinking.enabled(),
              thinking.effort(),
              null,
              lease);
    } catch (ReasoningBudgetExhaustedError exhausted) {
      event(
          "reasoning_budget_exhausted",
          stage,
          executingAgent.id(),
          "running",
          "Reasoning reached the output ceiling; recovering the structured artifact",
          null);
      int recoveryLimit = recoveryOutputTokens(config, stage, outputLimit);
      try {
        Map<String, Object> recoveryContext = new LinkedHashMap<>(context);
        recoveryContext.put("artifact_recovery_mode", "compact_structured_artifact_only");
        recoveryContext.put(
            "artifact_recovery_rule",
            "Return exactly one complete compact JSON artifact matching the requested schema. "
                + "Do not repeat private reasoning or add prose. Preserve required mathematical "
                + "content, keep descriptive fields concise, and never fabricate missing evidence.");
        ResearchCheckpointFallbackEvidence fallbackEvidence = null;
        if (ResearchCheckpointedPromptFactory.isAllowedResearchStage(promptStage(stage))) {
          String routeId = checkpointRouteId(context);
          recoveryContext.put(
              "active_research_findings", researchCheckpointLedger().activeFindings(routeId));
          recoveryContext.put(
              "completed_checkpoint_frames", researchCheckpointLedger().checkpointsForRoute(routeId));
          recoveryContext.put("reasoning_progress", exhausted.progress());
          recoveryContext.put(
              "reasoning_trace_sha256",
              Objects.toString(exhausted.progress().get("reasoning_trace_sha256"), ""));
          var recoveryTrace =
              runner
                  .reasoningTrace(
                      Objects.toString(exhausted.progress().get("provider_call_id"), ""))
                  .orElse(null);
          if (recoveryTrace != null) {
            int excerptStart = Math.max(0, recoveryTrace.text().length() - 2_000);
            recoveryContext.put(
                "bounded_trace_excerpt",
                recoveryTrace.text().substring(excerptStart));
            recoveryContext.put("bounded_trace_excerpt_start", excerptStart);
            String exhaustedProviderCallId =
                Objects.toString(exhausted.progress().get("provider_call_id"), "");
            boolean completeFrameAlreadyCommitted =
                researchCheckpointLedger().checkpointsForRoute(routeId).stream()
                    .anyMatch(
                        checkpoint ->
                            exhaustedProviderCallId.equals(checkpoint.providerCallId())
                                && "reasoning_trace".equals(checkpoint.source()));
            if (!completeFrameAlreadyCommitted) {
              fallbackEvidence =
                  new ResearchCheckpointFallbackEvidence(
                      recoveryTrace.text(), recoveryTrace.sha256());
            }
          }
          recoveryContext.put(
              "finding_accounting_rule",
              "Account for every active finding; omission never deletes a finding. If the prior "
                  + "trace had no complete public marker, every recovered finding must include an "
                  + "exact source_quote with quote_start and quote_end measured against the full "
                  + "original trace plus its quote_sha256.");
          incrementCheckpointRecovery(routeId);
          persistUnchecked("research_checkpoint_recovery", false);
        }
        try (AgentLease recoveryLease =
            acquireStageLease(
                idempotencyKey + ":artifact-recovery", stage, executingAgent, true, "")) {
          result =
              callStageOnce(
                  idempotencyKey + ":artifact-recovery",
                  stage,
                  responseType,
                  recoveryContext,
                  executingAgent,
                  budgetBucket,
                  recoveryLimit,
                  false,
                  null,
                  fallbackEvidence,
                  recoveryLease);
        }
      } catch (RuntimeException failure) {
        event(
            "agent_failed",
            stage,
            executingAgent.id(),
            "failed",
            summary + " failed: " + failure.getClass().getSimpleName(),
            null);
        throw failure;
      }
    } catch (RuntimeException failure) {
      event(
          "agent_failed",
          stage,
          executingAgent.id(),
          "failed",
          summary + " failed: " + failure.getClass().getSimpleName(),
          null);
      throw failure;
    }
    runner.apply(result, "desktop:" + idempotencyKey);
    event(
        "agent_completed",
        stage,
        result.agentId(),
        "completed",
        summary + " completed",
        result.responseArtifactRef());
    return result;
  }

  private <T> StructuredCallResult<T> callStageOnce(
      String idempotencyKey,
      String stage,
      Class<T> responseType,
      Map<String, ?> context,
      AgentRuntime agent,
      String budgetBucket,
      int outputLimit,
      Boolean thinkingEnabled,
      String reasoningEffort,
      ResearchCheckpointFallbackEvidence fallbackEvidence,
      AgentLease lease) {
    var prompt =
        prompts.typedStage(
            promptStage(stage), responseType, context, 0.0d, outputLimit, false);
    if (!ResearchCheckpointedPromptFactory.isAllowedResearchStage(prompt.stage())) {
      return runner.callLeased(
          runId,
          idempotencyKey,
          prompt,
          lease,
          budgetBucket,
          thinkingEnabled,
          reasoningEffort);
    }
    String routeId = checkpointRouteId(context);
    CheckpointedPromptBundle<T> checkpointed = checkpointedPrompts.checkpoint(prompt);
    CheckpointedStructuredCallResult<T> result =
        runner.callCheckpointedLeased(
            runId,
            idempotencyKey,
            checkpointed,
            lease,
            budgetBucket,
            thinkingEnabled,
            reasoningEffort,
            capture -> commitResearchCheckpoint(routeId, stage, capture),
            fallbackEvidence);
    return result.result();
  }

  private AgentLease acquireStageLease(
      String idempotencyKey,
      String stage,
      AgentRuntime preferredAgent,
      boolean fixedAssignment,
      String requiredRoleOverride) {
    AgentLeaseClass leaseClass =
        isCoordinationStage(stage) ? AgentLeaseClass.COORDINATION : AgentLeaseClass.RESEARCH;
    return pool.acquireLease(
        new AgentLeaseRequest(
            runId,
            "desktop-" + currentStage + "-r" + roundIndex.get(),
            idempotencyKey,
            leaseClass,
            requiredRoleOverride == null || requiredRoleOverride.isBlank()
                ? roleForStage(stage, preferredAgent)
                : requiredRoleOverride,
            Set.of(),
            List.of(stage),
            "",
            "",
            1,
            fixedAssignment ? preferredAgent.id() : ""));
  }

  private static boolean requiresFixedStageAssignment(String stage) {
    return switch (stage) {
      case "goal_normalization",
          "triage",
          "strategy_generation",
          "strategy_preflight_plan" -> false;
      default -> true;
    };
  }

  private static boolean isCoordinationStage(String stage) {
    String normalized = stage.toLowerCase(Locale.ROOT);
    return normalized.contains("review")
        || normalized.contains("verif")
        || normalized.contains("audit")
        || normalized.contains("adjudicat")
        || normalized.contains("referee")
        || normalized.contains("synthes");
  }

  private synchronized void commitResearchCheckpoint(
      String routeId, String stage, ResearchCheckpointCapture capture) {
    ResearchCheckpointLedger staged =
        ResearchCheckpointLedger.restore(researchCheckpointLedger().snapshot());
    List<ResearchCheckpointRecord> committed =
        staged.appendTraceFrames(
            problemHash,
            routeId,
            stage,
            capture.providerCallId(),
            capture.reasoningTraceCallId(),
            capture.reasoningTraceTaskId(),
            capture.traceFrames());
    if (capture.envelopeFrame() != null) {
      committed =
          append(
              committed,
              staged.appendEnvelopeFrame(
                  problemHash,
                  routeId,
                  stage,
                  capture.providerCallId(),
                  capture.envelopeFrame()));
    }
    staged =
        ResearchFindingUpdateBoundary.apply(
            staged,
            routeId,
            CAMPAIGN_RESEARCH_ROUTE_ID,
            stage,
            capture.providerCallId(),
            capture.findingUpdates());
    ResearchWorkerContext worker = activeResearchWorker.get();
    if (worker == null) {
      researchCheckpoints = staged;
    } else {
      worker.researchCheckpoints = staged;
    }
    RouteState route =
        worker == null
            ? findRouteState(routeId).orElse(null)
            : worker.route.routeId.equals(routeId) ? worker.route : null;
    if (route != null) {
      if (!committed.isEmpty()) {
        route.latestResearchCheckpointId = committed.getLast().checkpointId();
      }
      route.lastCheckpointedProviderCallId = capture.providerCallId();
      refreshRouteResearchProjection(route);
    }
    if (worker == null) {
      persistUnchecked("research_checkpoint", false);
    }
  }

  private ResearchCheckpointLedger researchCheckpointLedger() {
    ResearchWorkerContext worker = activeResearchWorker.get();
    return worker == null ? researchCheckpoints : worker.researchCheckpoints;
  }

  private static List<ResearchCheckpointRecord> append(
      List<ResearchCheckpointRecord> values, ResearchCheckpointRecord value) {
    List<ResearchCheckpointRecord> result = new ArrayList<>(values);
    result.add(value);
    return List.copyOf(result);
  }

  private Optional<RouteState> findRouteState(String routeId) {
    return routes.stream().filter(route -> route.routeId.equals(routeId)).findFirst();
  }

  private static String checkpointRouteId(Map<String, ?> context) {
    Object explicit = context.get("route_id");
    if (explicit != null && !explicit.toString().isBlank()) {
      return explicit.toString();
    }
    Object task = context.get("task");
    if (task instanceof InspirationTask inspiration && !inspiration.targetRouteIds().isEmpty()) {
      return inspiration.targetRouteIds().getFirst();
    }
    return CAMPAIGN_RESEARCH_ROUTE_ID;
  }

  private void incrementCheckpointRecovery(String routeId) {
    findRouteState(routeId).ifPresent(route -> route.checkpointRecoveryCount++);
  }

  private void refreshRouteResearchProjection(RouteState route) {
    route.activeResearchFindingIds.clear();
    route.activeResearchFindingIds.addAll(
        researchCheckpointLedger().activeFindings(route.routeId).stream()
            .map(ResearchFindingRecord::findingId)
            .toList());
  }

  private void reconcileSubmittedAttemptFindings(RouteState route) {
    if (route.attempt == null || !route.pendingFindingReconciliation) {
      return;
    }
    Map<String, ClaimCard> existing = new LinkedHashMap<>();
    for (ClaimCard claim : route.attempt.proposedLemmas()) {
      existing.put(claim.claimId(), claim);
    }
    boolean changed = false;
    for (ResearchFindingRecord finding : researchCheckpointLedger().activeFindings(route.routeId)) {
      if (finding.status() != ResearchFindingStatus.ACTIVE) {
        continue;
      }
      if (finding.kind()
          == io.github.aililuola.mathproofmesh.contract.ResearchFindingKind.CANDIDATE_LEMMA) {
        ClaimCard claim = proposedResearchClaim(finding, route.attempt);
        existing.putIfAbsent(claim.claimId(), claim);
        researchCheckpointLedger().applyUpdates(
            route.routeId,
            new ResearchFindingUpdateBatch(
                List.of(
                    new io.github.aililuola.mathproofmesh.contract.ResearchFindingDisposition(
                        finding.findingId(),
                        ResearchFindingDispositionAction.PROMOTE_TO_PROPOSED_LEMMA,
                        "bounded submit-attempt reconciliation",
                        null))));
        changed = true;
      } else if (finding.kind()
              == io.github.aililuola.mathproofmesh.contract.ResearchFindingKind
                  .COUNTEREXAMPLE_CANDIDATE
          && finding.targetObligationId() != null) {
        ClaimCard claim = proposedResearchCounterexample(finding, route.attempt);
        existing.putIfAbsent(claim.claimId(), claim);
        researchCheckpointLedger().applyUpdates(
            route.routeId,
            new ResearchFindingUpdateBatch(
                List.of(
                    new io.github.aililuola.mathproofmesh.contract.ResearchFindingDisposition(
                        finding.findingId(),
                        ResearchFindingDispositionAction.PROMOTE_TO_COUNTEREXAMPLE_CANDIDATE,
                        "bounded submit-attempt reconciliation",
                        null))));
        changed = true;
      }
    }
    if (changed) {
      route.attempt = withProposedResearchClaims(route.attempt, List.copyOf(existing.values()));
    }
    route.pendingFindingReconciliation = false;
    refreshRouteResearchProjection(route);
  }

  private static void attachPendingPivotProposedClaims(RouteState route) {
    if (route.attempt == null || route.pendingPivotProposedClaims.isEmpty()) {
      return;
    }
    Map<String, ClaimCard> proposed = new LinkedHashMap<>();
    route.attempt.proposedLemmas().forEach(claim -> proposed.put(claim.claimId(), claim));
    route.pendingPivotProposedClaims.forEach(claim -> proposed.put(claim.claimId(), claim));
    route.attempt = withProposedResearchClaims(route.attempt, List.copyOf(proposed.values()));
  }

  private static ClaimCard proposedResearchClaim(
      ResearchFindingRecord finding, ProofAttempt attempt) {
    return new ClaimCard(
        finding.assumptions(),
        "research-claim-" + finding.findingId().substring("research_finding_".length()),
        finding.statement(),
        "",
        "Requires independent issue-003 claim review.",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        finding.scopeLimitations(),
        0.5d,
        attempt.agentId(),
        attempt.attemptId(),
        null,
        finding.statement(),
        ClaimStatus.PROPOSED,
        List.of("source-research-finding:" + finding.findingId()),
        null);
  }

  private static ClaimCard proposedResearchCounterexample(
      ResearchFindingRecord finding, ProofAttempt attempt) {
    ClaimCard base = proposedResearchClaim(finding, attempt);
    return new ClaimCard(
        base.assumptions(),
        base.claimId(),
        base.conclusion(),
        "",
        base.counterexampleRisk(),
        base.dependencies(),
        base.dependencyRefs(),
        base.evidenceRefs(),
        base.proofSteps(),
        base.scopeLimitations(),
        base.selfConfidence(),
        base.sourceAgentId(),
        base.sourceAttemptId(),
        base.sourceDeltaId(),
        base.statement(),
        ClaimStatus.PROPOSED,
        List.of(
            "artifact:counterexample",
            "counterexample-target:" + finding.targetObligationId(),
            "source-research-finding:" + finding.findingId()),
        null);
  }

  private static ProofAttempt withProposedResearchClaims(
      ProofAttempt source, List<ClaimCard> proposedLemmas) {
    List<ClaimSemanticContextBinding> bindings = source.claimSemanticContextBindings();
    if (source.claimSemanticContextManifestVersion() == 1) {
      LinkedHashSet<String> originalClaimIds =
          source.proposedLemmas().stream()
              .map(ClaimCard::claimId)
              .collect(
                  java.util.stream.Collectors.toCollection(LinkedHashSet::new));
      ArrayList<ClaimSemanticContextBinding> expanded = new ArrayList<>(bindings);
      proposedLemmas.stream()
          .filter(claim -> !originalClaimIds.contains(claim.claimId()))
          .forEach(
              claim ->
                  expanded.add(
                      new ClaimSemanticContextBinding(
                          claim.claimId(),
                          null,
                          claim.assumptions(),
                          List.of(),
                          List.of(),
                          claim.scopeLimitations(),
                          "positive")));
      bindings = List.copyOf(expanded);
    }
    return new ProofAttempt(
        source.agentId(),
        source.attemptId(),
        source.candidateConjectures(),
        source.checkpointIds(),
        source.deadEnds(),
        source.failoverChain(),
        source.falsificationChecks(),
        source.finalAnswer(),
        source.latestCheckpointId(),
        source.pathId(),
        source.problemHash(),
        source.proofSketch(),
        source.proofSteps(),
        proposedLemmas,
        source.rawArtifactRef(),
        source.resumedFromCheckpointId(),
        source.roundIndex(),
        source.segmentCount(),
        source.selfConfidence(),
        source.status(),
        source.strategyId(),
        source.unresolvedGaps(),
        source.usage(),
        bindings,
        source.claimSemanticContextManifestVersion());
  }

  private static String roleForStage(String stage, AgentRuntime agent) {
    List<String> preferred =
        switch (stage) {
          case "goal_normalization", "triage", "strategy_generation" -> List.of("planner");
          case "independent_exploration" -> List.of("explorer", "route_prover");
          case "skeptic_review" ->
              List.of("route_skeptic", "counterexample_hunter", "detailed_verifier");
          case "tool_replay",
              "experiment_codegen",
              "computation_contract_repair",
              "formal_chain_verification" ->
              List.of(
                  "tool_specialist",
                  "experimenter",
                  "planner",
                  "explorer",
                  "structural_verifier");
          case "structural_verification" ->
              List.of("structural_verifier", "route_referee", "detailed_verifier");
          case "detailed_verification", "claim_salvage_review", "inspiration_referee" ->
              List.of("detailed_verifier", "route_referee", "inspiration_referee");
          case "semantic_pivot_review" ->
              List.of("detailed_verifier", "route_referee", "final_verifier");
          case "semantic_pivot_proposal" ->
              List.of(
                  "meta_strategist",
                  "representation_switchboard",
                  "reverse_goal_analyzer",
                  "construction_inventor",
                  "explorer");
          case "inspiration_proposal" ->
              List.of(
                  "representation_switchboard",
                  "analogy_agent",
                  "construction_inventor",
                  "invariant_hypothesis_agent",
                  "reverse_goal_analyzer",
                  "bridge_prover",
                  "meta_strategist",
                  "explorer",
                  "route_prover");
          case "meta_review" -> List.of("meta_reviewer", "planner");
          case "synthesis" -> List.of("synthesizer");
          case "blind_final_verification",
              "adversarial_final_verification",
              "cross_provider_final_verification" ->
              List.of("final_verifier", "detailed_verifier");
          default -> List.of("general");
        };
    return preferred.stream()
        .filter(agent::supportsRole)
        .findFirst()
        .orElseGet(
            () ->
                agent.config().roles().stream()
                    .findFirst()
                    .orElseThrow(
                        () ->
                            new IllegalStateException(
                                "agent has no callable role: " + agent.id())));
  }

  private static String promptStage(String stage) {
    return switch (stage) {
      case "skeptic_review" -> "route_skeptic";
      case "tool_replay", "formal_chain_verification" -> "route_tool_audit";
      case "blind_final_verification", "adversarial_final_verification" ->
          "blind_detailed_verification";
      case "cross_provider_final_verification" -> "final_verification";
      default -> stage;
    };
  }

  private static String promptStageForMechanism(InspirationMechanism mechanism) {
    return switch (mechanism) {
      case REPRESENTATION_SWITCH -> "representation_switchboard";
      case STRUCTURAL_ANALOGY -> "structural_analogy_search";
      case AUXILIARY_CONSTRUCTION -> "invent_auxiliary_construction";
      case INVARIANT_HYPOTHESIS -> "hypothesize_invariant";
      case REVERSE_GOAL_ANALYSIS -> "reverse_goal_analysis";
      case BRIDGE_LEMMA -> "bridge_lemma";
      case SURPRISE_EXPLORATION -> "surprise_exploration";
      case META_REPLAN -> "persistent_meta_strategy";
      case INSPIRATION_COMPOSITION -> "persistent_meta_strategy";
    };
  }

  private static int outputTokens(SystemConfig config, String stage) {
    int configured =
        config.runtime().stageOutputTokenLimits().getOrDefault(stage, 16_000);
    return "computation_contract_repair".equals(stage)
        ? Math.min(configured, config.computation().contractRepairMaxOutputTokens())
        : configured;
  }

  static StageThinkingPolicy stageThinkingPolicy(
      SystemConfig config, String stage, int outputLimit) {
    String mode = config.runtime().stageThinkingModes().getOrDefault(stage, "agent_default");
    return switch (mode) {
      case "disabled" -> new StageThinkingPolicy(false, null);
      case "high", "max" -> new StageThinkingPolicy(true, mode);
      case "tiered" ->
          new StageThinkingPolicy(
              true,
              outputLimit >= config.deepExplorationPolicy().highTierThresholdTokens()
                  ? "max"
                  : "high");
      default -> new StageThinkingPolicy(null, null);
    };
  }

  private static int recoveryOutputTokens(
      SystemConfig config, String stage, int outputLimit) {
    if (config.deepExplorationPolicy().enabled()
        && Set.of("independent_exploration", "route_prove", "proof_continuation")
            .contains(stage)) {
      return config
          .deepExplorationPolicy()
          .tierForLimit(outputLimit)
          .artifactRecoveryTokens();
    }
    return config.continuation().postFailureBottleneckMaxOutputTokens();
  }

  private static String normalizedLowerCaseCode(String value) {
    if (!value.matches("[A-Za-z0-9_]+")) {
      throw new IllegalArgumentException("status code must contain ASCII letters, digits, or '_'");
    }
    return value.toLowerCase(Locale.ROOT);
  }

  private static int safeInt(long value) {
    return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, value));
  }

  private void stage(RoutePipelineFunctions.RunStage stage, String summary) {
    currentStage = stage.name().toLowerCase(Locale.ROOT);
    event("stage_started", currentStage, null, "running", summary, null);
  }

  private void complete(RoutePipelineFunctions.RunStage stage) {
    completedStages.add(stage.name());
    currentStage = stage.name().toLowerCase(Locale.ROOT);
    event(
        "stage_completed",
        currentStage,
        null,
        "completed",
        stage.name() + " completed",
        "pipeline://" + activitySequence.incrementAndGet());
  }

  private void event(
      String type,
      String stage,
      String agentId,
      String status,
      String summary,
      String reference) {
    progress.emit(type, stage, agentId, status, summary, reference);
  }

  private enum SchedulerExit {
    READY_TO_SYNTHESIZE,
    STOPPED
  }

  record StageThinkingPolicy(Boolean enabled, String effort) {}

  private record ComputationTrace(
      String routeId,
      ExperimentSpec spec,
      ComputationDecision decision,
      ExperimentProgram program,
      ExperimentResult result,
      ComputationTargetBinding targetBinding,
      ComputationEvidenceGate.EvidenceAuthority authority,
      boolean replayValid) {
    private String targetObligationId() {
      return targetBinding == null ? "" : targetBinding.obligationId();
    }

    private Map<String, Object> publicView() {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("route_id", routeId);
      view.put("target_obligation_id", targetObligationId());
      view.put("target_binding_hash", targetBinding == null ? "none" : targetBinding.bindingHash());
      view.put("experiment", spec);
      view.put("decision", decision);
      view.put("program_hash", program == null ? "none" : program.codeHash());
      view.put("result", result == null ? "not_executed" : result);
      view.put("authority", authority.name().toLowerCase(Locale.ROOT));
      view.put("replay_valid", replayValid);
      return Map.copyOf(view);
    }
  }

  private record AuthorityMutationIds(
      String factMessageId,
      String counterexampleMessageId,
      String closedObligationId,
      String refutedObligationId,
      String claimCourtEvidenceId) {
    private AuthorityMutationIds {
      factMessageId = cleanIdentity(factMessageId);
      counterexampleMessageId = cleanIdentity(counterexampleMessageId);
      closedObligationId = cleanIdentity(closedObligationId);
      refutedObligationId = cleanIdentity(refutedObligationId);
      claimCourtEvidenceId = cleanIdentity(claimCourtEvidenceId);
    }

    private static AuthorityMutationIds none() {
      return new AuthorityMutationIds("", "", "", "", "");
    }
  }

  private record RouteBatchCompletion(RouteState route, RuntimeException failure) {}

  private static String normalizedIdentityText(String value) {
    return value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
  }

  private static String cleanIdentity(String value) {
    return value == null ? "" : value.strip();
  }

  private record VerifiedCourtFactProjection(
      FrozenClaimSnapshot frozen, ClaimProofRevisionRecord revision) {
    private VerifiedCourtFactProjection {
      frozen = Objects.requireNonNull(frozen, "frozen");
      revision = Objects.requireNonNull(revision, "revision");
    }
  }

  private record ObligationControlAdmission(
      ObligationCreationContext context,
      FocusedRecoveryActionType actionType,
      FocusedExpansionDecision decision,
      boolean existingCanonicalTarget) {
    private ObligationControlAdmission {
      context = Objects.requireNonNull(context, "context");
      actionType = Objects.requireNonNull(actionType, "actionType");
      decision = Objects.requireNonNull(decision, "decision");
    }
  }

  private record ControlledObligationWrite(
      CanonicalizedObligationWriteResult result, FocusedExpansionDecision decision) {
    private ControlledObligationWrite {
      result = Objects.requireNonNull(result, "result");
      decision = Objects.requireNonNull(decision, "decision");
    }
  }

  private record PivotRouteSnapshot(
      StrategyCard strategy,
      ContinuationFunctions.Checkpoint checkpoint,
      List<DesktopSolveCheckpoint.AttemptRevisionCheckpoint> revisionHistory,
      ProofAttempt attempt,
      VerificationReport skepticReview,
      ToolAuditReport toolAudit,
      VerificationReport structuralReview,
      VerificationReport detailedReview,
      VerificationReport crossProviderReview,
      ClaimReviewBatch claimReview,
      RouteTeamResult teamResult,
      EscalationPlan escalation,
      ValidationExecution validationExecution,
      ContinuationFunctions.Delta delta,
      FailureControlService.Failure failure,
      String deltaId,
      String status,
      String failureReason,
      String nearMissId,
      int segmentCount,
      int noProgressSegments,
      String focusObligationId,
      String focusedCanonicalTargetId,
      String focusedBottleneckFamilyId,
      String focusSource,
      boolean reviewComplete,
      boolean checkpointProcessed,
      boolean integrated,
      String activeSemanticPivotId,
      List<String> semanticPivotIds,
      String activeStrategyEpochId,
      List<String> retiredActiveClaimIds,
      List<ClaimCard> pendingPivotProposedClaims,
      List<String> retiredStrategyFocusObligationIds,
      List<String> activeMathematicalObjectIds,
      String activeDirectionSignature) {
    private static PivotRouteSnapshot capture(RouteState route) {
      return new PivotRouteSnapshot(
          route.strategy,
          route.checkpoint,
          List.copyOf(route.revisionHistory),
          route.attempt,
          route.skepticReview,
          route.toolAudit,
          route.structuralReview,
          route.detailedReview,
          route.crossProviderReview,
          route.claimReview,
          route.teamResult,
          route.escalation,
          route.validationExecution,
          route.delta,
          route.failure,
          route.deltaId,
          route.status,
          route.failureReason,
          route.nearMissId,
          route.segmentCount,
          route.noProgressSegments,
          route.focusObligationId,
          route.focusedCanonicalTargetId,
          route.focusedBottleneckFamilyId,
          route.focusSource,
          route.reviewComplete,
          route.checkpointProcessed,
          route.integrated,
          route.activeSemanticPivotId,
          List.copyOf(route.semanticPivotIds),
          route.activeStrategyEpochId,
          List.copyOf(route.retiredActiveClaimIds),
          List.copyOf(route.pendingPivotProposedClaims),
          List.copyOf(route.retiredStrategyFocusObligationIds),
          List.copyOf(route.activeMathematicalObjectIds),
          route.activeDirectionSignature);
    }

    private void restore(RouteState route) {
      route.strategy = strategy;
      route.checkpoint = checkpoint;
      route.revisionHistory.clear();
      route.revisionHistory.addAll(revisionHistory);
      route.attempt = attempt;
      route.skepticReview = skepticReview;
      route.toolAudit = toolAudit;
      route.structuralReview = structuralReview;
      route.detailedReview = detailedReview;
      route.crossProviderReview = crossProviderReview;
      route.claimReview = claimReview;
      route.teamResult = teamResult;
      route.escalation = escalation;
      route.validationExecution = validationExecution;
      route.delta = delta;
      route.failure = failure;
      route.deltaId = deltaId;
      route.status = status;
      route.failureReason = failureReason;
      route.nearMissId = nearMissId;
      route.segmentCount = segmentCount;
      route.noProgressSegments = noProgressSegments;
      route.focusObligationId = focusObligationId;
      route.focusedCanonicalTargetId = focusedCanonicalTargetId;
      route.focusedBottleneckFamilyId = focusedBottleneckFamilyId;
      route.focusSource = focusSource;
      route.reviewComplete = reviewComplete;
      route.checkpointProcessed = checkpointProcessed;
      route.integrated = integrated;
      route.activeSemanticPivotId = activeSemanticPivotId;
      route.semanticPivotIds.clear();
      route.semanticPivotIds.addAll(semanticPivotIds);
      route.activeStrategyEpochId = activeStrategyEpochId;
      route.retiredActiveClaimIds.clear();
      route.retiredActiveClaimIds.addAll(retiredActiveClaimIds);
      route.pendingPivotProposedClaims.clear();
      route.pendingPivotProposedClaims.addAll(pendingPivotProposedClaims);
      route.retiredStrategyFocusObligationIds.clear();
      route.retiredStrategyFocusObligationIds.addAll(retiredStrategyFocusObligationIds);
      route.activeMathematicalObjectIds.clear();
      route.activeMathematicalObjectIds.addAll(activeMathematicalObjectIds);
      route.activeDirectionSignature = activeDirectionSignature;
    }
  }

  private record PreparedStrategyCandidate(
      StrategyCard strategy,
      ProofControlModels.Strategy controlStrategy,
      StrategyBlueprintCompiler.Compilation blueprint,
      ProofControlModels.GoalLink goalLink,
      StrategyMechanismSignature signature,
      StrategyMechanismProfile profile,
      StrategyPreflightReport preflight) {
    private PreparedStrategyCandidate {
      Objects.requireNonNull(strategy, "strategy");
      Objects.requireNonNull(controlStrategy, "controlStrategy");
      Objects.requireNonNull(blueprint, "blueprint");
      Objects.requireNonNull(goalLink, "goalLink");
      Objects.requireNonNull(signature, "signature");
      Objects.requireNonNull(profile, "profile");
      Objects.requireNonNull(preflight, "preflight");
    }
  }

  static final class SimulatedStrategyPortfolioProcessTermination extends Error {
    private static final long serialVersionUID = 1L;

    private SimulatedStrategyPortfolioProcessTermination(StrategyPortfolioFailurePoint point) {
      super("simulated strategy portfolio process termination at " + point);
    }
  }

  static final class SimulatedStrategyPreflightProcessTermination extends Error {
    private static final long serialVersionUID = 1L;

    private SimulatedStrategyPreflightProcessTermination(
        StrategyPreflightFailurePoint point) {
      super("simulated strategy preflight process termination at " + point);
    }
  }

  private record StrategyPortfolioPreparation(
      List<StrategyPortfolioCandidate> candidates,
      Map<String, PreparedStrategyCandidate> prepared,
      StrategyPortfolioDecision decision) {
    private StrategyPortfolioPreparation {
      candidates = List.copyOf(candidates);
      prepared = Map.copyOf(prepared);
      Objects.requireNonNull(decision, "decision");
    }

    private StrategyPortfolioPreparation withDecision(StrategyPortfolioDecision replacement) {
      return new StrategyPortfolioPreparation(candidates, prepared, replacement);
    }
  }

  private record CourtStageResult<T>(T value, String executionId) {
    private CourtStageResult {
      Objects.requireNonNull(value, "value");
      Objects.requireNonNull(executionId, "executionId");
    }
  }

  private record ClaimCourtReviewResult(
      ClaimCourtRecord record,
      ClaimProofRevisionRecord revision,
      String authorityAgentId,
      double confidence) {
    private ClaimCourtReviewResult {
      Objects.requireNonNull(record, "record");
      Objects.requireNonNull(revision, "revision");
      Objects.requireNonNull(authorityAgentId, "authorityAgentId");
    }
  }

  enum AuthoritativeConcurrencyFailurePoint {
    NONE,
    AFTER_RESULTS_DURABLE_BEFORE_COMMIT,
    AFTER_FIRST_AUTHORITY_RESULT_APPLIED,
    AFTER_ALL_AUTHORITY_RESULTS_APPLIED_BEFORE_EPOCH_COMMIT,
    AFTER_EPOCH_MARKED_COMMITTED_BEFORE_CHECKPOINT,
    AFTER_ATOMIC_CHECKPOINT_MOVE
  }

  record AuthoritativeConcurrencyDiagnostics(
      int epochCount,
      int workItemCount,
      int resultArtifactCount,
      long committedEpochCount,
      int liveMergeReceiptCount,
      long directWorkerAuthorityMutations,
      Set<ResearchWorkKind> workKinds) {
    AuthoritativeConcurrencyDiagnostics {
      workKinds = workKinds == null ? Set.of() : Set.copyOf(workKinds);
    }

    @Override
    public Set<ResearchWorkKind> workKinds() {
      return Set.copyOf(workKinds);
    }
  }

  static final class SimulatedAuthoritativeConcurrencyProcessTermination extends Error {
    private static final long serialVersionUID = 1L;

    private SimulatedAuthoritativeConcurrencyProcessTermination(
        AuthoritativeConcurrencyFailurePoint point) {
      super("simulated authoritative concurrency process termination at " + point);
    }
  }

  private record AuthoritativeWorkSpec(
      ResearchWorkKind kind,
      String routeId,
      String claimId,
      String obligationId,
      String canonicalTargetId,
      String requiredRole,
      AgentLeaseClass leaseClass,
      Set<String> excludedAgentIds,
      ResearchWorkReadSet readSet,
      ResearchWorkConflictSet conflictSet,
      String inputArtifactRef,
      String expectedResultSchema,
      int stableOrdinal) {
    private AuthoritativeWorkSpec {
      Objects.requireNonNull(kind, "kind");
      routeId = routeId == null ? "" : routeId.strip();
      claimId = claimId == null ? "" : claimId.strip();
      obligationId = obligationId == null ? "" : obligationId.strip();
      canonicalTargetId = canonicalTargetId == null ? "" : canonicalTargetId.strip();
      requiredRole = requireNonBlank(requiredRole, "requiredRole");
      Objects.requireNonNull(leaseClass, "leaseClass");
      excludedAgentIds = excludedAgentIds == null ? Set.of() : Set.copyOf(excludedAgentIds);
      readSet = readSet == null ? ResearchWorkReadSet.empty() : readSet;
      conflictSet = conflictSet == null ? ResearchWorkConflictSet.empty() : conflictSet;
      inputArtifactRef = requireNonBlank(inputArtifactRef, "inputArtifactRef");
      expectedResultSchema = requireNonBlank(expectedResultSchema, "expectedResultSchema");
      if (stableOrdinal < 0) {
        throw new IllegalArgumentException("stableOrdinal must be nonnegative");
      }
    }

    private String identity() {
      return String.join(
          ":", kind.name(), routeId, claimId, obligationId, Integer.toString(stableOrdinal));
    }
  }

  private record AuthoritativeEpochRun(
      FrozenResearchSnapshot snapshot,
      List<ResearchWorkItem> workItems,
      List<ResearchWorkResultEnvelope> results,
      io.github.aililuola.mathproofmesh.concurrency.ResearchMergePlan mergePlan) {
    private AuthoritativeEpochRun {
      Objects.requireNonNull(snapshot, "snapshot");
      workItems = List.copyOf(workItems);
      results = List.copyOf(results);
      Objects.requireNonNull(mergePlan, "mergePlan");
    }
  }

  private record RouteReviewDraft(
      RouteTeamPlan plan,
      VerificationReport skepticReview,
      ToolAuditReport toolAudit,
      VerificationReport structuralReview,
      VerificationReport detailedReview,
      VerificationReport crossProviderReview,
      RouteTeamResult teamResult,
      EscalationPlan escalation,
      ValidationExecution validationExecution,
      String status,
      String failureReason,
      List<ComputationAudit> computationAudits) {
    private RouteReviewDraft {
      Objects.requireNonNull(plan, "plan");
      status = requireNonBlank(status, "status");
      failureReason = failureReason == null ? "" : failureReason;
      computationAudits =
          computationAudits == null ? List.of() : List.copyOf(computationAudits);
    }

    private static RouteReviewDraft from(
        RouteState route, List<ComputationAudit> computationAudits) {
      return new RouteReviewDraft(
          route.plan,
          route.skepticReview,
          route.toolAudit,
          route.structuralReview,
          route.detailedReview,
          route.crossProviderReview,
          route.teamResult,
          route.escalation,
          route.validationExecution,
          route.status,
          route.failureReason,
          computationAudits);
    }
  }

  private record ClaimCourtProjectionTarget(String routeId, String claimId) {
    private ClaimCourtProjectionTarget {
      routeId = requireNonBlank(routeId, "routeId");
      claimId = requireNonBlank(claimId, "claimId");
    }
  }

  private record ClaimCourtCaseDraft(
      ClaimCourtRecord record,
      ClaimProofRevisionRecord revision,
      String authorityAgentId,
      double confidence,
      ClaimCourtSnapshot court,
      ClaimProofRevisionSnapshot revisions,
      ClaimCourtStageExecutionSnapshot executions) {
    private ClaimCourtCaseDraft {
      Objects.requireNonNull(record, "record");
      Objects.requireNonNull(revision, "revision");
      authorityAgentId = requireNonBlank(authorityAgentId, "authorityAgentId");
      Objects.requireNonNull(court, "court");
      Objects.requireNonNull(revisions, "revisions");
      Objects.requireNonNull(executions, "executions");
    }

    private ClaimCourtReviewResult reviewResult() {
      return new ClaimCourtReviewResult(record, revision, authorityAgentId, confidence);
    }
  }

  private record ExplorationTurnDraft(
      InitialExplorationTurn turn,
      String runId,
      String callId,
      String agentId,
      String provider,
      String model,
      String promptArtifactRef,
      String responseArtifactRef,
      UsageRecord usage,
      boolean repaired,
      List<String> attemptedAgents,
      io.github.aililuola.mathproofmesh.research.ResearchCheckpointSnapshot researchCheckpoints,
      String latestResearchCheckpointId,
      String lastCheckpointedProviderCallId,
      List<String> activeResearchFindingIds,
      int checkpointRecoveryCount) {
    private ExplorationTurnDraft {
      Objects.requireNonNull(turn, "turn");
      runId = requireNonBlank(runId, "runId");
      callId = requireNonBlank(callId, "callId");
      agentId = requireNonBlank(agentId, "agentId");
      provider = requireNonBlank(provider, "provider");
      model = requireNonBlank(model, "model");
      promptArtifactRef = requireNonBlank(promptArtifactRef, "promptArtifactRef");
      responseArtifactRef = requireNonBlank(responseArtifactRef, "responseArtifactRef");
      Objects.requireNonNull(usage, "usage");
      attemptedAgents = attemptedAgents == null ? List.of() : List.copyOf(attemptedAgents);
      Objects.requireNonNull(researchCheckpoints, "researchCheckpoints");
      latestResearchCheckpointId =
          latestResearchCheckpointId == null ? "" : latestResearchCheckpointId.strip();
      lastCheckpointedProviderCallId =
          lastCheckpointedProviderCallId == null ? "" : lastCheckpointedProviderCallId.strip();
      activeResearchFindingIds =
          activeResearchFindingIds == null ? List.of() : List.copyOf(activeResearchFindingIds);
      if (checkpointRecoveryCount < 0) {
        throw new IllegalArgumentException("checkpointRecoveryCount must be nonnegative");
      }
    }

    private StructuredCallResult<InitialExplorationTurn> callResult() {
      return new StructuredCallResult<>(
          turn,
          runId,
          callId,
          agentId,
          provider,
          model,
          promptArtifactRef,
          responseArtifactRef,
          usage,
          repaired,
          attemptedAgents);
    }
  }

  private static final class ResearchWorkerContext {
    private ResearchCheckpointLedger researchCheckpoints;
    private final RouteState route;
    private final List<ComputationAudit> computationAudits = new ArrayList<>();
    private final String requiredRole;
    private final boolean fixedAgentAssignment;

    private ResearchWorkerContext(
        ResearchCheckpointLedger researchCheckpoints,
        RouteState route,
        String requiredRole,
        boolean fixedAgentAssignment) {
      this.researchCheckpoints = Objects.requireNonNull(researchCheckpoints, "researchCheckpoints");
      this.route = Objects.requireNonNull(route, "route");
      this.requiredRole = requiredRole == null ? "" : requiredRole.strip();
      this.fixedAgentAssignment = fixedAgentAssignment;
    }
  }

  private record ClaimCourtWorkerContext(
      ClaimCourtLedger court,
      ClaimProofRevisionLedger revisions,
      ClaimCourtStageExecutionLedger executions,
      NegativeKnowledgeRegistry negativeKnowledge) {
    private ClaimCourtWorkerContext {
      Objects.requireNonNull(court, "court");
      Objects.requireNonNull(revisions, "revisions");
      Objects.requireNonNull(executions, "executions");
      Objects.requireNonNull(negativeKnowledge, "negativeKnowledge");
    }
  }

  private record EpochAuthorityCommitContext(String epochId) {
    private EpochAuthorityCommitContext {
      epochId = requireNonBlank(epochId, "epochId");
    }
  }

  private record ResearchEpochAuthorityMutationSnapshot(
      ResearchAuthorityAnchor authority,
      AttemptArtifactSnapshot attemptArtifacts,
      LemmaMemorySnapshot lemmaMemory,
      ClaimLifecycleSnapshot claimLifecycle,
      ClaimProofRevisionSnapshot claimProofRevisions,
      ClaimCourtSnapshot claimCourt,
      ClaimCourtStageExecutionSnapshot claimCourtExecutions,
      TypedMemorySnapshot typedMemory,
      ProofGraphSnapshot proofGraph,
      ContinuationFunctions.CheckpointLedgerSnapshot checkpoints,
      List<DesktopSolveCheckpoint.ScheduledProofTask> pendingProofTasks,
      ResearchCheckpointSnapshot researchCheckpoints,
      ResearchEpochSnapshot researchEpochs,
      ResearchTaskSnapshot researchTasks,
      ResearchResultSnapshot researchResults,
      ResearchAuthorityMutationSnapshot researchAuthorityMutations,
      List<RouteState> routes,
      List<ComputationTrace> computationTraces,
      List<ComputationAudit> computationAudits,
      ComputationExecutionState computation,
      MessageStoreSnapshot messageStore,
      BrokerArtifactRegistrySnapshot brokerArtifactRegistry,
      BrokerArtifactPublicationSnapshot brokerArtifactPublications,
      BrokerArtifactDeliverySnapshot brokerArtifactDeliveries,
      BrokerArtifactReceiptSnapshot brokerArtifactReceipts,
      BrokerArtifactUseSnapshot brokerArtifactUses,
      BrokerArtifactUtilitySnapshot brokerArtifactUtilities,
      BrokerArtifactInvalidationSnapshot brokerArtifactInvalidations,
      Set<String> restorablePreparedEpochIds) {
    private ResearchEpochAuthorityMutationSnapshot {
      Objects.requireNonNull(authority, "authority");
      pendingProofTasks = List.copyOf(pendingProofTasks);
      routes = List.copyOf(routes);
      computationTraces = List.copyOf(computationTraces);
      computationAudits = List.copyOf(computationAudits);
      restorablePreparedEpochIds = Set.copyOf(restorablePreparedEpochIds);
    }

    @Override
    public List<DesktopSolveCheckpoint.ScheduledProofTask> pendingProofTasks() {
      return List.copyOf(pendingProofTasks);
    }

    @Override
    public List<RouteState> routes() {
      return List.copyOf(routes);
    }

    @Override
    public List<ComputationTrace> computationTraces() {
      return List.copyOf(computationTraces);
    }

    @Override
    public List<ComputationAudit> computationAudits() {
      return List.copyOf(computationAudits);
    }

    @Override
    public Set<String> restorablePreparedEpochIds() {
      return Set.copyOf(restorablePreparedEpochIds);
    }
  }

  private record ClaimCourtMutationSnapshot(
      ClaimCourtSnapshot court,
      ClaimProofRevisionSnapshot revisions,
      ClaimCourtStageExecutionSnapshot executions,
      AttemptArtifactSnapshot attemptArtifacts,
      LemmaMemorySnapshot lemmaMemory,
      ClaimLifecycleSnapshot claimLifecycle,
      TypedMemorySnapshot typedMemory,
      ProofGraphSnapshot proofGraph,
      ContinuationFunctions.CheckpointLedgerSnapshot checkpoints,
      List<DesktopSolveCheckpoint.ScheduledProofTask> pendingProofTasks,
      RouteClaimProjectionSnapshot route) {
    private ClaimCourtMutationSnapshot {
      pendingProofTasks = List.copyOf(pendingProofTasks);
    }

    @Override
    public List<DesktopSolveCheckpoint.ScheduledProofTask> pendingProofTasks() {
      return List.copyOf(pendingProofTasks);
    }
  }

  private record RouteClaimProjectionSnapshot(
      String routeId,
      List<String> claimIds,
      List<String> salvagedVerifiedClaimIds,
      List<String> salvagedCounterexampleIds,
      List<String> rejectedClaimIds,
      List<String> uncertainClaimIds,
      Set<String> courtCaseIds,
      Set<String> proofInvalidOpenClaimIds,
      Set<String> repairExhaustedClaimIds,
      ClaimReviewBatch claimReview) {
    private RouteClaimProjectionSnapshot {
      Objects.requireNonNull(routeId, "routeId");
      claimIds = List.copyOf(claimIds);
      salvagedVerifiedClaimIds = List.copyOf(salvagedVerifiedClaimIds);
      salvagedCounterexampleIds = List.copyOf(salvagedCounterexampleIds);
      rejectedClaimIds = List.copyOf(rejectedClaimIds);
      uncertainClaimIds = List.copyOf(uncertainClaimIds);
      courtCaseIds = Set.copyOf(courtCaseIds);
      proofInvalidOpenClaimIds = Set.copyOf(proofInvalidOpenClaimIds);
      repairExhaustedClaimIds = Set.copyOf(repairExhaustedClaimIds);
    }

    private void restore(RouteState route) {
      replace(route.claimIds, claimIds);
      replace(route.salvagedVerifiedClaimIds, salvagedVerifiedClaimIds);
      replace(route.salvagedCounterexampleIds, salvagedCounterexampleIds);
      replace(route.rejectedClaimIds, rejectedClaimIds);
      replace(route.uncertainClaimIds, uncertainClaimIds);
      replace(route.courtCaseIds, courtCaseIds);
      replace(route.proofInvalidOpenClaimIds, proofInvalidOpenClaimIds);
      replace(route.repairExhaustedClaimIds, repairExhaustedClaimIds);
      route.claimReview = claimReview;
    }

    private static <T> void replace(Collection<T> target, Collection<T> source) {
      target.clear();
      target.addAll(source);
    }
  }

  private static final class ClaimCourtStageQuarantinedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private ClaimCourtStageQuarantinedException(String message) {
      super(message);
    }
  }

  private final class BudgetHost implements DesktopBudgetScheduler.Host {
    private boolean reserveInitial() {
      int pendingRoutes =
          (int)
              routes.stream()
                  .filter(route -> route.attempt == null)
                  .filter(route -> !"abandoned".equals(route.status))
                  .filter(DesktopSolveCoordinator.this::routeEligibleForWork)
                  .count();
      return budgetScheduler.reserveInitial(pendingRoutes, state());
    }

    private boolean reserveProofTaskBatch(
        List<DesktopSolveCheckpoint.ScheduledProofTask> batch) {
      List<DesktopBudgetScheduler.ProofTaskBudgetInput> tasks =
          batch.stream()
              .map(
                  task ->
                      new DesktopBudgetScheduler.ProofTaskBudgetInput(
                          task.taskId(), task.requestedAction()))
              .toList();
      return budgetScheduler.reserveProofTaskBatch(
          tasks, currentResearchAuthorityAnchor().stableHash());
    }

    private boolean schedulableProofTask(DesktopSolveCheckpoint.ScheduledProofTask task) {
      RouteState route = findRouteTarget(task.routeId()).orElse(null);
      ProofObligation obligation = findObligation(task.obligationId()).orElse(null);
      return route != null
          && obligation != null
          && Set.of("open", "tentative", "blocked").contains(obligation.status())
          && routeEligibleForWork(route);
    }

    private boolean schedulePendingProofTasksBatch() {
      List<DesktopSolveCheckpoint.ScheduledProofTask> batch =
          pendingProofTasks.stream()
              .filter(this::schedulableProofTask)
              .limit(config.scheduler().maxActionsPerRound())
              .toList();
      if (batch.isEmpty() || !reserveProofTaskBatch(batch)) {
        return false;
      }
      int scheduled = 0;
      while (scheduled < batch.size() && !pendingProofTasks.isEmpty()) {
        int pendingBefore = pendingProofTasks.size();
        if (schedulePendingProofTask()) {
          scheduled++;
        }
        if (pendingProofTasks.size() >= pendingBefore) {
          break;
        }
      }
      if (scheduled == 0) {
        budgetScheduler.finish();
      }
      return scheduled > 0;
    }

    private BudgetStateSnapshot state() {
      String authorityHash = currentResearchAuthorityAnchor().stableHash();
      String epochId =
          "scheduler-epoch-"
              + roundIndex.get()
              + "-"
              + authorityHash.substring(0, Math.min(16, authorityHash.length()));
      return budgetRuntime.snapshot(
          authorityHash,
          epochId,
          roundIndex.get(),
          routes.size(),
          ledger.totals(),
          pathBudgetStats());
    }

    @Override
    public TargetMechanismKey restoredBudgetTarget(
        DesktopSolveCheckpoint checkpoint, BudgetEnvelope envelope) {
      BudgetActionCandidate selected =
          checkpoint.budgetDecisions().decisions().stream()
              .filter(
                  decision ->
                      decision.identity().decisionHash().equals(envelope.actionDecisionId()))
              .flatMap(decision -> decision.selectedActions().stream())
              .findFirst()
              .orElse(null);
      if (selected != null) {
        return budgetTarget(selected);
      }
      DesktopSolveCheckpoint.ScheduledProofTask task =
          pendingProofTasks.stream()
              .filter(value -> value.taskId().equals(envelope.workItemId()))
              .findFirst()
              .orElse(null);
      ActionKind action =
          envelope.bucket() == BudgetBucket.REVISION ? ActionKind.REVISE : ActionKind.DEEPEN;
      RouteState route = task == null ? null : findRouteTarget(task.routeId()).orElse(null);
      return new TargetMechanismKey(
          task == null ? envelope.workItemId() : task.obligationId(),
          route == null ? envelope.workItemId() : route.strategy.strategyId(),
          action,
          route == null ? envelope.workItemId() : mechanismSignature(route));
    }

    @Override
    public TargetMechanismKey budgetTarget(BudgetActionCandidate action) {
      RouteState route = findRouteTarget(action.targetId()).orElse(null);
      String target = action.targetId().isBlank() ? "scheduler-global" : action.targetId();
      String strategy =
          action.strategyId().isBlank()
              ? route == null ? "scheduler-global" : route.strategy.strategyId()
              : action.strategyId();
      String mechanism = route == null ? strategy : mechanismSignature(route);
      return new TargetMechanismKey(target, strategy, action.action(), mechanism);
    }

    @Override
    public DesktopBudgetRuntime.GainBaseline gainBaseline() {
      return new DesktopBudgetRuntime.GainBaseline(
          "scheduler-epoch-" + roundIndex.get(),
          currentResearchAuthorityAnchor().stableHash(),
          lemmaMemory.verified().size(),
          factMessageIds(typedMemory.snapshot()).size(),
          refutedObligationIds(proofGraph.snapshot()).size(),
          closedObligationCount(),
          totalProofDebt(),
          (int) routes.stream().filter(route -> route.checkpointProcessed).count(),
          admittedStrategies.size());
    }

    @Override
    public int currentRound() {
      return roundIndex.get();
    }

    @Override
    public int noGainExhaustionThreshold() {
      return Math.max(1, config.scheduler().maxNormalAttemptsPerSignature());
    }

    @Override
    public boolean execute(BudgetActionCandidate action) {
      return switch (action.action()) {
        case DEEPEN -> deepenRoute(action.targetId());
        case REVISE -> reviseFailedRoute(action.targetId());
        case WIDEN -> widenRoutes();
        case VERIFY -> scheduleVerification(action.targetId());
        default -> false;
      };
    }

    private boolean scheduleVerification(String targetRouteId) {
      RouteState route =
          routes.stream()
              .filter(candidate -> candidate.routeId.equals(targetRouteId))
              .filter(candidate -> candidate.attempt != null)
              .filter(candidate -> !candidate.integrated || !candidate.reviewComplete)
              .findFirst()
              .orElse(null);
      if (route == null) {
        return false;
      }
      route.integrated = false;
      route.reviewComplete = false;
      return true;
    }

    @Override
    public void persistReservation() {
      persistUnchecked("budget_envelope_reserved", false);
    }

    @Override
    public void event(String action, boolean applied, String detail) {
      eventSchedulerAction(action, applied, detail);
    }

    private List<PathBudgetStats> pathBudgetStats() {
      Map<String, Long> mechanismCounts =
          routes.stream()
              .map(this::mechanismSignature)
              .collect(
                  java.util.stream.Collectors.groupingBy(
                      java.util.function.Function.identity(),
                      LinkedHashMap::new,
                      java.util.stream.Collectors.counting()));
      return routes.stream()
          .map(route -> pathBudgetStats(route, mechanismCounts.get(mechanismSignature(route))))
          .toList();
    }

    private PathBudgetStats pathBudgetStats(RouteState route, long mechanismCount) {
      VerificationReport review =
          route.detailedReview != null
              ? route.detailedReview
              : route.structuralReview != null ? route.structuralReview : route.skepticReview;
      String verdict =
          review == null
              ? "verified".equals(route.status) ? "pass" : "unknown"
              : review.verdict().value();
      double verificationScore = review == null ? 0.0d : review.confidence();
      double debt = proofGraph.canonicalProofDebt(route.routeId);
      int unresolved =
          (int)
              proofGraph.obligations().stream()
                  .filter(obligation -> obligation.routeIds().contains(route.routeId))
                  .filter(
                      obligation ->
                          Set.of("open", "tentative", "blocked").contains(obligation.status()))
                  .count();
      boolean verified = "verified".equals(route.status) || "pass".equals(verdict);
      boolean complete = verified && unresolved == 0;
      double marginalProgress =
          verified
              ? 1.0d
              : route.attempt != null ? 0.5d : route.checkpoint != null ? 0.25d : 0.0d;
      boolean structurallyValid =
          route.structuralReview == null
              ? route.failure == null
                  || route.failure.failureClass() != ProofControlModels.FailureClass.FRAMING
              : route.structuralReview.verdict() != VerificationVerdict.FAIL;
      return new PathBudgetStats(
          route.strategy.strategyId(),
          route.routeId,
          route.attempt == null ? "" : route.attempt.attemptId(),
          complete,
          verified,
          marginalProgress,
          debt <= 0.0d ? 1.0d : Math.min(1.0d, 1.0d / (1.0d + debt)),
          Math.min(1.0d, 1.0d / Math.max(1L, mechanismCount)),
          1.0d - verificationScore,
          verificationScore,
          verdict,
          attemptFailureClass(route),
          route.failure == null ? 0.0d : route.failure.confidence(),
          route.failure == null ? 0 : Math.max(1, route.noProgressSegments),
          route.revisionCount,
          unresolved,
          route.noProgressSegments,
          0L,
          BigDecimal.ZERO,
          structurallyValid,
          mechanismSignature(route));
    }

    private String mechanismSignature(RouteState route) {
      return strategyMechanisms
          .signature(route.strategy.strategyId())
          .map(signature -> signature.structuralSignatureHash())
          .orElse(route.strategy.strategyId());
    }
  }

  private static final class RouteState {
    private final String routeId;
    private final AgentRuntime author;
    private StrategyCard strategy;
    private RouteTeamPlan plan;
    private int revisionCount;
    private final List<String> claimIds = new ArrayList<>();
    private final List<String> artifactIds = new ArrayList<>();
    private final List<String> salvagedVerifiedClaimIds = new ArrayList<>();
    private final List<String> salvagedCounterexampleIds = new ArrayList<>();
    private final List<String> rejectedClaimIds = new ArrayList<>();
    private final List<String> uncertainClaimIds = new ArrayList<>();
    private final LinkedHashSet<String> courtCaseIds = new LinkedHashSet<>();
    private final LinkedHashSet<String> proofInvalidOpenClaimIds = new LinkedHashSet<>();
    private final LinkedHashSet<String> repairExhaustedClaimIds = new LinkedHashSet<>();
    private final List<DesktopSolveCheckpoint.AttemptRevisionCheckpoint> revisionHistory =
        new ArrayList<>();
    private final List<MessageDelivery> pendingDeliveries = new ArrayList<>();
    private ProofAttempt attempt;
    private VerificationReport skepticReview;
    private ToolAuditReport toolAudit;
    private VerificationReport structuralReview;
    private VerificationReport detailedReview;
    private VerificationReport crossProviderReview;
    private ClaimReviewBatch claimReview;
    private RouteTeamResult teamResult;
    private EscalationPlan escalation;
    private ValidationExecution validationExecution;
    private ContinuationFunctions.Checkpoint checkpoint;
    private ContinuationFunctions.Delta delta;
    private FailureControlService.Failure failure;
    private String deltaId;
    private String status = "pending";
    private String failureReason = "";
    private String nearMissId;
    private int segmentCount;
    private int noProgressSegments;
    private int cooldownUntilRound = -1;
    private boolean metaAbandoned;
    private String metaControlReason = "";
    private String focusObligationId = "";
    private String focusedCanonicalTargetId = "";
    private String focusedBottleneckFamilyId = "";
    private String focusSource = "";
    private String latestResearchCheckpointId = "";
    private final List<String> activeResearchFindingIds = new ArrayList<>();
    private String lastCheckpointedProviderCallId = "";
    private int checkpointRecoveryCount;
    private boolean pendingFindingReconciliation;
    private boolean reviewComplete;
    private boolean checkpointProcessed;
    private boolean integrated;
    private String activeSemanticPivotId = "";
    private final LinkedHashSet<String> semanticPivotIds = new LinkedHashSet<>();
    private String activeStrategyEpochId;
    private final LinkedHashSet<String> retiredActiveClaimIds = new LinkedHashSet<>();
    private final List<ClaimCard> pendingPivotProposedClaims = new ArrayList<>();
    private final LinkedHashSet<String> retiredStrategyFocusObligationIds =
        new LinkedHashSet<>();
    private final LinkedHashSet<String> activeMathematicalObjectIds = new LinkedHashSet<>();
    private String activeDirectionSignature = "forward";

    private RouteState(
        String routeId,
        AgentRuntime author,
        StrategyCard strategy,
        RouteTeamPlan plan,
        int revisionCount) {
      this.routeId = Objects.requireNonNull(routeId, "routeId");
      this.author = Objects.requireNonNull(author, "author");
      this.strategy = Objects.requireNonNull(strategy, "strategy");
      this.plan = Objects.requireNonNull(plan, "plan");
      this.revisionCount = revisionCount;
      this.activeStrategyEpochId = strategy.strategyId();
      List<String> domainSources = new ArrayList<>();
      domainSources.add(strategy.coreIdea());
      domainSources.add(strategy.bottleneck());
      domainSources.addAll(strategy.expectedLemmas());
      domainSources.addAll(strategy.prerequisites());
      this.activeMathematicalObjectIds.addAll(ProofIdentity.domainObjects(domainSources));
    }
  }
}
