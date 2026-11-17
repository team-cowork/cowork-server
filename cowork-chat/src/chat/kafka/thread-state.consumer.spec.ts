import {mongo} from 'mongoose';
import {type ThreadState} from '../repository/thread-projection.repository.js';
import {threadSocketEvent} from './thread-state.consumer.js';

const parentMessageId = '507f1f77bcf86cd799439011';

const state: ThreadState = {
	threadId: 7,
	channelId: 1,
	name: '배포 논의',
	parentMessageId,
	createdBy: 42,
	status: 'ACTIVE',
	archived: false,
	deleted: false,
	occurredAt: new Date('2026-11-17T00:00:00.000Z'),
	sourceVersion: mongo.Long.fromNumber(1),
};

const previousRow = {
	threadId: 7, channelId: 1, name: '배포 논의', parentMessageId, createdBy: 42, archived: false, deleted: false,
};

describe('threadSocketEvent', () => {
	it('바뀐 것이 없으면 알리지 않는다', () => {
		expect(threadSocketEvent(state, {applied: false, previous: null})).toBeNull();
	});

	it('검증 대기 중인 스레드는 알리지 않는다', () => {
		expect(threadSocketEvent({...state, status: 'PENDING'}, {applied: true, previous: null})).toBeNull();
	});

	it('처음 확정된 스레드는 채널에 thread:created로 알린다', () => {
		const event = threadSocketEvent(state, {applied: true, previous: {...previousRow, status: 'PENDING'}});

		expect(event).toEqual({
			target: 'channel',
			event: 'thread:created',
			payload: {
				id: 7, channelId: 1, name: '배포 논의', parentMessageId, isArchived: false, createdBy: 42,
			},
		});
	});

	it('이미 확정된 스레드의 변경은 채널에 thread:updated로 알린다', () => {
		const event = threadSocketEvent({...state, archived: true}, {applied: true, previous: {...previousRow, status: 'ACTIVE'}});

		expect(event).toMatchObject({target: 'channel', event: 'thread:updated', payload: {isArchived: true}});
	});

	it('거부된 스레드는 만든 사용자에게만 thread:rejected로 알린다', () => {
		const event = threadSocketEvent({...state, status: 'REJECTED', deleted: true}, {applied: true, previous: {...previousRow, status: 'PENDING'}});

		expect(event).toEqual({target: 'user', event: 'thread:rejected', payload: {id: 7, channelId: 1, parentMessageId}});
	});

	it('채널 삭제로 지워진 스레드는 알리지 않는다', () => {
		expect(threadSocketEvent({...state, deleted: true}, {applied: true, previous: {...previousRow, status: 'ACTIVE'}})).toBeNull();
	});
});
