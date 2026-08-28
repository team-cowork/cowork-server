import { ConflictException } from '@nestjs/common';
import { ProjectGithubRepoProjectionRepository } from '../repository/project-github-repo-projection.repository';
import { ProjectMemberProjectionRepository } from '../repository/project-member-projection.repository';
import { ProjectClient } from './project.client';

describe('ProjectClient', () => {
    const memberRepository = { exists: jest.fn() };
    const repoRepository = {
        findAllByProjectId: jest.fn(),
        findWebhookTargets: jest.fn(),
    };
    const client = new ProjectClient(
        memberRepository as unknown as ProjectMemberProjectionRepository,
        repoRepository as unknown as ProjectGithubRepoProjectionRepository,
    );

    beforeEach(() => {
        jest.clearAllMocks();
    });

    it('연결 저장소가 하나면 그 저장소를 선택한다', async () => {
        repoRepository.findAllByProjectId.mockResolvedValue([{
            repoId: 7,
            projectId: 5,
            teamId: 10,
            owner: 'cowork-org',
            repo: 'server',
        }]);

        await expect(client.getGithubRepoInfo(5)).resolves.toEqual({
            repoId: 7,
            teamId: 10,
            owner: 'cowork-org',
            repo: 'server',
        });
    });

    it('여러 저장소 중 하나를 임의 선택하지 않는다', async () => {
        repoRepository.findAllByProjectId.mockResolvedValue([
            { repoId: 7, projectId: 5, teamId: 10, owner: 'cowork-org', repo: 'server' },
            { repoId: 8, projectId: 5, teamId: 10, owner: 'cowork-org', repo: 'web' },
        ]);

        await expect(client.getGithubRepoInfo(5)).rejects.toBeInstanceOf(ConflictException);
    });

    it('GitHub 활동은 같은 저장소에 연결된 모든 알림 채널로 라우팅한다', async () => {
        repoRepository.findWebhookTargets.mockResolvedValue([
            { teamId: 10, projectId: 5, webhookChannelId: 3 },
            { teamId: 20, projectId: 6, webhookChannelId: 4 },
        ]);

        await expect(client.getGithubWebhookTargets('Cowork-Org', 'Server')).resolves.toEqual([
            { teamId: 10, projectId: 5, channelId: 3 },
            { teamId: 20, projectId: 6, channelId: 4 },
        ]);
        expect(repoRepository.findWebhookTargets).toHaveBeenCalledWith('Cowork-Org', 'Server');
    });

    it('프로젝트 멤버십은 로컬 projection만 조회한다', async () => {
        memberRepository.exists.mockResolvedValue(true);

        await expect(client.isMember(5, 42)).resolves.toBe(true);
        expect(memberRepository.exists).toHaveBeenCalledWith(5, 42);
    });
});
