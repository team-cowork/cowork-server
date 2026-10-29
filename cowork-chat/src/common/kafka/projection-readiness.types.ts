import {type Admin, type Consumer, type Kafka} from 'kafkajs';
import {type ProjectionAssignmentLease, type ProjectionCheckpointOffset} from './projection-checkpoint.repository.js';
import {type ProjectionDatasetState} from './projection-dataset.repository.js';
import {type ProjectionDatasetStatus, type ProjectionRunMode} from './projection-dataset.schema.js';
import {type ProjectionStream, type StartupPartitionOffset} from './projection-streams.js';

export type AssignmentClaimContext = {
	state: ProjectionCatchupState;
	pending: PendingProjectionAssignment;
	groupGenerationId: number;
	dataset: ProjectionDatasetState;
	currentByPartition: Map<number, StartupPartitionOffset>;
};

export type ClaimedAssignment = {
	partition: number;
	target: StartupPartitionOffset;
	lease: ProjectionAssignmentLease;
	checkpoint: ProjectionCheckpointOffset;
};

export type PendingProjectionAssignment = {
	revision: number;
	memberId: string;
	assignedPartitions: number[];
	afterHeartbeatSequence: number;
	groupGenerationId?: number;
	claimInFlight: boolean;
};

export type ProjectionCatchupState = {
	stream: ProjectionStream;
	consumer: Consumer;
	targets: StartupPartitionOffset[];
	checkpoints: Map<number, string>;
	checkpointLeases: Map<number, ProjectionAssignmentLease>;
	snapshotBarriers: Map<number, string>;
	invalidRecordOffsets: Map<number, string>;
	snapshotOccurredAt: Map<number, Date>;
	localAssignmentLeases: Map<number, ProjectionAssignmentLease>;
	pendingAssignment?: PendingProjectionAssignment;
	heartbeatSequence: number;
	renewalInFlight: boolean;
	leaseRenewalPaused: boolean;
	assignmentObserved: boolean;
	assignmentRevision: number;
	assignmentTask: Promise<void>;
	dataset: ProjectionDatasetState;
	startOffsets: Map<number, string>;
	activatedDatasetGeneration?: string;
	catchupStartedAt: number;
	lastDocumentCountCheckAt?: number;
	ready: boolean;
	checkPromise?: Promise<void>;
	pollTimer?: NodeJS.Timeout;
};

export type ProjectionPartitionStatus = {
	partition: number;
	startOffset?: string;
	currentOffset?: string;
	targetOffset: string;
	lowOffset: string;
	lag?: string;
};

export type ProjectionStreamStatus = {
	ready: boolean;
	mode: ProjectionRunMode;
	status: ProjectionDatasetStatus;
	datasetGeneration: string;
	sourceGeneration: string;
	reason?: string;
	partitions: ProjectionPartitionStatus[];
};

export type ProjectionAdminConnection = {
	kafka: Kafka;
	admin?: Admin;
	connectPromise?: Promise<Admin>;
};
