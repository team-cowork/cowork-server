import { Test, TestingModule } from '@nestjs/testing';
import { ConfigService } from '@nestjs/config';
import { DicoshotService } from 'dicoshot-nest';
import { ProjectMemberEventConsumer } from './project-member-event.consumer';
import { ProjectMemberCache } from '../service/project-member.cache';

type ConsumerWithPrivates = Omit<ProjectMemberEventConsumer, 'handleEvent'> & {
    handleEvent: (event: unknown) => Promise<void>;
};

const mockInvalidate = jest.fn().mockResolvedValue(undefined);

const mockProjectMemberCache = {
    invalidate: mockInvalidate,
};

describe('ProjectMemberEventConsumer', () => {
    let consumer: ProjectMemberEventConsumer;

    beforeEach(async () => {
        const module: TestingModule = await Test.createTestingModule({
            providers: [
                ProjectMemberEventConsumer,
                {
                    provide: ProjectMemberCache,
                    useValue: mockProjectMemberCache,
                },
                {
                    provide: ConfigService,
                    useValue: { get: jest.fn().mockReturnValue('localhost:9092') },
                },
                {
                    provide: DicoshotService,
                    useValue: { sendCustom: jest.fn().mockResolvedValue(true) },
                },
            ],
        }).compile();

        consumer = module.get<ProjectMemberEventConsumer>(ProjectMemberEventConsumer);
        jest.clearAllMocks();
    });

    const callHandleEvent = (event: unknown) => (consumer as unknown as ConsumerWithPrivates).handleEvent(event);

    describe('유효한 payload', () => {
        it('캐시를 무효화한다', async () => {
            await callHandleEvent({ eventType: 'ADDED', projectId: 5, userId: 42 });

            expect(mockInvalidate).toHaveBeenCalledWith(5, 42);
        });
    });

    describe('잘못된 payload', () => {
        it('projectId가 숫자가 아니면 캐시를 무효화하지 않는다', async () => {
            await callHandleEvent({ eventType: 'ADDED', projectId: '5', userId: 42 });

            expect(mockInvalidate).not.toHaveBeenCalled();
        });

        it('eventType이 없으면 캐시를 무효화하지 않는다', async () => {
            await callHandleEvent({ projectId: 5, userId: 42 });

            expect(mockInvalidate).not.toHaveBeenCalled();
        });
    });
});
