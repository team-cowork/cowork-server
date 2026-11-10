import {isSearchIndexed} from './message-index-scope.js';

describe('message index scope', () => {
	it.each([
		{name: '프로젝트에 연결된 팀 채널 메시지', message: {teamId: 1, projectId: 7, type: 'TEXT'}, expected: true},
		{name: '프로젝트 없는 팀 채널 메시지', message: {teamId: 1, projectId: null, type: 'TEXT'}, expected: true},
		{name: 'DM 메시지', message: {teamId: null, projectId: null, type: 'TEXT'}, expected: false},
		{name: '팀 채널의 SYSTEM 메시지', message: {teamId: 1, projectId: null, type: 'SYSTEM'}, expected: false},
	])('$name의 색인 대상 여부는 $expected이다', ({message, expected}) => {
		expect(isSearchIndexed(message)).toBe(expected);
	});
});
