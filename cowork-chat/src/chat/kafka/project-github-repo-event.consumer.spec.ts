import { mongo } from 'mongoose';
import { ProjectGithubRepoProjectionRepository } from '../repository/project-github-repo-projection.repository';
import { ProjectGithubRepoEventConsumer } from './project-github-repo-event.consumer';

type ConsumerWithHandle = {
    handleEvent(event: unknown, messageKey?: string): Promise<void>;
};

const SOURCE_VERSION = mongo.Long.fromString('1787702400000000000');

describe('ProjectGithubRepoEventConsumer', () => {
    const repository = {
        upsert: jest.fn().mockResolvedValue(true),
        remove: jest.fn().mockResolvedValue(true),
    };
    const consumer = new ProjectGithubRepoEventConsumer(
        { get: jest.fn() } as never,
        { sendCustom: jest.fn() } as never,
        repository as unknown as ProjectGithubRepoProjectionRepository,
        {} as never,
    );

    beforeEach(() => jest.clearAllMocks());

    const validUpsertPayload = {
        schemaVersion: 1 as const,
        eventType: 'UPSERT' as const,
        repoId: 5,
        projectId: 100,
        teamId: 10,
        githubRepoUrl: 'https://github.com/my-org/backend',
        owner: 'my-org',
        repo: 'backend',
        webhookChannelId: 7,
        occurredAt: '2026-08-26T00:00:00Z',
    };

    it('UPSERT 이벤트를 owner/repo와 함께 projection에 반영한다', async () => {
        await (consumer as unknown as ConsumerWithHandle).handleEvent(validUpsertPayload, '5');

        expect(repository.upsert).toHaveBeenCalledWith({
            repoId: 5,
            projectId: 100,
            teamId: 10,
            githubRepoUrl: 'https://github.com/my-org/backend',
            owner: 'my-org',
            repo: 'backend',
            webhookChannelId: 7,
            occurredAt: new Date('2026-08-26T00:00:00Z'),
            sourceVersion: SOURCE_VERSION,
        });
    });

    it('webhookChannelId가 null이어도 UPSERT를 반영한다', async () => {
        await (consumer as unknown as ConsumerWithHandle).handleEvent({
            ...validUpsertPayload,
            webhookChannelId: null,
        }, '5');

        expect(repository.upsert).toHaveBeenCalledWith(
            expect.objectContaining({ webhookChannelId: null }),
        );
    });

    it('DELETE 이벤트는 repository.remove만 호출한다', async () => {
        await (consumer as unknown as ConsumerWithHandle).handleEvent({
            schemaVersion: 1,
            eventType: 'DELETE',
            repoId: 5,
            projectId: 100,
            teamId: 10,
            githubRepoUrl: null,
            owner: null,
            repo: null,
            webhookChannelId: null,
            occurredAt: '2026-08-26T00:00:00Z',
        }, '5');

        expect(repository.remove).toHaveBeenCalledWith(5, new Date('2026-08-26T00:00:00Z'), SOURCE_VERSION);
        expect(repository.upsert).not.toHaveBeenCalled();
    });

    it.each([undefined, 'legacy-repo-key'])(
        'compacted topic key가 없거나 repoId와 다르면 projection을 변경하지 않는다 (key=%s)',
        async (messageKey) => {
            await expect(
                (consumer as unknown as ConsumerWithHandle).handleEvent(validUpsertPayload, messageKey),
            ).rejects.toThrow('project GitHub repo event key mismatch');

            expect(repository.upsert).not.toHaveBeenCalled();
            expect(repository.remove).not.toHaveBeenCalled();
        },
    );

    it.each([
        ['schemaVersion이 1이 아님', { ...validUpsertPayload, schemaVersion: 2 }],
        ['eventType이 알 수 없는 값', { ...validUpsertPayload, eventType: 'UNKNOWN' }],
        ['repoId가 양의 정수가 아님', { ...validUpsertPayload, repoId: -1 }],
        ['projectId가 양의 정수가 아님', { ...validUpsertPayload, projectId: 0 }],
        ['teamId가 양의 정수가 아님', { ...validUpsertPayload, teamId: 1.5 }],
        ['webhookChannelId가 null도 양의 정수도 아님', { ...validUpsertPayload, webhookChannelId: -3 }],
        ['occurredAt이 RFC3339 형식이 아님', { ...validUpsertPayload, occurredAt: 'not-a-date' }],
        ['UPSERT인데 githubRepoUrl이 문자열이 아님', { ...validUpsertPayload, githubRepoUrl: null }],
        ['owner에 대문자가 섞여 있음', { ...validUpsertPayload, owner: 'My-Org' }],
        ['repo에 대문자가 섞여 있음', { ...validUpsertPayload, repo: 'Backend' }],
        ['owner가 빈 문자열', { ...validUpsertPayload, owner: '' }],
        ['repo가 빈 문자열', { ...validUpsertPayload, repo: '' }],
        ['payload가 객체가 아님', 'not-an-object'],
        ['payload가 null', null],
    ])('잘못된 payload(%s)이면 계약 오류를 던진다', async (_label, payload) => {
        await expect(
            (consumer as unknown as ConsumerWithHandle).handleEvent(payload, '5'),
        ).rejects.toThrow('invalid project GitHub repo event payload');

        expect(repository.upsert).not.toHaveBeenCalled();
        expect(repository.remove).not.toHaveBeenCalled();
    });
});
