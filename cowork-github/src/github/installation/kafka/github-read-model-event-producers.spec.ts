import {Kafka} from 'kafkajs';
import type {AppConfigService} from '../../../config/app-config.service.js';
import {GithubReadModelEventProducers} from './github-read-model-event-producers.js';
import {syncCompletedMarker} from './event/sync-completed.js';

jest.mock('kafkajs');

describe('GithubReadModelEventProducers', () => {
	let producers: GithubReadModelEventProducers;
	let mockSend: jest.Mock;
	let mockConnect: jest.Mock;
	let mockDisconnect: jest.Mock;

	const sentMessage = (call = 0) => {
		const [record] = mockSend.mock.calls[call] as [{topic: string; messages: Array<{key: string; value: string}>}];
		const {topic} = record;
		const [message] = record.messages;
		return {topic, key: message.key, value: JSON.parse(message.value) as Record<string, unknown>};
	};

	beforeEach(async () => {
		mockSend = jest.fn().mockResolvedValue(undefined);
		mockConnect = jest.fn().mockResolvedValue(undefined);
		mockDisconnect = jest.fn().mockResolvedValue(undefined);
		(Kafka as unknown as jest.Mock).mockClear();
		(Kafka as unknown as jest.Mock).mockImplementation(() => ({
			producer: () => ({connect: mockConnect, disconnect: mockDisconnect, send: mockSend}),
		}));

		producers = new GithubReadModelEventProducers({kafkaClientConfig: {brokers: ['localhost:9092']}} as unknown as AppConfigService);
		await producers.onModuleInit();
	});

	it('5개 state 토픽이 Kafka client 하나를 공유한다', async () => {
		await producers.onModuleDestroy();

		expect(Kafka).toHaveBeenCalledTimes(1);
		expect(mockConnect).toHaveBeenCalledTimes(1);
		expect(mockDisconnect).toHaveBeenCalledTimes(1);
	});

	it('repo state는 정규화한 owner/repo를 key로 발행한다', async () => {
		await producers.repo.send({
			installationId: 1,
			owner: 'Team-Cowork',
			repo: 'Cowork-Server',
			fullName: 'Team-Cowork/Cowork-Server',
			private: false,
			htmlUrl: 'https://github.com/Team-Cowork/Cowork-Server',
			deleted: false,
			sourceOccurredAt: '2026-11-05T00:00:00Z',
		});

		const {topic, key, value} = sentMessage();
		expect(topic).toBe('github.repo.state.v1');
		expect(key).toBe('team-cowork/cowork-server');
		expect(value).toMatchObject({owner: 'team-cowork', repo: 'cowork-server', fullName: 'Team-Cowork/Cowork-Server'});
	});

	it('issue state는 owner/repo#number를 key로 발행한다', async () => {
		await producers.issue.send({
			owner: 'o',
			repo: 'r',
			number: 7,
			title: 't',
			author: 'a',
			state: 'open',
			htmlUrl: 'u',
			labels: [],
			createdAt: 'c',
			updatedAt: 'u',
			deleted: false,
			sourceOccurredAt: 's',
		});

		expect(sentMessage()).toMatchObject({topic: 'github.issue.state.v1', key: 'o/r#7'});
	});

	it('pull request state는 owner/repo#number를 key로 발행한다', async () => {
		await producers.pullRequest.send({
			owner: 'o',
			repo: 'r',
			number: 8,
			title: 't',
			body: null,
			author: 'a',
			state: 'open',
			draft: false,
			merged: false,
			htmlUrl: 'u',
			labels: [],
			headRef: 'h',
			baseRef: 'b',
			createdAt: 'c',
			updatedAt: 'u',
			sourceOccurredAt: 's',
		});

		expect(sentMessage()).toMatchObject({topic: 'github.pull-request.state.v1', key: 'o/r#8'});
	});

	it('label state는 owner/repo:name을 key로 발행한다', async () => {
		await producers.label.send({
			owner: 'o',
			repo: 'r',
			name: 'bug',
			color: 'red',
			deleted: false,
			sourceOccurredAt: 's',
		});

		expect(sentMessage()).toMatchObject({topic: 'github.label.state.v1', key: 'o/r:bug'});
	});

	it('issue comment state는 owner/repo#commentId를 key로 발행한다', async () => {
		await producers.issueComment.send({
			owner: 'o',
			repo: 'r',
			commentId: 99,
			parentType: 'ISSUE',
			parentNumber: 7,
			author: 'a',
			body: 'b',
			htmlUrl: 'u',
			createdAt: 'c',
			updatedAt: 'u',
			deleted: false,
			sourceOccurredAt: 's',
		});

		expect(sentMessage()).toMatchObject({topic: 'github.issue-comment.state.v1', key: 'o/r#99'});
	});

	it('동기화 완료 마커는 각 state 토픽에 sync key로 발행한다', async () => {
		await producers.label.sendSyncCompleted(syncCompletedMarker('O', 'R'));

		expect(sentMessage()).toMatchObject({topic: 'github.label.state.v1', key: 'sync:o/r'});
	});
});
