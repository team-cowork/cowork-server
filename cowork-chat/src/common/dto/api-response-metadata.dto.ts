import {type Type} from '@nestjs/common';
import {
	ApiProperty as apiProperty,
	ApiResponse as apiResponse,
	getSchemaPath,
	type ReferenceObject,
	type SchemaObject,
} from '@nestjs/swagger';

/**
Documents the SDK-compatible response fields returned through Gateway.
*/
export class ApiResponseMetadataDto {
	@apiProperty({description: 'HTTP 상태 코드와 이름입니다. 예: 200 OK.'}) status!: string;
	@apiProperty({description: 'HTTP 상태 코드입니다.'}) code!: number;
	@apiProperty({description: '응답 메시지'}) message!: string;
}

// Gateway writes Spring HttpStatus names, so 413 is CONTENT_TOO_LARGE, not Nest's PAYLOAD_TOO_LARGE.
const statusNames = {
	200: 'OK',
	201: 'CREATED',
	400: 'BAD_REQUEST',
	401: 'UNAUTHORIZED',
	403: 'FORBIDDEN',
	404: 'NOT_FOUND',
	409: 'CONFLICT',
	410: 'GONE',
	413: 'CONTENT_TOO_LARGE',
	429: 'TOO_MANY_REQUESTS',
	503: 'SERVICE_UNAVAILABLE',
} as const;

const successMessages = {200: 'OK', 201: 'Created'} as const;

type SuccessCode = keyof typeof successMessages;
type ErrorCode = Exclude<keyof typeof statusNames, SuccessCode>;
type EnvelopeData = Type | SchemaObject | ReferenceObject;

/**
Documents one Gateway response envelope. A success response passes its data schema, or a DTO class for a `$ref`. An error response passes no data.
*/
export function apiEnvelopeResponse(code: SuccessCode, description: string, data: EnvelopeData): MethodDecorator & ClassDecorator;
export function apiEnvelopeResponse(code: ErrorCode, description: string): MethodDecorator & ClassDecorator;
export function apiEnvelopeResponse(code: SuccessCode | ErrorCode, description: string, data?: EnvelopeData): MethodDecorator & ClassDecorator {
	return apiResponse({status: code, description, schema: envelopeSchema(code, data)});
}

function envelopeSchema(code: SuccessCode | ErrorCode, data?: EnvelopeData): SchemaObject {
	const properties: Record<string, SchemaObject | ReferenceObject> = {
		status: {type: 'string', enum: [`${code} ${statusNames[code]}`]},
		code: {type: 'integer', enum: [code]},
	};
	if (data === undefined) {
		return {allOf: [{$ref: getSchemaPath(ApiResponseMetadataDto)}, {type: 'object', properties}]};
	}

	properties.message = {type: 'string', example: successMessages[code as SuccessCode]};
	properties.data = typeof data === 'function' ? {$ref: getSchemaPath(data)} : data;
	return {allOf: [{$ref: getSchemaPath(ApiResponseMetadataDto)}, {type: 'object', required: ['data'], properties}]};
}
