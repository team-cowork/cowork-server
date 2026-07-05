import { Test, TestingModule } from '@nestjs/testing';
import { ConfigService } from '@nestjs/config';
import { ProjectClient, GithubRepoInfo } from './project.client';
import { ProjectMemberCache } from './project-member.cache';

type ClientWithPrivates = Omit<ProjectClient, 'parseRepoUrl'> & {
    parseRepoUrl: (url: string | null | undefined) => Omit<GithubRepoInfo, 'teamId'> | null;
};

describe('ProjectClient', () => {
    let client: ProjectClient;
    let memberCache: { get: jest.Mock; set: jest.Mock };

    beforeEach(async () => {
        memberCache = { get: jest.fn().mockResolvedValue(null), set: jest.fn().mockResolvedValue(undefined) };

        const module: TestingModule = await Test.createTestingModule({
            providers: [
                ProjectClient,
                {
                    provide: ConfigService,
                    useValue: { get: jest.fn().mockReturnValue('http://localhost:8084') },
                },
                { provide: ProjectMemberCache, useValue: memberCache },
            ],
        }).compile();

        client = module.get<ProjectClient>(ProjectClient);
    });

    describe('parseRepoUrl (private)', () => {
        const parse = (url: string | null | undefined) =>
            (client as unknown as ClientWithPrivates).parseRepoUrl(url);

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
                    teamId: 10,
                    githubRepoUrl: 'https://github.com/my-org/backend',
                }),
            });

            const result = await client.getGithubRepoInfo(1);

            expect(global.fetch).toHaveBeenCalledWith(
                'http://localhost:8084/projects/1',
                expect.objectContaining({ signal: expect.any(AbortSignal) as unknown }),
            );
            expect(result).toEqual({ teamId: 10, owner: 'my-org', repo: 'backend' });
        });

        it('githubRepoUrl이 null이면 null을 반환한다', async () => {
            (global.fetch as jest.Mock).mockResolvedValue({
                ok: true,
                json: jest.fn().mockResolvedValue({ teamId: 10, githubRepoUrl: null }),
            });

            expect(await client.getGithubRepoInfo(1)).toBeNull();
        });

        it('teamId가 없으면 null을 반환한다', async () => {
            (global.fetch as jest.Mock).mockResolvedValue({
                ok: true,
                json: jest.fn().mockResolvedValue({ githubRepoUrl: 'https://github.com/my-org/backend' }),
            });

            expect(await client.getGithubRepoInfo(1)).toBeNull();
        });

        it('404 응답이면 null을 반환한다', async () => {
            (global.fetch as jest.Mock).mockResolvedValue({ ok: false, status: 404 });

            expect(await client.getGithubRepoInfo(99)).toBeNull();
        });

        it('5xx 응답이면 예외를 던진다', async () => {
            (global.fetch as jest.Mock).mockResolvedValue({ ok: false, status: 500 });

            await expect(client.getGithubRepoInfo(1)).rejects.toThrow('프로젝트 서비스 응답 오류: 500');
        });

        it('네트워크 오류가 발생하면 예외를 던진다', async () => {
            (global.fetch as jest.Mock).mockRejectedValue(new Error('ECONNREFUSED'));

            await expect(client.getGithubRepoInfo(1)).rejects.toThrow('ECONNREFUSED');
        });
    });

    describe('isMember', () => {
        beforeEach(() => {
            global.fetch = jest.fn();
        });

        afterEach(() => {
            jest.restoreAllMocks();
        });

        it('200 응답이면 true를 반환하고 캐시에 저장한다', async () => {
            (global.fetch as jest.Mock).mockResolvedValue({ ok: true, status: 200 });

            const result = await client.isMember(5, 42);

            expect(global.fetch).toHaveBeenCalledWith(
                'http://localhost:8084/projects/5/members/me',
                expect.objectContaining({
                    headers: { 'X-User-Id': '42' },
                    signal: expect.any(AbortSignal) as unknown,
                }),
            );
            expect(result).toBe(true);
            expect(memberCache.set).toHaveBeenCalledWith(5, 42, true);
        });

        it('404 응답이면 false를 반환하고 캐시에 저장한다', async () => {
            (global.fetch as jest.Mock).mockResolvedValue({ ok: false, status: 404 });

            expect(await client.isMember(5, 99)).toBe(false);
            expect(memberCache.set).toHaveBeenCalledWith(5, 99, false);
        });

        it('5xx 응답이면 예외를 던지고 캐시에 저장하지 않는다', async () => {
            (global.fetch as jest.Mock).mockResolvedValue({ ok: false, status: 500 });

            await expect(client.isMember(5, 42)).rejects.toThrow('project-service 오류: 500');
            expect(memberCache.set).not.toHaveBeenCalled();
        });

        it('네트워크 오류가 발생하면 예외를 던지고 캐시에 저장하지 않는다', async () => {
            (global.fetch as jest.Mock).mockRejectedValue(new Error('ECONNREFUSED'));

            await expect(client.isMember(5, 42)).rejects.toThrow('ECONNREFUSED');
            expect(memberCache.set).not.toHaveBeenCalled();
        });

        it('캐시에 값이 있으면 project-service를 호출하지 않고 캐시된 값을 반환한다', async () => {
            memberCache.get.mockResolvedValue(true);

            const result = await client.isMember(5, 42);

            expect(result).toBe(true);
            expect(global.fetch).not.toHaveBeenCalled();
            expect(memberCache.set).not.toHaveBeenCalled();
        });

        it('캐시에 false가 저장되어 있으면 project-service를 호출하지 않는다', async () => {
            memberCache.get.mockResolvedValue(false);

            const result = await client.isMember(5, 42);

            expect(result).toBe(false);
            expect(global.fetch).not.toHaveBeenCalled();
        });
    });
});
