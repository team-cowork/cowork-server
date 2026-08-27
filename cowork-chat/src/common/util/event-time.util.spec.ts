import { parseEventOccurredAt, parseEventTime } from './event-time.util';

describe('parseEventOccurredAt', () => {
    it('offset이 없는 Kotlin LocalDateTime을 환경 TZ와 무관하게 UTC로 해석한다', () => {
        expect(parseEventOccurredAt('2026-08-26T09:30:00.123')).toEqual(
            new Date('2026-08-26T09:30:00.123Z'),
        );
    });

    it('명시된 timezone offset은 그대로 보존한다', () => {
        expect(parseEventOccurredAt('2026-08-26T09:30:00+09:00')).toEqual(
            new Date('2026-08-26T00:30:00Z'),
        );
    });

    it('같은 millisecond 안의 DELETE와 재가입도 nanosecond sourceVersion으로 순서를 구분한다', () => {
        const deleted = parseEventTime('2026-08-26T09:30:00.123456001Z');
        const rejoined = parseEventTime('2026-08-26T09:30:00.123456002Z');

        expect(deleted).not.toBeNull();
        expect(rejoined).not.toBeNull();
        expect(rejoined?.sourceVersion.greaterThan(deleted!.sourceVersion)).toBe(true);
        expect(deleted?.occurredAt).toEqual(rejoined?.occurredAt);
    });

    it('유효하지 않은 값은 null을 반환한다', () => {
        expect(parseEventOccurredAt('invalid')).toBeNull();
        expect(parseEventOccurredAt(undefined)).toBeNull();
    });
});
