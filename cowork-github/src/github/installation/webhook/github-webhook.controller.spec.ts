import {Test, type TestingModule} from '@nestjs/testing';
import {REDIS_CLIENT} from '../../constants';
import {PullRequestService} from '../../pull-request/pull-request.service';
import {GithubReadModelEventProducers} from '../kafka/github-read-model-event-producers';
import {TeamGithubProducer} from '../kafka/team-github.producer';
import {RepoEventProducer} from '../kafka/repo-event.producer';
import {GithubWebhookController} from './github-webhook.controller';
import {GithubWebhookSignatureGuard} from './github-webhook-signature.guard';

describe('GithubWebhookController', () => {
    let controller: GithubWebhookController;
    let producer: {send: jest.Mock};
    let repoEventProducer: {send: jest.Mock};
    let readModelProducers: {
        repo: {send: jest.Mock};
        issue: {send: jest.Mock};
        pullRequest: {send: jest.Mock};
        label: {send: jest.Mock};
        issueComment: {send: jest.Mock};
    };
    let pullRequestService: {listPullRequestFiles: jest.Mock};
    let redis: {incr: jest.Mock; get: jest.Mock; set: jest.Mock};

    beforeEach(async () => {
        producer = {send: jest.fn()};
        repoEventProducer = {send: jest.fn()};
        readModelProducers = {
            repo: {send: jest.fn()},
            issue: {send: jest.fn()},
            pullRequest: {send: jest.fn()},
            label: {send: jest.fn()},
            issueComment: {send: jest.fn()},
        };
        pullRequestService = {listPullRequestFiles: jest.fn().mockResolvedValue([])};
        redis = {
            incr: jest.fn().mockResolvedValue(5),
            get: jest.fn().mockResolvedValue(null),
            set: jest.fn().mockResolvedValue('OK'),
        };

        const module: TestingModule = await Test.createTestingModule({
            controllers: [GithubWebhookController],
            providers: [
                {provide: TeamGithubProducer, useValue: producer},
                {provide: RepoEventProducer, useValue: repoEventProducer},
                {provide: GithubReadModelEventProducers, useValue: readModelProducers},
                {provide: PullRequestService, useValue: pullRequestService},
                {provide: REDIS_CLIENT, useValue: redis},
            ],
        })
            .overrideGuard(GithubWebhookSignatureGuard)
            .useValue({canActivate: () => true})
            .compile();

        controller = module.get<GithubWebhookController>(GithubWebhookController);
    });

    it('installation.deleted 이벤트는 team.github.disconnected를 발행한다', async () => {
        const result = await controller.handle('installation', {
            action: 'deleted',
            installation: {id: 42},
        });

        expect(result).toEqual({ok: true});
        expect(redis.incr).toHaveBeenCalledWith('github:installation:revision:42');
        expect(producer.send).toHaveBeenCalledWith('team.github.disconnected', {
            installationId: 42,
            revision: 5,
        });
    });

    it('installation.created 이벤트는 무시한다', async () => {
        await controller.handle('installation', {
            action: 'created',
            installation: {id: 42},
        });

        expect(producer.send).not.toHaveBeenCalled();
    });

    it('installation.suspend 이벤트는 연동을 끊지 않는다', async () => {
        await controller.handle('installation', {
            action: 'suspend',
            installation: {id: 42},
        });

        expect(producer.send).not.toHaveBeenCalled();
    });

    it('push 이벤트는 github.repo.event를 발행한다', async () => {
        await controller.handle('push', {
            ref: 'refs/heads/main',
            pusher: {name: 'octocat'},
            commits: [{}, {}],
            repository: {full_name: 'my-org/my-repo'},
        });

        expect(repoEventProducer.send).toHaveBeenCalledWith({
            owner: 'my-org',
            repo: 'my-repo',
            eventType: 'push',
            action: 'pushed',
            summary: '📦 octocat님이 main에 커밋 2개를 푸시했습니다.',
        });
    });

    it('pull_request 이벤트는 github.repo.event와 PR state를 둘 다 발행한다', async () => {
        await controller.handle('pull_request', {
            action: 'opened',
            repository: {full_name: 'my-org/my-repo'},
            pull_request: {
                number: 7,
                title: 'Add feature',
                html_url: 'https://github.com/my-org/my-repo/pull/7',
                state: 'open',
                draft: false,
                merged: false,
                user: {login: 'octocat'},
                labels: [{name: 'bug'}],
                head: {ref: 'feature'},
                base: {ref: 'main'},
                created_at: '2026-01-01T00:00:00Z',
                updated_at: '2026-01-02T00:00:00Z',
            },
        });

        expect(repoEventProducer.send).toHaveBeenCalledWith({
            owner: 'my-org',
            repo: 'my-repo',
            eventType: 'pull_request',
            action: 'opened',
            summary:
        '🔀 PR #7 opened: Add feature (https://github.com/my-org/my-repo/pull/7)',
        });
        expect(pullRequestService.listPullRequestFiles).toHaveBeenCalledWith('my-org', 'my-repo', 7);
        expect(readModelProducers.pullRequest.send).toHaveBeenCalledWith(expect.objectContaining({
            owner: 'my-org',
            repo: 'my-repo',
            number: 7,
            title: 'Add feature',
            author: 'octocat',
            labels: ['bug'],
            headRef: 'feature',
            baseRef: 'main',
            files: [],
        }));
    });

    it('PR state 발행이 실패하면 예외가 전파된다(재시도 대상이 되도록)', async () => {
        pullRequestService.listPullRequestFiles.mockRejectedValue(new Error('rate limited'));

        await expect(controller.handle('pull_request', {
            action: 'synchronize',
            repository: {full_name: 'my-org/my-repo'},
            pull_request: {number: 7, user: {login: 'octocat'}},
        })).rejects.toThrow('rate limited');
    });

    it('알림(github.repo.event) 발행이 실패해도 ok를 반환하고 PR state는 발행된다', async () => {
        repoEventProducer.send.mockRejectedValue(new Error('kafka down'));

        const result = await controller.handle('pull_request', {
            action: 'synchronize',
            repository: {full_name: 'my-org/my-repo'},
            pull_request: {number: 7, user: {login: 'octocat'}},
        });

        expect(result).toEqual({ok: true});
        expect(readModelProducers.pullRequest.send).toHaveBeenCalled();
    });

    it('pull_request의 labeled 액션에서는 파일 목록을 다시 조회하지 않고, 캐시도 없으면 files를 생략한다', async () => {
        await controller.handle('pull_request', {
            action: 'labeled',
            repository: {full_name: 'my-org/my-repo'},
            pull_request: {number: 7, user: {login: 'octocat'}},
        });

        expect(pullRequestService.listPullRequestFiles).not.toHaveBeenCalled();
        const [sentEvent] = readModelProducers.pullRequest.send.mock.calls[0] as [Record<string, unknown>];
        expect(sentEvent.files).toBeUndefined();
    });

    it('pull_request의 labeled 액션에서는 캐시된 파일 목록을 그대로 실어 보낸다(compaction 안전)', async () => {
        const cachedFiles = [{
            filename: 'a.kt', status: 'modified', additions: 1, deletions: 0, patch: null,
        }];
        redis.get.mockResolvedValue(JSON.stringify(cachedFiles));

        await controller.handle('pull_request', {
            action: 'labeled',
            repository: {full_name: 'my-org/my-repo'},
            pull_request: {number: 7, user: {login: 'octocat'}},
        });

        expect(redis.get).toHaveBeenCalledWith('github:pr-files:my-org/my-repo#7');
        const [sentEvent] = readModelProducers.pullRequest.send.mock.calls[0] as [Record<string, unknown>];
        expect(sentEvent.files).toEqual(cachedFiles);
    });

    it('base 브랜치가 바뀌면 synchronize가 아니어도 파일 목록을 다시 조회하고 캐시에 저장한다', async () => {
        pullRequestService.listPullRequestFiles.mockResolvedValue([
            {
                filename: 'b.kt', status: 'added', additions: 5, deletions: 0, patch: null,
            },
        ]);

        await controller.handle('pull_request', {
            action: 'edited',
            repository: {full_name: 'my-org/my-repo'},
            pull_request: {number: 7, user: {login: 'octocat'}},
            changes: {base: {ref: {from: 'develop'}}},
        });

        expect(pullRequestService.listPullRequestFiles).toHaveBeenCalledWith('my-org', 'my-repo', 7);
        expect(redis.set).toHaveBeenCalledWith(
            'github:pr-files:my-org/my-repo#7',
            JSON.stringify([{
                filename: 'b.kt', status: 'added', additions: 5, deletions: 0, patch: null,
            }]),
        );
    });

    it('issues 이벤트는 이슈 state를 발행한다', async () => {
        await controller.handle('issues', {
            action: 'edited',
            repository: {full_name: 'my-org/my-repo'},
            issue: {
                number: 3,
                title: '버그 리포트',
                html_url: 'https://github.com/my-org/my-repo/issues/3',
                state: 'open',
                user: {login: 'octocat'},
                labels: [{name: 'bug'}],
                created_at: '2026-01-01T00:00:00Z',
                updated_at: '2026-01-02T00:00:00Z',
            },
        });

        expect(readModelProducers.issue.send).toHaveBeenCalledWith({
            owner: 'my-org',
            repo: 'my-repo',
            number: 3,
            title: '버그 리포트',
            author: 'octocat',
            state: 'open',
            htmlUrl: 'https://github.com/my-org/my-repo/issues/3',
            labels: ['bug'],
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-02T00:00:00Z',
            deleted: false,
            sourceOccurredAt: '2026-01-02T00:00:00Z',
        });
    });

    it('installation_repositories 이벤트는 추가된 repo를 발행하고 제거된 repo를 삭제 처리한다', async () => {
        await controller.handle('installation_repositories', {
            action: 'added',
            installation: {id: 42},
            repositories_added: [{full_name: 'my-org/new-repo', private: true}],
            repositories_removed: [{full_name: 'my-org/old-repo', private: false}],
        });

        expect(readModelProducers.repo.send).toHaveBeenCalledWith(expect.objectContaining({
            installationId: 42,
            owner: 'my-org',
            repo: 'new-repo',
            private: true,
            htmlUrl: 'https://github.com/my-org/new-repo',
            deleted: false,
        }));
        expect(readModelProducers.repo.send).toHaveBeenCalledWith(expect.objectContaining({
            repo: 'old-repo',
            deleted: true,
        }));
    });

    it('repository 이름이 바뀌면 이전 이름을 삭제 처리하고 새 이름을 발행한다', async () => {
        await controller.handle('repository', {
            action: 'renamed',
            installation: {id: 42},
            repository: {full_name: 'my-org/new-name', private: false},
            changes: {repository: {name: {from: 'old-name'}}},
        });

        expect(readModelProducers.repo.send).toHaveBeenCalledWith(expect.objectContaining({
            fullName: 'my-org/old-name',
            deleted: true,
        }));
        expect(readModelProducers.repo.send).toHaveBeenCalledWith(expect.objectContaining({
            fullName: 'my-org/new-name',
            deleted: false,
        }));
    });

    it('repository 삭제 이벤트는 해당 repo만 삭제 처리한다', async () => {
        await controller.handle('repository', {
            action: 'deleted',
            installation: {id: 42},
            repository: {full_name: 'my-org/my-repo', private: false},
        });

        expect(readModelProducers.repo.send).toHaveBeenCalledTimes(1);
        expect(readModelProducers.repo.send).toHaveBeenCalledWith(expect.objectContaining({
            fullName: 'my-org/my-repo',
            deleted: true,
        }));
    });

    it('label 이벤트는 라벨 state를 발행한다', async () => {
        await controller.handle('label', {
            action: 'created',
            repository: {full_name: 'my-org/my-repo'},
            label: {name: 'bug', color: 'd73a4a'},
        });

        expect(readModelProducers.label.send).toHaveBeenCalledWith(expect.objectContaining({
            owner: 'my-org',
            repo: 'my-repo',
            name: 'bug',
            color: 'd73a4a',
            deleted: false,
        }));
    });

    it('label 이름이 바뀌면 이전 이름을 삭제 처리하고 새 이름을 발행한다', async () => {
        await controller.handle('label', {
            action: 'edited',
            repository: {full_name: 'my-org/my-repo'},
            label: {name: 'defect', color: 'd73a4a'},
            changes: {name: {from: 'bug'}},
        });

        expect(readModelProducers.label.send).toHaveBeenCalledWith(expect.objectContaining({
            name: 'bug',
            deleted: true,
        }));
        expect(readModelProducers.label.send).toHaveBeenCalledWith(expect.objectContaining({
            name: 'defect',
            deleted: false,
        }));
    });

    it('issue_comment 이벤트는 숨은 마커에서 실제 작성자를 복원해 댓글 state를 발행한다', async () => {
        await controller.handle('issue_comment', {
            action: 'created',
            repository: {full_name: 'my-org/my-repo'},
            issue: {number: 3},
            comment: {
                id: 99,
                body: '확인했습니다\n\n<!-- cowork:author=real-author -->',
                html_url: 'https://github.com/my-org/my-repo/issues/3#issuecomment-99',
                user: {login: 'cowork-bot'},
                created_at: '2026-01-01T00:00:00Z',
                updated_at: '2026-01-01T00:00:00Z',
            },
        });

        expect(readModelProducers.issueComment.send).toHaveBeenCalledWith(expect.objectContaining({
            owner: 'my-org',
            repo: 'my-repo',
            commentId: 99,
            parentType: 'ISSUE',
            parentNumber: 3,
            author: 'real-author',
            body: '확인했습니다',
        }));
    });

    it('repository 정보가 없으면 이벤트를 발행하지 않는다', async () => {
        await controller.handle('push', {ref: 'refs/heads/main'});

        expect(repoEventProducer.send).not.toHaveBeenCalled();
    });

    it('알 수 없는 이벤트는 무시하고 ok를 반환한다', async () => {
        const result = await controller.handle('star', {});

        expect(result).toEqual({ok: true});
        expect(producer.send).not.toHaveBeenCalled();
        expect(repoEventProducer.send).not.toHaveBeenCalled();
    });
});
