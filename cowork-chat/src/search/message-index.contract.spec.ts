import {classifyBulkItemResult} from './message-index.contract.js';

describe('classifyBulkItemResult', () => {
	it.each([
		{
			name: '문서가 없는 delete 404는 tombstone을 남기고 성공으로 본다',
			action: 'delete' as const,
			status: 404,
			error: {type: 'document_missing_exception', reason: '[1]: document missing'},
			expected: {outcome: 'APPLIED'},
		},
		{
			name: '인덱스가 없는 delete 404는 성공이 아니라 재시도 대상이다',
			action: 'delete' as const,
			status: 404,
			error: {type: 'index_not_found_exception', reason: 'no such index [chat_messages]'},
			expected: {outcome: 'RETRYABLE', error: 'no such index [chat_messages]'},
		},
		{
			name: 'index 404는 delete 예외 없이 재시도 대상이다',
			action: 'index' as const,
			status: 404,
			error: {type: 'document_missing_exception', reason: '[1]: document missing'},
			expected: {outcome: 'RETRYABLE', error: '[1]: document missing'},
		},
		{
			name: 'delete 409는 더 최신 버전이 이미 있다는 뜻이라 SUPERSEDED다',
			action: 'delete' as const,
			status: 409,
			error: {type: 'version_conflict_engine_exception', reason: 'version conflict'},
			expected: {outcome: 'SUPERSEDED'},
		},
		{
			name: 'index 409도 SUPERSEDED다',
			action: 'index' as const,
			status: 409,
			error: {type: 'version_conflict_engine_exception', reason: 'version conflict'},
			expected: {outcome: 'SUPERSEDED'},
		},
		{
			name: '400은 계약 위반이라 reason을 담아 PERMANENT로 본다',
			action: 'index' as const,
			status: 400,
			error: {type: 'mapper_parsing_exception', reason: 'failed to parse field [createdAt]'},
			expected: {outcome: 'PERMANENT', error: 'failed to parse field [createdAt]'},
		},
		{
			name: '400에 reason이 없으면 type을 오류로 남긴다',
			action: 'delete' as const,
			status: 400,
			error: {type: 'illegal_argument_exception'},
			expected: {outcome: 'PERMANENT', error: 'illegal_argument_exception'},
		},
		{
			name: '429는 일시적 거절이라 RETRYABLE이다',
			action: 'index' as const,
			status: 429,
			error: {type: 'es_rejected_execution_exception', reason: 'queue capacity'},
			expected: {outcome: 'RETRYABLE', error: 'queue capacity'},
		},
		{
			name: '오류 객체 없이 실패한 delete는 상태 코드를 담아 RETRYABLE이다',
			action: 'delete' as const,
			status: 500,
			error: undefined,
			expected: {outcome: 'RETRYABLE', error: 'bulk delete failed with status 500'},
		},
		{
			name: '상태 코드도 오류도 없으면 unknown으로 RETRYABLE이다',
			action: 'index' as const,
			status: undefined,
			error: undefined,
			expected: {outcome: 'RETRYABLE', error: 'bulk index failed with status unknown'},
		},
	])('$name', ({action, status, error, expected}) => {
		expect(classifyBulkItemResult(action, status, error)).toEqual(expected);
	});
});
