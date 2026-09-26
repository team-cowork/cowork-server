import { IssueWriteCommandType } from '../issue-write-command-type';

export interface IssueWriteResultError {
  code: string;
  message: string;
}

export interface IssueWriteResultEvent {
  schemaVersion: 1;
  operationId: string;
  idempotencyKey: string;
  commandType: IssueWriteCommandType;
  status: 'SUCCEEDED' | 'FAILED';
  result?: unknown;
  error?: IssueWriteResultError;
  occurredAt: string;
}
