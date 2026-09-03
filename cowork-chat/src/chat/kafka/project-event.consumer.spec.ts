import { mongo } from 'mongoose';
import { ProjectMemberProjectionRepository } from '../repository/project-member-projection.repository';
import { ProjectProjectionRepository } from '../repository/project-projection.repository';
import { ProjectEventConsumer } from './project-event.consumer';
import { ChannelMessageReadAccessService } from '../service/channel-message-read-access.service';

type ConsumerWithHandle = {
    handleEvent(event: unknown, messageKey?: string): Promise<void>;
};

const SOURCE_VERSION = mongo.Long.fromString('1787702400000000000');

describe('ProjectEventConsumer', () => {
    const memberRepository = { removeByProjectId: jest.fn().mockResolvedValue(undefined) };
    const projectRepository = {
        upsert: jest.fn().mockResolvedValue(true),
        remove: jest.fn().mockResolvedValue(true),
    };
    const accessService = { emitToActiveTeamUsers: jest.fn().mockResolvedValue(undefined) };
    const consumer = new ProjectEventConsumer(
        { get: jest.fn() } as never,
        { sendCustom: jest.fn() } as never,
        projectRepository as unknown as ProjectProjectionRepository,
        memberRepository as unknown as ProjectMemberProjectionRepository,
        {} as never,
        accessService as unknown as ChannelMessageReadAccessService,
    );
    const socket = { to: jest.fn().mockReturnValue({ emit: jest.fn() }) };
    consumer.setSocketServer(socket as never);

    beforeEach(() => jest.clearAllMocks());

    it('프로젝트 삭제 시 모든 멤버십 projection을 정리한다', async () => {
        await (consumer as unknown as ConsumerWithHandle).handleEvent({
            eventType: 'DELETED',
            projectId: 5,
            teamId: 10,
            name: 'project',
            description: null,
            status: 'DELETED',
            occurredAt: '2026-08-26T00:00:00',
        }, '5');

        expect(projectRepository.remove).toHaveBeenCalledWith(
            5,
            new Date('2026-08-26T00:00:00Z'),
            SOURCE_VERSION,
        );
        expect(memberRepository.removeByProjectId).toHaveBeenCalledWith(
            5,
            new Date('2026-08-26T00:00:00Z'),
            SOURCE_VERSION,
        );
        expect(accessService.emitToActiveTeamUsers).toHaveBeenCalledWith(
            socket,
            10,
            'project:deleted',
            { projectId: 5, teamId: 10 },
        );
    });

    it('이미 반영된 UPDATED 이벤트면 부수효과를 다시 실행하지 않는다', async () => {
        projectRepository.upsert.mockResolvedValueOnce(false);

        await (consumer as unknown as ConsumerWithHandle).handleEvent({
            eventType: 'UPDATED',
            projectId: 5,
            teamId: 10,
            name: 'project',
            description: null,
            status: 'ACTIVE',
            occurredAt: '2026-08-26T00:00:00Z',
        }, '5');

        expect(memberRepository.removeByProjectId).not.toHaveBeenCalled();
        expect(socket.to).not.toHaveBeenCalled();
    });

    it('snapshot=true이면 projection은 갱신하지만 socket 변경 알림은 발생시키지 않는다', async () => {
        await (consumer as unknown as ConsumerWithHandle).handleEvent({
            eventType: 'UPDATED',
            projectId: 5,
            teamId: 10,
            name: 'project',
            description: null,
            status: 'ACTIVE',
            position: 1,
            occurredAt: '2026-08-26T00:00:00Z',
            snapshot: true,
        }, '5');

        expect(projectRepository.upsert).toHaveBeenCalled();
        expect(accessService.emitToActiveTeamUsers).not.toHaveBeenCalled();
        expect(socket.to).not.toHaveBeenCalled();
    });

    it.each([undefined, 'legacy-project-key'])(
        'compacted topic key가 없거나 projectId와 다르면 projection을 변경하지 않는다 (key=%s)',
        async (messageKey) => {
            await expect((consumer as unknown as ConsumerWithHandle).handleEvent({
                eventType: 'UPDATED',
                projectId: 5,
                teamId: 10,
                name: 'project',
                description: null,
                status: 'ACTIVE',
                occurredAt: '2026-08-26T00:00:00Z',
            }, messageKey)).rejects.toThrow('project event key does not match projectId');

            expect(projectRepository.upsert).not.toHaveBeenCalled();
            expect(projectRepository.remove).not.toHaveBeenCalled();
            expect(socket.to).not.toHaveBeenCalled();
        },
    );
});
