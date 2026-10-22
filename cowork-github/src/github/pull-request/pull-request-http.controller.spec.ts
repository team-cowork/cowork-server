import {Test, type TestingModule} from '@nestjs/testing';
import {HttpException} from '@nestjs/common';
import {InternalApiKeyGuard} from '../common/guards/internal-api-key.guard';
import {GithubClientError} from '../github.errors';
import {PullRequestHttpController} from './pull-request-http.controller';
import {PullRequestService} from './pull-request.service';

describe('PullRequestHttpController', () => {
    let controller: PullRequestHttpController;
    let pullRequestService: {
        getPullRequestLiveStatus: jest.Mock;
        listPullRequestFiles: jest.Mock;
        mergePullRequest: jest.Mock;
        approvePullRequest: jest.Mock;
    };

    beforeEach(async () => {
        pullRequestService = {
            getPullRequestLiveStatus: jest.fn(),
            mergePullRequest: jest.fn(),
            approvePullRequest: jest.fn(),
        };

        const module: TestingModule = await Test.createTestingModule({
            controllers: [PullRequestHttpController],
            providers: [
                {provide: PullRequestService, useValue: pullRequestService},
            ],
        })
            .overrideGuard(InternalApiKeyGuard)
            .useValue({canActivate: () => true})
            .compile();

        controller = module.get<PullRequestHttpController>(
            PullRequestHttpController,
        );
    });

    it('PR live 상태를 조회한다', async () => {
        pullRequestService.getPullRequestLiveStatus.mockResolvedValue({mergeable: true});

        const result = await controller.getLiveStatus('my-org', 'my-repo', 1);

        expect(result).toEqual({mergeable: true});
        expect(pullRequestService.getPullRequestLiveStatus).toHaveBeenCalledWith(
            'my-org',
            'my-repo',
            1,
        );
    });

    it('머지 요청을 전달한다', async () => {
        pullRequestService.mergePullRequest.mockResolvedValue({
            alreadyMerged: false,
            prUrl: 'url',
            prNumber: 1,
        });

        const result = await controller.merge('my-org', 'my-repo', 1, {
            requesterGithubUsername: 'octocat',
        });

        expect(result).toEqual({
            alreadyMerged: false,
            prUrl: 'url',
            prNumber: 1,
        });
        expect(pullRequestService.mergePullRequest).toHaveBeenCalledWith({
            owner: 'my-org',
            repo: 'my-repo',
            prNumber: 1,
            requesterGithubUsername: 'octocat',
        });
    });

    it('GithubClientError는 동일한 statusCode의 HttpException으로 변환한다', async () => {
        pullRequestService.approvePullRequest.mockRejectedValue(
            new GithubClientError('본인이 작성한 PR은 승인할 수 없습니다.', 403),
        );

        await expect(
            controller.approve('my-org', 'my-repo', 1, {
                requesterGithubUsername: 'octocat',
            }),
        ).rejects.toMatchObject({
            response: '본인이 작성한 PR은 승인할 수 없습니다.',
            status: 403,
        });
    });

    it('알 수 없는 에러는 502로 변환한다', async () => {
        pullRequestService.getPullRequestLiveStatus.mockRejectedValue(
            new Error('network down'),
        );

        let caught: unknown;
        try {
            await controller.getLiveStatus('my-org', 'my-repo', 1);
        } catch (error) {
            caught = error;
        }

        expect(caught).toBeInstanceOf(HttpException);
        expect((caught as HttpException).getStatus()).toBe(502);
    });
});
