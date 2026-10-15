import {type IssueWriteCommandType} from '../issue-write-command-type';

export type IssueWriteResultError = {
    code: string;
    message: string;
};

export type IssueWriteResultEvent = {
    schemaVersion: 1;
    operationId: string;
    idempotencyKey: string;
    commandType: IssueWriteCommandType;
    status: 'SUCCEEDED' | 'FAILED';
    result?: unknown;
    error?: IssueWriteResultError;
    occurredAt: string;
};
