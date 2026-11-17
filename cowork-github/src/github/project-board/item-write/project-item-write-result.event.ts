import {type ProjectItemWriteCommandType} from './project-item-write-topics.js';

export type ProjectItemWriteResultEvent = {
	schemaVersion: 1;
	operationId: string;
	idempotencyKey: string;
	commandType: ProjectItemWriteCommandType;
	status: 'SUCCEEDED' | 'FAILED';
	result?: unknown;
	error?: {code: string; message: string};
	occurredAt: string;
};
