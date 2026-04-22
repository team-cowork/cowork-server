export interface ChannelMemberEvent {
    eventType: 'JOIN' | 'LEAVE' | 'ROLE_CHANGE';
    channelId: number;
    userId: number;
    role: string;
    occurredAt: string;
}
