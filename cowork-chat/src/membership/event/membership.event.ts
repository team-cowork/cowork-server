export interface ChannelMemberEvent {
    eventType: 'JOIN' | 'LEAVE' | 'ROLE_CHANGE';
    channelId: number;
    /** DM 채널은 팀에 속하지 않으므로 null */
    teamId: number | null;
    userId: number;
    role: string;
    /** 채널 타입 (TEXT, VOICE, DM) — DM 채널 식별에 사용 */
    channelType: string;
    occurredAt: string;
}
