import {type TeamRoleProjectionView} from '../repository/index.js';

/**
Discord role union, then default overwrite, then custom role denies and allows.
*/
export function hasChannelPermission(
	roles: TeamRoleProjectionView[],
	policies: Map<number, Record<string, boolean>>,
	permission: string,
	isPrivateAccess = false,
): boolean {
	let isAllowed = !isPrivateAccess && roles.some(role => role.permissions?.[permission] === true);
	for (const role of roles) {
		if (!role.isDefault) {
			continue;
		}

		const override = policies.get(role.roleId)?.[permission];
		if (override !== undefined) {
			isAllowed = override;
		}
	}

	const overrides = new Set(roles.filter(role => !role.isDefault)
		.map(role => policies.get(role.roleId)?.[permission]));
	if (overrides.has(false)) {
		isAllowed = false;
	}

	if (overrides.has(true)) {
		isAllowed = true;
	}

	return isAllowed;
}
