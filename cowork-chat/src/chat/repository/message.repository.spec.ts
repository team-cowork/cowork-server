import { Types } from 'mongoose';
import { toMessageBroadcastPayload } from './message.repository';

describe('toMessageBroadcastPayload', () => {
    it('클라이언트가 쓰는 필드만 남기고 아웃박스 내부 상태 필드는 제외한다', () => {
        const id = new Types.ObjectId();
        const createdAt = new Date('2026-05-12T00:00:00.000Z');
        const fullDocument = {
            _id: id,
            teamId: 10,
            projectId: null,
            channelId: 1,
            authorId: 42,
            content: '안녕하세요',
            type: 'TEXT',
            attachments: [],
            parentMessageId: null,
            isEdited: false,
            editHistory: [],
            isPinned: false,
            reactions: [],
            clientMessageId: 'client-1',
            mentions: [],
            createdAt,
            updatedAt: createdAt,
            notificationStatus: 'PENDING',
            notificationRetryCount: 0,
            notificationProcessingStartedAt: null,
            notificationClaimId: null,
            searchIndexStatus: 'PENDING',
            searchIndexVersion: 1,
            searchIndexSyncedVersion: 0,
            searchIndexRetryCount: 0,
            searchIndexNextAttemptAt: createdAt,
            searchIndexProcessingStartedAt: null,
            searchIndexClaimId: null,
            searchIndexLastError: null,
            searchIndexSyncedAt: null,
        };
        const doc = { toObject: () => fullDocument } as unknown as Parameters<typeof toMessageBroadcastPayload>[0];

        const payload = toMessageBroadcastPayload(doc);

        expect(payload).toEqual({
            _id: id,
            teamId: 10,
            projectId: null,
            channelId: 1,
            authorId: 42,
            content: '안녕하세요',
            type: 'TEXT',
            attachments: [],
            parentMessageId: null,
            isEdited: false,
            isPinned: false,
            clientMessageId: 'client-1',
            mentions: [],
            reactions: [],
            createdAt,
            updatedAt: createdAt,
        });
        expect(payload).not.toHaveProperty('notificationStatus');
        expect(payload).not.toHaveProperty('searchIndexStatus');
        expect(payload).not.toHaveProperty('editHistory');
    });
});
