import {
	IsEnum as isEnum,
	IsIn as isIn,
	IsISO8601 as isIso8601,
	IsNotEmpty as isNotEmpty,
	IsNumber as isNumber,
	IsObject as isObject,
	IsOptional as isOptional,
	IsString as isString,
	IsUUID as isUuid,
} from 'class-validator';
import {ProjectItemWriteCommandType} from '../project-item-write-topics.js';
import {type Nullable} from '../../../common/nullable.js';

// Cowork-project가 github.project-item-write.command.v1로 발행하는 Project item 쓰기 커맨드 봉투.
// payload는 commandType마다 모양이 달라 컨트롤러가 별도 DTO로 검증한다.
// 카드 생성처럼 아직 item이 없는 커맨드를 받을 수 있도록 itemId는 봉투에서 선택 값이고, 필요 여부는 커맨드마다 검사한다.
export class ProjectItemWriteCommandEnvelopeDto {
	@isIn([1])
	schemaVersion: number;

	@isUuid()
	operationId: string;

	@isString()
	@isNotEmpty()
	idempotencyKey: string;

	@isEnum(ProjectItemWriteCommandType)
	commandType: ProjectItemWriteCommandType;

	@isString()
	@isNotEmpty()
	owner: string;

	@isString()
	@isNotEmpty()
	projectNodeId: string;

	@isOptional()
	@isString()
	@isNotEmpty()
	itemId?: Nullable<string>;

	@isNumber()
	requestedBy: number;

	@isIso8601()
	occurredAt: string;

	@isObject()
	payload: Record<string, unknown>;
}
