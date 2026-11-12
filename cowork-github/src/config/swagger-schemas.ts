import {type SchemaObject} from '@nestjs/swagger';

export const gatewayApiErrorSchema: SchemaObject = {
	type: 'object',
	required: ['status', 'code', 'message'],
	properties: {
		status: {type: 'string', description: 'HTTP 상태'},
		code: {type: 'integer', description: 'HTTP 상태 코드'},
		message: {type: 'string', description: '오류 메시지'},
	},
};

export const internalApiErrorSchema: SchemaObject = {
	type: 'object',
	required: ['statusCode', 'message'],
	properties: {
		statusCode: {type: 'integer', description: 'HTTP 상태 코드'},
		message: {
			description: '오류 메시지 또는 요청 검증 오류 목록',
			oneOf: [{type: 'string'}, {type: 'array', items: {type: 'string'}}],
		},
		error: {type: 'string', description: '오류 유형 (응답에 따라 생략)'},
	},
};
