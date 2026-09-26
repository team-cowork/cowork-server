import { Test, TestingModule } from '@nestjs/testing';
import { Kafka } from 'kafkajs';
import { AppConfigService } from '../../config/app-config.service';
import { IssueWriteResultEvent } from './event/issue-write-result.event';
import { IssueWriteCommandType } from './issue-write-command-type';
import { IssueWriteResultProducer } from './issue-write-result.producer';

jest.mock('kafkajs');

describe('IssueWriteResultProducer', () => {
  let producer: IssueWriteResultProducer;
  let mockSend: jest.Mock;
  let mockConnect: jest.Mock;
  let mockDisconnect: jest.Mock;

  beforeEach(async () => {
    mockSend = jest.fn().mockResolvedValue(undefined);
    mockConnect = jest.fn().mockResolvedValue(undefined);
    mockDisconnect = jest.fn().mockResolvedValue(undefined);

    (Kafka as unknown as jest.Mock).mockImplementation(() => ({
      producer: () => ({
        connect: mockConnect,
        disconnect: mockDisconnect,
        send: mockSend,
      }),
    }));

    const module: TestingModule = await Test.createTestingModule({
      providers: [
        IssueWriteResultProducer,
        {
          provide: AppConfigService,
          useValue: { kafkaBrokers: ['localhost:9092'] },
        },
      ],
    }).compile();

    producer = module.get<IssueWriteResultProducer>(IssueWriteResultProducer);
    await producer.onModuleInit();
  });

  it('연결 시 kafka producer.connect를 호출한다', () => {
    expect(mockConnect).toHaveBeenCalledTimes(1);
  });

  it('github-app.issue-write.result 토픽에 operationId를 key로 발행한다', async () => {
    const event: IssueWriteResultEvent = {
      schemaVersion: 1,
      operationId: 'op-1',
      idempotencyKey: 'idem-1',
      commandType: IssueWriteCommandType.CREATE_COMMENT,
      status: 'SUCCEEDED',
      result: { id: 100, author: 'octocat', body: 'hi' },
      occurredAt: '2026-09-26T00:00:00.000Z',
    };

    await producer.send(event);

    expect(mockSend).toHaveBeenCalledWith({
      topic: 'github-app.issue-write.result',
      messages: [{ key: 'op-1', value: JSON.stringify(event) }],
    });
  });

  it('모듈 종료 시 producer.disconnect를 호출한다', async () => {
    await producer.onModuleDestroy();

    expect(mockDisconnect).toHaveBeenCalledTimes(1);
  });
});
