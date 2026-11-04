import {
	IsEnum as isEnum,
	IsIn as isIn,
	IsISO8601 as isIso8601,
	IsNotEmpty as isNotEmpty,
	IsNumber as isNumber,
	IsObject as isObject,
	IsString as isString,
	IsUUID as isUuid,
} from 'class-validator';
import {IssueWriteCommandType} from '../issue-write-command-type.js';

// Cowork-project(Kotlin)가 github.issue-write.command.v1 토픽으로 발행하는 커맨드 봉투.
// payload의 상세 스키마는 commandType별로 다르므로 여기서는 object임만 검증하고,
// 커맨드별 세부 검증은 IssueWriteCommandController가 별도 payload DTO로 수행한다.
export class IssueWriteCommandEnvelopeDto {
	@isIn([1])
	schemaVersion: number;

	@isUuid()
	operationId: string;

	@isString()
	@isNotEmpty()
	idempotencyKey: string;

	@isEnum(IssueWriteCommandType)
	commandType: IssueWriteCommandType;

	@isString()
	@isNotEmpty()
	owner: string;

	@isString()
	@isNotEmpty()
	repo: string;

	@isNumber()
	requestedBy: number;

	@isIso8601()
	occurredAt: string;

	@isObject()
	payload: Record<string, unknown>;
}
