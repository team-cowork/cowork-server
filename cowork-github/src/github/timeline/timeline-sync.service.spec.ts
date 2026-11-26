import type {AppConfigService} from '../../config/app-config.service.js';
import type {KafkaProducerService} from '../../kafka/kafka-producer.service.js';
import {githubField} from '../github-field.js';
import {GithubClientError} from '../github.errors.js';
import type {TimelineApiClient} from './timeline-api.client.js';
import {TimelineSyncService} from './timeline-sync.service.js';

const event = (id: number) => ({event: 'closed', id, [githubField.createdAt]: '2026-01-01T00:00:00Z'});

describe('TimelineSyncService', () => {
	const run = async (client: Partial<TimelineApiClient>, send: jest.Mock, renew = jest.fn()) =>
		new TimelineSyncService(client as TimelineApiClient, {send} as unknown as KafkaProducerService, {githubAppId: '1'} as AppConfigService)
			.sync('ORG', 'Repo', 4, renew);

	it('다음 페이지의 중복 이벤트를 제거하고 마지막에 완료 레코드를 보낸다', async () => {
		const send = jest.fn();
		const first = Array.from({length: 100}, (_, id) => event(id));
		await run({
			parent: async () => 'ISSUE', async * pages() {
				yield first;
				yield [event(99), event(100)];
			},
		}, send);
		expect(send).toHaveBeenCalledTimes(103);
		expect(send).toHaveBeenLastCalledWith('github.timeline.state.v1', 'org/repo#4:complete', expect.objectContaining({count: 101, parent: 'ISSUE'}));
	});

	it('후속 페이지 오류가 나면 부분 이력을 게시하지 않는다', async () => {
		const send = jest.fn();
		await expect(run({
			parent: async () => 'ISSUE', async * pages() {
				yield [event(1)];
				throw new Error('timeout');
			},
		}, send)).rejects.toThrow('timeout');
		expect(send).toHaveBeenCalledTimes(2);
		expect(send).toHaveBeenCalledWith('github.timeline.state.v1', 'org/repo#4:closed:1', expect.anything());
	});

	it('없는 대상과 활동이 없는 대상의 완료 레코드를 구분한다', async () => {
		const send = jest.fn();
		await run({
			async parent() {
				throw new GithubClientError('missing', 404);
			},
		}, send);
		expect(send).toHaveBeenCalledWith('github.timeline.state.v1', 'org/repo#4:complete', expect.objectContaining({count: 0, parent: 'MISSING'}));
	});

	it('락 소유권을 잃으면 완료 레코드를 보내지 않는다', async () => {
		const send = jest.fn();
		const renew = jest.fn().mockRejectedValue(new Error('lost lock'));
		await expect(run({
			parent: async () => 'PULL_REQUEST', async * pages() {
				yield [];
			},
		}, send, renew)).rejects.toThrow('lost lock');
		expect(send).not.toHaveBeenCalled();
	});
});
