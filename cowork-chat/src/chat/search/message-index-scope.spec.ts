import {Types} from 'mongoose';
import {SEARCH_INDEX_SCOPE_FILTER, buildMessageIndexDoc, isSearchIndexed} from './message-index-scope.js';

describe('message index scope', () => {
	it.each([
		{name: '프로젝트에 연결된 팀 채널 메시지', message: {teamId: 1, projectId: 7, type: 'TEXT'}, expected: true},
		{name: '프로젝트 없는 팀 채널 메시지', message: {teamId: 1, projectId: null, type: 'TEXT'}, expected: true},
		{name: 'DM 메시지', message: {teamId: null, projectId: null, type: 'TEXT'}, expected: false},
		{name: '팀 채널의 SYSTEM 메시지', message: {teamId: 1, projectId: null, type: 'SYSTEM'}, expected: false},
	])('$name의 색인 대상 여부는 $expected이다', ({message, expected}) => {
		expect(isSearchIndexed(message)).toBe(expected);
	});

	it('전체 재구축 범위 조건은 projectId를 보지 않는다', () => {
		expect(SEARCH_INDEX_SCOPE_FILTER).not.toHaveProperty('projectId');
		expect(SEARCH_INDEX_SCOPE_FILTER).toMatchObject({teamId: {$ne: null}, type: {$ne: 'SYSTEM'}});
	});

	it('프로젝트 없는 채널 메시지는 projectId를 null로 색인한다', () => {
		const id = new Types.ObjectId();

		const doc = buildMessageIndexDoc({
			_id: id,
			teamId: 1,
			projectId: null,
			channelId: 10,
			authorId: 42,
			content: 'srchprobe1',
			type: 'TEXT',
			createdAt: new Date('2026-11-10T06:52:00.000Z'),
		});

		expect(doc).toMatchObject({
			messageId: id.toHexString(), teamId: 1, projectId: null, channelId: 10,
		});
	});
});
