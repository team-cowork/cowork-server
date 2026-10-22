import type Redis from 'ioredis';
import type {IssueHttpService} from '../issue-http/issue-http.service';
import type {LabelHttpService} from '../label-http/label-http.service';
import type {PullRequestService} from '../pull-request/pull-request.service';
import type {InstallationApiClient} from './client/installation-api.client';
import {GithubBackfillService} from './github-backfill.service';
import type {GithubReadModelEventProducers} from './kafka/github-read-model-event-producers';

const producer = () => ({send: jest.fn(), sendSyncCompleted: jest.fn()});

describe('GithubBackfillService', () => {
    let producers: Record<'repo' | 'issue' | 'pullRequest' | 'label' | 'issueComment', ReturnType<typeof producer>>;
    let labelHttpService: {listLabels: jest.Mock};
    let redis: {set: jest.Mock; eval: jest.Mock};
    let service: GithubBackfillService;

    beforeEach(() => {
        producers = {
            repo: producer(), issue: producer(), pullRequest: producer(), label: producer(), issueComment: producer(),
        };
        labelHttpService = {listLabels: jest.fn().mockResolvedValue([{name: 'bug', color: 'd73a4a'}])};
        redis = {set: jest.fn().mockResolvedValue('OK'), eval: jest.fn()};
        const issueHttpService = {
            listIssues: jest.fn().mockResolvedValue([{number: 1, updatedAt: '2026-01-01T00:00:00Z'}]),
            listComments: jest.fn().mockResolvedValue([{id: 10, updatedAt: '2026-01-01T00:00:00Z'}]),
        };
        const pullRequestService = {
            listPullRequests: jest.fn().mockResolvedValue([{number: 2, updatedAt: '2026-01-01T00:00:00Z'}]),
            getPullRequestDetail: jest.fn().mockResolvedValue({}),
            listPullRequestFiles: jest.fn().mockResolvedValue([]),
        };
        service = new GithubBackfillService(
            {listRepositories: jest.fn().mockResolvedValue([])} as unknown as InstallationApiClient,
            issueHttpService as unknown as IssueHttpService,
            pullRequestService as unknown as PullRequestService,
            labelHttpService as unknown as LabelHttpService,
            producers as unknown as GithubReadModelEventProducers,
            redis as unknown as Redis,
        );
    });

    it('repo 동기화는 토픽마다 데이터를 모두 보낸 뒤 완료 마커를 보낸다', async () => {
        await service.enqueue({type: 'REPOSITORY', owner: 'my-org', repo: 'my-repo'});

        for (const name of ['label', 'issue', 'pullRequest', 'issueComment'] as const) {
            const lastSend = Math.max(...producers[name].send.mock.invocationCallOrder);
            const [markerOrder] = producers[name].sendSyncCompleted.mock.invocationCallOrder;
            expect(markerOrder).toBeGreaterThan(lastSend);
            expect(producers[name].sendSyncCompleted).toHaveBeenCalledWith(expect.objectContaining({
                recordType: 'SYNC_COMPLETED', owner: 'my-org', repo: 'my-repo',
            }));
        }

        expect(redis.eval).toHaveBeenCalled();
    });

    it('이미 대기 중인 대상은 다시 넣지 않는다', async () => {
        const target = {type: 'REPOSITORY', owner: 'my-org', repo: 'my-repo'} as const;

        await Promise.all([service.enqueue(target), service.enqueue(target)]);

        expect(labelHttpService.listLabels).toHaveBeenCalledTimes(1);
    });

    it('다른 레플리카가 같은 대상의 락을 잡고 있으면 동기화하지 않는다', async () => {
        redis.set.mockResolvedValue(null);

        await service.enqueue({type: 'REPOSITORY', owner: 'my-org', repo: 'my-repo'});

        expect(labelHttpService.listLabels).not.toHaveBeenCalled();
        expect(producers.label.sendSyncCompleted).not.toHaveBeenCalled();
    });
});
