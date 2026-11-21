import {syncCompletedKey, syncCompletedMarker} from './sync-completed.js';

describe('syncCompletedMarker', () => {
	it('owner/repo를 소문자로 맞추고 repo 단위 key를 만든다', () => {
		const marker = syncCompletedMarker('Team-Cowork', 'Cowork-Server');

		expect(marker).toMatchObject({recordType: 'SYNC_COMPLETED', owner: 'team-cowork', repo: 'cowork-server'});
		expect(syncCompletedKey(marker)).toBe('sync:team-cowork/cowork-server');
	});

	it('repo가 없으면 org 단위 key를 만든다', () => {
		const marker = syncCompletedMarker('Team-Cowork', null);

		expect(marker.repo).toBeNull();
		expect(syncCompletedKey(marker)).toBe('sync:team-cowork');
	});
});
