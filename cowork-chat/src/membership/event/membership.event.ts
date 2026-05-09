export interface ChannelMemberEvent {
    eventType: 'JOIN' | 'LEAVE' | 'ROLE_CHANGE';
    channelId: number;
    teamId: number;
    userId: number;
    role: string;
    occurredAt: string;
}
