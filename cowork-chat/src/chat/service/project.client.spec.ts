import { Test, TestingModule } from '@nestjs/testing';
import { ConfigService } from '@nestjs/config';
import { ProjectClient } from './project.client';

describe('ProjectClient', () => {
    let client: ProjectClient;

    beforeEach(async () => {
        const module: TestingModule = await Test.createTestingModule({
            providers: [
                ProjectClient,
                {
                    provide: ConfigService,
                    useValue: { get: jest.fn().mockReturnValue('http://localhost:8084') },
                },
            ],
        }).compile();

        client = module.get<ProjectClient>(ProjectClient);
    });

    describe('parseRepoUrl (private)', () => {
        const parse = (url: string | null | undefined) =>
            (client as any).parseRepoUrl(url);

        it('표준 GitHub URL에서 owner와 repo를 파싱한다', () => {
            expect(parse('https://github.com/my-org/backend')).toEqual({
                owner: 'my-org',
                repo: 'backend',
            });
        });

        it('.git 접미사를 제거한다', () => {
            expect(parse('https://github.com/my-org/backend.git')).toEqual({
                owner: 'my-org',
                repo: 'backend',
            });
        });

        it('하위 경로가 있어도 owner/repo만 추출한다', () => {
            expect(parse('https://github.com/my-org/backend/tree/main')).toEqual({
                owner: 'my-org',
                repo: 'backend',
            });
        });

        it('null이면 null을 반환한다', () => {
            expect(parse(null)).toBeNull();
        });

        it('undefined이면 null을 반환한다', () => {
            expect(parse(undefined)).toBeNull();
        });

        it('빈 문자열이면 null을 반환한다', () => {
            expect(parse('')).toBeNull();
        });

        it('GitHub URL이 아니면 null을 반환한다', () => {
            expect(parse('https://gitlab.com/my-org/backend')).toBeNull();
        });

        it('형식이 잘못된 URL이면 null을 반환한다', () => {
            expect(parse('not-a-url')).toBeNull();
        });
    });

    describe('getGithubRepoInfo', () => {
        beforeEach(() => {
            global.fetch = jest.fn();
        });

        afterEach(() => {
            jest.restoreAllMocks();
        });

        it('프로젝트 서비스 응답에서 레포 정보를 파싱해 반환한다', async () => {
            (global.fetch as jest.Mock).mockResolvedValue({
                ok: true,
                json: jest.fn().mockResolvedValue({
                    githubRepoUrl: 'https://github.com/my-org/backend',
                }),
            });

            const result = await client.getGithubRepoInfo(1);

            expect(global.fetch).toHaveBeenCalledWith('http://localhost:8084/projects/1');
            expect(result).toEqual({ owner: 'my-org', repo: 'backend' });
        });

        it('githubRepoUrl이 null이면 null을 반환한다', async () => {
            (global.fetch as jest.Mock).mockResolvedValue({
                ok: true,
                json: jest.fn().mockResolvedValue({ githubRepoUrl: null }),
            });

            expect(await client.getGithubRepoInfo(1)).toBeNull();
        });

        it('HTTP 오류 응답이면 null을 반환한다', async () => {
            (global.fetch as jest.Mock).mockResolvedValue({ ok: false, status: 404 });

            expect(await client.getGithubRepoInfo(99)).toBeNull();
        });

        it('네트워크 오류가 발생하면 null을 반환한다', async () => {
            (global.fetch as jest.Mock).mockRejectedValue(new Error('ECONNREFUSED'));

            expect(await client.getGithubRepoInfo(1)).toBeNull();
        });
    });
});
