import {SetMetadata as setMetadata} from '@nestjs/common';
import {type UserRole} from '../enum/user-role.enum.js';

export const ROLES_KEY = 'roles';
export const requireRoles = (...roles: UserRole[]) => setMetadata(ROLES_KEY, roles);
