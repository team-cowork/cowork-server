import {
	IsInt as isInt,
	IsMongoId as isMongoId,
	IsOptional as isOptional,
	Max as max,
	Min as min,
} from 'class-validator';
import {Type as transformType} from 'class-transformer';
import {ApiPropertyOptional as apiPropertyOptional} from '@nestjs/swagger';

export class GetMessagesDto {
	@apiPropertyOptional({description: '이 메시지 ID 이전 메시지 조회 (커서 페이지네이션)'})
	@isMongoId() @isOptional() before?: string;

	@apiPropertyOptional({description: '이 메시지 ID 이후 메시지 조회 (커서 페이지네이션). before, around와 함께 쓸 수 없음'})
	@isMongoId() @isOptional() after?: string;

	@apiPropertyOptional({description: '이 메시지를 포함해 앞뒤 메시지 조회 (검색 결과 위치 이동). before, after와 함께 쓸 수 없음'})
	@isMongoId() @isOptional() around?: string;

	@apiPropertyOptional({description: '답장 조회 - 지정한 원본 메시지에 단 답장만 반환'})
	@isMongoId() @isOptional() parentMessageId?: string;

	@apiPropertyOptional({description: '스레드 메시지 조회 - 지정한 스레드의 메시지만 반환. 생략하면 스레드에 속하지 않은 채널 메시지만 반환'})
	@isInt() @min(1) @max(Number.MAX_SAFE_INTEGER) @isOptional() @transformType(() => Number) threadId?: number;
}
