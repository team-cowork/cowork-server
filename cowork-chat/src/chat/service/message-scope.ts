import { ChannelProjectionView } from '../repository';

export interface MessageScope {
    teamId: number | null;
    projectId: number | null;
}

/** A message belongs to the active channel projection, never to a caller-supplied scope. */
export function resolveMessageScope(channel: ChannelProjectionView): MessageScope | null {
    const teamId = channel.teamId ?? null;
    const projectId = channel.projectId ?? null;
    if (channel.type === 'DM') {
        return teamId === null && projectId === null
            ? { teamId: null, projectId: null }
            : null;
    }
    if (teamId === null) return null;
    return { teamId, projectId };
}
