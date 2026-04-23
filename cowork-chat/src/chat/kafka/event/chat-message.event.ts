export interface ChatMessageEvent {
    eventType: string;
    teamId: number;
    projectId?: number | null;
    channelId: number;
    authorId: number;
    authorRole: string;
    content: string;
    type: string;
    attachments?: any[];
    parentMessageId?: string;
    clientMessageId?: string;
    occurredAt: string;
}
