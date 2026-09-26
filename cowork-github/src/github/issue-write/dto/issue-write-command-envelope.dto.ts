import {
  IsEnum,
  IsIn,
  IsISO8601,
  IsNotEmpty,
  IsNumber,
  IsObject,
  IsString,
  IsUUID,
} from 'class-validator';
import { IssueWriteCommandType } from '../issue-write-command-type';

// cowork-project(Kotlin)가 github-app.issue-write.command 토픽으로 발행하는 커맨드 봉투.
// payload의 상세 스키마는 commandType별로 다르므로 여기서는 object임만 검증하고,
// 커맨드별 세부 검증은 IssueWriteCommandController가 별도 payload DTO로 수행한다.
export class IssueWriteCommandEnvelopeDto {
  @IsIn([1])
  schemaVersion: number;

  @IsUUID()
  operationId: string;

  @IsString()
  @IsNotEmpty()
  idempotencyKey: string;

  @IsEnum(IssueWriteCommandType)
  commandType: IssueWriteCommandType;

  @IsString()
  @IsNotEmpty()
  owner: string;

  @IsString()
  @IsNotEmpty()
  repo: string;

  @IsNumber()
  requestedBy: number;

  @IsISO8601()
  occurredAt: string;

  @IsObject()
  payload: Record<string, unknown>;
}
