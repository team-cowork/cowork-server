import {applyDecorators, SetMetadata as setMetadata} from '@nestjs/common';
import {ApiResponse as apiResponse, getSchemaPath} from '@nestjs/swagger';
import {ApiResponseMetadataDto} from '../dto/api-response-metadata.dto.js';
import {type ChatAccountPermission} from '../kafka/account-policy.service.js';

export const ACCOUNT_PERMISSION = 'accountPermission';
export const requirePermission = (permission: ChatAccountPermission) => applyDecorators(
	setMetadata(ACCOUNT_PERMISSION, permission),
	apiResponse({
		status: 403,
		description: `${permission} 계정 권한 필요`,
		schema: {
			allOf: [
				{$ref: getSchemaPath(ApiResponseMetadataDto)},
				{
					type: 'object',
					properties: {status: {type: 'string', enum: ['403 FORBIDDEN']}, code: {type: 'integer', enum: [403]}},
				},
			],
		},
	}),
	apiResponse({status: 503, description: '계정 권한 동기화 중', type: ApiResponseMetadataDto}),
);
