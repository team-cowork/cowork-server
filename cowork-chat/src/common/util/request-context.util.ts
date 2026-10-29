import {UnauthorizedException} from '@nestjs/common';
import {UserRole} from '../enum/user-role.enum.js';
import {isSafePositiveInteger} from './safe-integer.util.js';

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

	getUserRole(headers: Record<string, string | string[] | undefined>): string {
		const raw = headers['x-user-role'];
		const value = Array.isArray(raw) ? raw[0] : raw;
		return value ?? UserRole.USER;
	},
};
