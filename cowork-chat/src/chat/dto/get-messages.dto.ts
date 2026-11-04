import {IsOptional as isOptional, IsMongoId as isMongoId} from 'class-validator';
import {ApiPropertyOptional as apiPropertyOptional} from '@nestjs/swagger';

export class GetMessagesDto {
	@apiPropertyOptional({description: '이 메시지 ID 이전 메시지 조회 (커서 페이지네이션)'})
	@isMongoId() @isOptional() before?: string;

	@apiPropertyOptional({description: '이 메시지 ID 이후 메시지 조회 (커서 페이지네이션). before, around와 함께 쓸 수 없음'})
	@isMongoId() @isOptional() after?: string;

	@apiPropertyOptional({description: '이 메시지를 포함해 앞뒤 메시지 조회 (검색 결과 위치 이동). before, after와 함께 쓸 수 없음'})
	@isMongoId() @isOptional() around?: string;

	@apiPropertyOptional({description: '스레드 답글 조회 - 지정한 부모 메시지의 답글만 반환'})
	@isMongoId() @isOptional() parentMessageId?: string;
}
