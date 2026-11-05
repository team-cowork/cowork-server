import {ApiProperty as apiProperty} from '@nestjs/swagger';

/**
Documents the SDK-compatible response fields returned through Gateway.
*/
export class ApiResponseMetadataDto {
	@apiProperty({description: 'HTTP 상태 코드와 이름입니다. 예: 200 OK.'}) status!: string;
	@apiProperty({description: 'HTTP 상태 코드입니다.'}) code!: number;
	@apiProperty({description: '응답 메시지'}) message!: string;
}
