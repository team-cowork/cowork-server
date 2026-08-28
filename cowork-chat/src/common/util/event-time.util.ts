import { mongo } from 'mongoose';

const ISO_EVENT_TIME = /^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?([zZ]|[+-]\d{2}:?\d{2})?$/;
const NANOS_PER_MILLISECOND = 1_000_000n;
const MAX_SIGNED_LONG = 9_223_372_036_854_775_807n;

export interface ParsedEventTime {
    /** 표시와 기존 필드 호환을 위한 millisecond 정밀도 값. */
    occurredAt: Date;
    /** 실제 ordering에 사용하는 epoch nanoseconds BSON Long. */
    sourceVersion: mongo.Long;
}

/** ISO-8601 원문의 최대 9자리 fraction을 보존해 epoch nanoseconds로 변환한다. */
export function parseEventTime(value: unknown): ParsedEventTime | null {
    if (typeof value !== 'string') return null;
    const match = ISO_EVENT_TIME.exec(value);
    if (!match) return null;

    const [, yearText, monthText, dayText, hourText, minuteText, secondText, fraction = '', zone] = match;
    const year = Number(yearText);
    const month = Number(monthText);
    const day = Number(dayText);
    const hour = Number(hourText);
    const minute = Number(minuteText);
    const second = Number(secondText);
    if (year < 1970 || month < 1 || month > 12 || hour > 23 || minute > 59 || second > 59) return null;

    const localMilliseconds = Date.UTC(year, month - 1, day, hour, minute, second);
    const localDate = new Date(localMilliseconds);
    if (localDate.getUTCFullYear() !== year
        || localDate.getUTCMonth() !== month - 1
        || localDate.getUTCDate() !== day
        || localDate.getUTCHours() !== hour
        || localDate.getUTCMinutes() !== minute
        || localDate.getUTCSeconds() !== second) {
        return null;
    }

    let offsetMinutes = 0;
    if (zone && zone.toUpperCase() !== 'Z') {
        const offset = zone.slice(1).replace(':', '');
        const offsetHours = Number(offset.slice(0, 2));
        const offsetMinutePart = Number(offset.slice(2));
        if (offsetHours > 23 || offsetMinutePart > 59) return null;
        offsetMinutes = (zone.startsWith('+') ? 1 : -1) * (offsetHours * 60 + offsetMinutePart);
    }

    const utcMilliseconds = localMilliseconds - offsetMinutes * 60_000;
    const fractionNanos = BigInt(fraction.padEnd(9, '0'));
    const epochNanos = BigInt(utcMilliseconds) * NANOS_PER_MILLISECOND + fractionNanos;
    if (epochNanos > MAX_SIGNED_LONG) return null;

    return {
        occurredAt: new Date(utcMilliseconds + Number(fractionNanos / NANOS_PER_MILLISECOND)),
        sourceVersion: mongo.Long.fromBigInt(epochNanos),
    };
}

/**
 * 상태 이벤트의 발생 시각을 일관된 UTC Date로 변환한다.
 * Kotlin `LocalDateTime`의 legacy offset-less 값은 실행 환경 TZ에 의존하지 않도록 UTC로 해석한다.
 */
export function parseEventOccurredAt(value: unknown): Date | null {
    return parseEventTime(value)?.occurredAt ?? null;
}
