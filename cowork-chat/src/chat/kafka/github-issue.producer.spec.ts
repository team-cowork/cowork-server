import { ConfigService } from '@nestjs/config';
import { GithubIssueProducer } from './github-issue.producer';
import { GithubIssueCreateEvent } from './event/github-issue.event';

const mockSend = jest.fn();
const mockConnect = jest.fn();
const mockDisconnect = jest.fn();

jest.mock('kafkajs', () => ({
    Kafka: jest.fn().mockImplementation(() => ({
        producer: jest.fn().mockReturnValue({
            connect: mockConnect,
            disconnect: mockDisconnect,
            send: mockSend,
        }),
    })),
}));

describe('GithubIssueProducer', () => {
    let producer: GithubIssueProducer;

    beforeEach(async () => {
        jest.clearAllMocks();
        const configService = {
            get: jest.fn().mockReturnValue('localhost:9092'),
        } as unknown as ConfigService;

        producer = new GithubIssueProducer(configService);
        await producer.onModuleInit();
    });

    afterEach(async () => {
        await producer.onModuleDestroy();
    });

    it('onModuleInit 시 Kafka producer에 연결한다', () => {
        expect(mockConnect).toHaveBeenCalledTimes(1);
    });

    it('이벤트를 github.issue.create 토픽으로 발행한다', async () => {
        mockSend.mockResolvedValue(undefined);

        const event: GithubIssueCreateEvent = {
            channelId: 5,
            teamId: 1,
            owner: 'my-org',
            repo: 'backend',
            title: '로그인 버그',
            body: '상세 내용',
            requesterId: 42,
        };

        await producer.send(event);

        expect(mockSend).toHaveBeenCalledWith({
            topic: 'github.issue.create',
            messages: [
                {
                    key: '5',
                    value: JSON.stringify(event),
                },
            ],
        });
    });

    it('channelId를 Kafka 메시지 key로 사용한다', async () => {
        mockSend.mockResolvedValue(undefined);

        await producer.send({
            channelId: 99,
            teamId: 1,
            owner: 'org',
            repo: 'repo',
            title: '제목',
            requesterId: 1,
        });

        expect(mockSend).toHaveBeenCalledWith(
            expect.objectContaining({
                messages: [expect.objectContaining({ key: '99' })],
            }),
        );
    });

    it('onModuleDestroy 시 연결을 해제한다', async () => {
        await producer.onModuleDestroy();
        expect(mockDisconnect).toHaveBeenCalledTimes(1);
    });
});
