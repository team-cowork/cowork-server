import {mongo} from 'mongoose';
import {type Nullable} from '../type/nullable.js';

const ISO_EVENT_TIME = /^(?<year>\d{4})-(?<month>\d{2})-(?<day>\d{2})t(?<hour>\d{2}):(?<minute>\d{2}):(?<second>\d{2})(?:\.(?<fraction>\d{1,9}))?(?<zone>z|[+\-]\d{2}:?\d{2})?$/iv;
const NANOS_PER_MILLISECOND = 1_000_000n;
const MAX_SIGNED_LONG = 9_223_372_036_854_775_807n;

export type ParsedEventTime = {
	/**
    표시와 기존 필드 호환을 위한 millisecond 정밀도 값.
    */
	occurredAt: Date;
	/**
    실제 ordering에 사용하는 epoch nanoseconds BSON Long.
    */
	sourceVersion: mongo.Long;
};

/**
`Z`가 아닌 UTC offset을 분 단위로 바꾼다. 범위를 벗어나면 `undefined`를 반환한다.
*/
function parseOffsetMinutes(zone: string | undefined): number | undefined {
	if (typeof zone !== 'string' || zone.toUpperCase() === 'Z') {
		return 0;
	}

	const offset = zone.slice(1).replace(':', '');
	const offsetHours = Number(offset.slice(0, 2));
	const offsetMinutePart = Number(offset.slice(2));
	return offsetHours > 23 || offsetMinutePart > 59 ? undefined : (zone.startsWith('+') ? 1 : -1) * ((offsetHours * 60) + offsetMinutePart);
}

/**
`Date.UTC`가 넘친 값을 다음 단위로 올리지 않았는지, 즉 입력한 날짜와 시각이 실제로 존재하는지 확인한다.
*/
function isExactUtcDate(milliseconds: number, parts: {year: number; month: number; day: number; hour: number; minute: number; second: number}): boolean {
	const date = new Date(milliseconds);
	return date.getUTCFullYear() === parts.year
		&& date.getUTCMonth() === parts.month - 1
		&& date.getUTCDate() === parts.day
		&& date.getUTCHours() === parts.hour
		&& date.getUTCMinutes() === parts.minute
		&& date.getUTCSeconds() === parts.second;
}

/**
ISO-8601 원문의 최대 9자리 fraction을 보존해 epoch nanoseconds로 변환한다.
*/
export function parseEventTime(value: unknown): Nullable<ParsedEventTime> {
	if (typeof value !== 'string') {
		return null;
	}

	const groups = ISO_EVENT_TIME.exec(value)?.groups;
	if (!groups) {
		return null;
	}

	const {fraction = '', zone} = groups;
	const parts = {
		year: Number(groups.year),
		month: Number(groups.month),
		day: Number(groups.day),
		hour: Number(groups.hour),
		minute: Number(groups.minute),
		second: Number(groups.second),
	};
	if (parts.year < 1970 || parts.month < 1 || parts.month > 12 || parts.hour > 23 || parts.minute > 59 || parts.second > 59) {
		return null;
	}

	const localMilliseconds = Date.UTC(parts.year, parts.month - 1, parts.day, parts.hour, parts.minute, parts.second);
	if (!isExactUtcDate(localMilliseconds, parts)) {
		return null;
	}

	const offsetMinutes = parseOffsetMinutes(zone);
	if (offsetMinutes === undefined) {
		return null;
	}

	const utcMilliseconds = localMilliseconds - (offsetMinutes * 60_000);
	const fractionNanos = BigInt(fraction.padEnd(9, '0'));
	const epochNanos = (BigInt(utcMilliseconds) * NANOS_PER_MILLISECOND) + fractionNanos;
	if (epochNanos > MAX_SIGNED_LONG) {
		return null;
	}

	return {
		occurredAt: new Date(utcMilliseconds + Number(fractionNanos / NANOS_PER_MILLISECOND)),
		sourceVersion: mongo.Long.fromBigInt(epochNanos),
	};
}
