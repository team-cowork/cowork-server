import {checkThreadTarget, sendPermission} from './thread-message-target.js';

const activeThread = {
	channelId: 1, status: 'ACTIVE', archived: false, deleted: false,
};

describe('sendPermission', () => {
	it('채널 메시지와 답장은 message_send, 스레드 메시지는 thread_reply로 검사한다', () => {
		expect(sendPermission(undefined)).toBe('message_send');
		expect(sendPermission(7)).toBe('thread_reply');
	});
});

describe('checkThreadTarget', () => {
	it('스레드도 답장 원본도 없으면 통과한다', () => {
		expect(checkThreadTarget(1, undefined, undefined, undefined)).toBeNull();
	});

	it('채널 메시지에 단 답장은 통과한다', () => {
		expect(checkThreadTarget(1, undefined, undefined, {threadId: null})).toBeNull();
		expect(checkThreadTarget(1, undefined, undefined, {})).toBeNull();
	});

	it('같은 채널의 확정된 스레드로 보내는 메시지는 통과한다', () => {
		expect(checkThreadTarget(1, 7, activeThread, undefined)).toBeNull();
	});

	it.each([
		['없는 스레드', null],
		['다른 채널의 스레드', {...activeThread, channelId: 2}],
		['검증 대기 중인 스레드', {...activeThread, status: 'PENDING'}],
		['삭제된 스레드', {...activeThread, deleted: true}],
	])('%s로는 보낼 수 없다', (_name, thread) => {
		expect(checkThreadTarget(1, 7, thread, undefined)).toBe('THREAD_NOT_FOUND');
	});

	it('보관된 스레드로는 보낼 수 없다', () => {
		expect(checkThreadTarget(1, 7, {...activeThread, archived: true}, undefined)).toBe('THREAD_ARCHIVED');
	});

	it('스레드 메시지는 같은 스레드의 메시지에만 답장한다', () => {
		expect(checkThreadTarget(1, 7, activeThread, {threadId: 7})).toBeNull();
		expect(checkThreadTarget(1, 7, activeThread, {threadId: 8})).toBe('PARENT_THREAD_MISMATCH');
		expect(checkThreadTarget(1, 7, activeThread, {threadId: null})).toBe('PARENT_THREAD_MISMATCH');
	});

	it('채널 메시지는 스레드 메시지에 답장할 수 없다', () => {
		expect(checkThreadTarget(1, undefined, undefined, {threadId: 7})).toBe('PARENT_THREAD_MISMATCH');
	});
});
