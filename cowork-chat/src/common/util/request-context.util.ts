import {UnauthorizedException} from '@nestjs/common';
import {isSafePositiveInteger} from './safe-integer.util.js';

// Gateway는 agent JWT 요청에만 이 헤더를 붙이고, 클라이언트가 보낸 같은 헤더는 항상 제거한다.
export const AGENT_REQUEST_HEADER = 'x-cowork-agent';

export const requestContextUtil = {
	getUserId(headers: Record<string, string | string[] | undefined>): number {
		const raw = headers['x-user-id'];
		const value = Array.isArray(raw) ? raw[0] : raw;
		if (value === undefined || value === '') {
			throw new UnauthorizedException('x-user-id header missing');
		}

		const id = Number(value);
		if (!isSafePositiveInteger(id)) {
			throw new UnauthorizedException('x-user-id is invalid');
		}

		return id;
	},
};
