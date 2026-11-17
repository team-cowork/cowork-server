import {judgeThreadParent} from './thread-parent-command.consumer.js';

const command = {
	schemaVersion: 1 as const, threadId: 7, channelId: 1, parentMessageId: '507f1f77bcf86cd799439011',
};

describe('judgeThreadParent', () => {
	it('같은 채널의 채널 메시지이면 수락한다', () => {
		expect(judgeThreadParent(command, {threadId: null})).toEqual({...command, accepted: true, reason: null});
		expect(judgeThreadParent(command, {})).toEqual({...command, accepted: true, reason: null});
	});

	it('같은 채널에 메시지가 없으면 거부한다', () => {
		expect(judgeThreadParent(command, null)).toEqual({...command, accepted: false, reason: 'PARENT_NOT_FOUND'});
	});

	it('스레드 메시지로는 스레드를 만들 수 없다', () => {
		expect(judgeThreadParent(command, {threadId: 3})).toEqual({...command, accepted: false, reason: 'PARENT_IN_THREAD'});
	});
});
