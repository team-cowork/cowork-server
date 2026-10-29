import {type ChannelProjectionView} from '../repository/index.js';
import {type Nullable} from '../../common/type/nullable.js';

export type MessageScope = {
	teamId: Nullable<number>;
	projectId: Nullable<number>;
};

/**
A message belongs to the active channel projection, never to a caller-supplied scope.
*/
export function resolveMessageScope(channel: ChannelProjectionView): Nullable<MessageScope> {
	const teamId = channel.teamId ?? null;
	const projectId = channel.projectId ?? null;
	if (channel.type === 'DM') {
		return teamId === null && projectId === null
			? {teamId: null, projectId: null}
			: null;
	}

	return teamId === null ? null : {teamId, projectId};
}
