import { truncateUtf8 } from './chat-message-quarantine.repository';
import { ChatMessageQuarantineRecordSchema } from '../schema/chat-message-quarantine.schema';

describe('chat message quarantine persistence contract', () => {
    it('UTF-8 payload를 문자 중간에서 자르지 않고 64KiB 제한을 적용한다', () => {
        const raw = '가'.repeat(30_000);
        const result = truncateUtf8(raw, 65_536);

        expect(result.truncated).toBe(true);
        expect(Buffer.byteLength(result.value!, 'utf8')).toBeLessThanOrEqual(65_536);
        expect(result.value).not.toMatch(/�/);
    });

    it('위치 unique index와 30일 TTL에 필요한 expiresAt index를 선언한다', () => {
        const indexes = ChatMessageQuarantineRecordSchema.indexes();
        expect(indexes).toContainEqual([{ groupId: 1, topic: 1, partition: 1, messageOffset: 1 }, { unique: true }]);
        expect(indexes).toContainEqual([{ expiresAt: 1 }, { expireAfterSeconds: 0 }]);
    });
});
