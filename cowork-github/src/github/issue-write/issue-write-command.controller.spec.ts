import {Test, type TestingModule} from '@nestjs/testing';
import {type KafkaContext} from '@nestjs/microservices';
import {GithubClientError} from '../github.errors';
import {IssueHttpService} from '../issue-http/issue-http.service';
import {LabelHttpService} from '../label-http/label-http.service';
import {REDIS_CLIENT} from '../constants';
import {CreateCommentHistory} from './create-comment-history';
import {IssueWriteCommandController} from './issue-write-command.controller';
import {IssueWriteResultProducer} from './issue-write-result.producer';

describe('IssueWriteCommandController', () => {
    let controller: IssueWriteCommandController;
    let issueHttpService: {
        createComment: jest.Mock;
        findCommentByOperation: jest.Mock;
        updateComment: jest.Mock;
        deleteComment: jest.Mock;
    };
    let redisStore: Map<string, string>;
    let labelHttpService: {replaceLabels: jest.Mock};
    let resultProducer: {send: jest.Mock};
    let commitOffsets: jest.Mock;
    let ctx: KafkaContext;

    const operationId = '11111111-1111-4111-8111-111111111111';

    const baseEnvelope = {
        schemaVersion: 1,
        operationId,
        idempotencyKey: 'idem-key-1',
        owner: 'my-org',
        repo: 'my-repo',
        requestedBy: 1,
        occurredAt: '2026-09-26T00:00:00.000Z',
    };

    beforeEach(async () => {
        issueHttpService = {
            createComment: jest.fn(),
            findCommentByOperation: jest.fn(),
            updateComment: jest.fn(),
            deleteComment: jest.fn(),
        };
        redisStore = new Map();
        const redis = {
            set: jest.fn(async (key: string, value: string, ...options: unknown[]) => {
                if (options.includes('NX') && redisStore.has(key)) {
                    return null;
                }

                redisStore.set(key, value);
                return 'OK';
            }),
            get: jest.fn(async (key: string) => redisStore.get(key) ?? null),
        };
        labelHttpService = {replaceLabels: jest.fn()};
        resultProducer = {send: jest.fn().mockResolvedValue(undefined)};
        commitOffsets = jest.fn().mockResolvedValue(undefined);
        ctx = {
            getMessage: jest.fn().mockReturnValue({offset: '10'}),
            getConsumer: jest.fn().mockReturnValue({commitOffsets}),
            getTopic: jest.fn().mockReturnValue('github.issue-write.command.v1'),
            getPartition: jest.fn().mockReturnValue(0),
        } as unknown as KafkaContext;

        const module: TestingModule = await Test.createTestingModule({
            controllers: [IssueWriteCommandController],
            providers: [
                {provide: IssueHttpService, useValue: issueHttpService},
                {provide: LabelHttpService, useValue: labelHttpService},
                {provide: IssueWriteResultProducer, useValue: resultProducer},
                {provide: REDIS_CLIENT, useValue: redis},
                CreateCommentHistory,
            ],
        }).compile();

        controller = module.get<IssueWriteCommandController>(
            IssueWriteCommandController,
        );
        (controller as unknown as {exitProcess: jest.Mock}).exitProcess =
            jest.fn();
    });

    describe('REPLACE_LABELS', () => {
        it('라벨 교체 성공 시 SUCCEEDED 결과를 보내고 오프셋을 커밋한다', async () => {
            labelHttpService.replaceLabels.mockResolvedValue([
                {name: 'bug', color: 'd73a4a'},
            ]);

            const message = {
                ...baseEnvelope,
                commandType: 'REPLACE_LABELS',
                payload: {issueNumber: 1, labels: ['bug']},
            };

            await controller.handleCommand(message, ctx);

            expect(labelHttpService.replaceLabels).toHaveBeenCalledWith(
                'my-org',
                'my-repo',
                1,
                ['bug'],
            );
            expect(resultProducer.send).toHaveBeenCalledWith(
                expect.objectContaining({
                    operationId,
                    commandType: 'REPLACE_LABELS',
                    status: 'SUCCEEDED',
                    result: {labels: [{name: 'bug', color: 'd73a4a'}]},
                }),
            );
            expect(commitOffsets).toHaveBeenCalledWith([
                {
                    topic: 'github.issue-write.command.v1',
                    partition: 0,
                    offset: '11',
                },
            ]);
        });
    });

    describe('CREATE_COMMENT', () => {
        const message = {
            ...baseEnvelope,
            commandType: 'CREATE_COMMENT',
            payload: {
                issueNumber: 1,
                body: 'hello',
                requesterGithubUsername: 'octocat',
            },
        };

        it('댓글 생성 성공 시 SUCCEEDED 결과를 보낸다', async () => {
            issueHttpService.createComment.mockResolvedValue({
                id: 100,
                author: 'octocat',
                body: 'hello',
                htmlUrl: 'https://github.com/my-org/my-repo/issues/1#issuecomment-100',
                createdAt: '2026-09-26T00:00:00.000Z',
                updatedAt: '2026-09-26T00:00:00.000Z',
            });

            await controller.handleCommand(message, ctx);

            expect(issueHttpService.createComment).toHaveBeenCalledWith({
                owner: 'my-org',
                repo: 'my-repo',
                issueNumber: 1,
                body: 'hello',
                requesterGithubUsername: 'octocat',
                operationId,
            });
            expect(resultProducer.send).toHaveBeenCalledWith(
                expect.objectContaining({
                    status: 'SUCCEEDED',
                    commandType: 'CREATE_COMMENT',
                    result: expect.objectContaining({id: 100}) as unknown,
                }),
            );
            expect(commitOffsets).toHaveBeenCalledTimes(1);
        });

        it('GithubClientError 발생 시 FAILED 결과를 보내고 오프셋을 커밋한다', async () => {
            issueHttpService.createComment.mockRejectedValue(
                new GithubClientError('이슈를 찾을 수 없습니다.', 404),
            );

            await controller.handleCommand(message, ctx);

            expect(resultProducer.send).toHaveBeenCalledWith(
                expect.objectContaining({
                    status: 'FAILED',
                    error: {code: '404', message: '이슈를 찾을 수 없습니다.'},
                }),
            );
            expect(commitOffsets).toHaveBeenCalledTimes(1);
        });

        it('5xx 에러 발생 시 프로세스를 종료하고 오프셋을 커밋하지 않는다', async () => {
            issueHttpService.createComment.mockRejectedValue(new Error('GitHub 503'));

            await expect(controller.handleCommand(message, ctx)).rejects.toThrow(
                'GitHub 503',
            );

            expect(commitOffsets).not.toHaveBeenCalled();
            expect(
                (controller as unknown as {exitProcess: jest.Mock}).exitProcess,
            ).toHaveBeenCalledWith(1);
        });

        describe('재처리', () => {
            const comment = {
                id: 100,
                author: 'octocat',
                body: 'hello',
                htmlUrl: 'https://github.com/my-org/my-repo/issues/1#issuecomment-100',
                createdAt: '2026-09-26T00:00:00.000Z',
                updatedAt: '2026-09-26T00:00:00.000Z',
            };

            it('댓글 생성 뒤 결과 발행이 실패하면 재전달에서 댓글을 다시 만들지 않고 결과만 다시 보낸다', async () => {
                issueHttpService.createComment.mockResolvedValue(comment);
                resultProducer.send.mockRejectedValueOnce(new Error('Kafka unavailable'));

                await expect(controller.handleCommand(message, ctx)).rejects.toThrow('Kafka unavailable');
                await controller.handleCommand(message, ctx);

                expect(issueHttpService.createComment).toHaveBeenCalledTimes(1);
                expect(resultProducer.send).toHaveBeenCalledTimes(2);
                expect(resultProducer.send).toHaveBeenLastCalledWith(
                    expect.objectContaining({status: 'SUCCEEDED', result: comment}),
                );
                expect(commitOffsets).toHaveBeenCalledTimes(1);
            });

            it('결과 발행까지 끝난 명령이 다시 오면 댓글 생성과 결과 발행 없이 오프셋만 커밋한다', async () => {
                issueHttpService.createComment.mockResolvedValue(comment);

                await controller.handleCommand(message, ctx);
                await controller.handleCommand(message, ctx);

                expect(issueHttpService.createComment).toHaveBeenCalledTimes(1);
                expect(resultProducer.send).toHaveBeenCalledTimes(1);
                expect(commitOffsets).toHaveBeenCalledTimes(2);
            });

            it('같은 키로 본문이 다른 명령이 오면 댓글을 만들지 않고 결과 없이 거절한다', async () => {
                issueHttpService.createComment.mockResolvedValue(comment);
                await controller.handleCommand(message, ctx);

                await controller.handleCommand(
                    {...message, payload: {...message.payload, body: 'different'}},
                    ctx,
                );

                expect(issueHttpService.createComment).toHaveBeenCalledTimes(1);
                expect(resultProducer.send).toHaveBeenCalledTimes(1);
                expect(commitOffsets).toHaveBeenCalledTimes(2);
            });

            // GitHub 응답을 받지 못해 결과를 모르는 첫 처리.
            const loseFirstCreateResponse = async () => {
                issueHttpService.createComment.mockRejectedValueOnce(new Error('socket hang up'));
                await expect(controller.handleCommand(message, ctx)).rejects.toThrow('socket hang up');
            };

            it('결과를 모르면 마커로 찾은 기존 댓글을 결과로 보내고 새 댓글을 만들지 않는다', async () => {
                await loseFirstCreateResponse();
                issueHttpService.findCommentByOperation.mockResolvedValue(comment);

                await controller.handleCommand(message, ctx);

                expect(issueHttpService.findCommentByOperation).toHaveBeenCalledWith(
                    expect.objectContaining({issueNumber: 1, operationId}),
                );
                expect(issueHttpService.createComment).toHaveBeenCalledTimes(1);
                expect(resultProducer.send).toHaveBeenCalledWith(
                    expect.objectContaining({status: 'SUCCEEDED', result: comment}),
                );
                expect(commitOffsets).toHaveBeenCalledTimes(1);
            });

            it('결과를 모를 때 마커가 없음을 확인하면 댓글을 한 번 만든다', async () => {
                await loseFirstCreateResponse();
                issueHttpService.findCommentByOperation.mockResolvedValue('absent');
                issueHttpService.createComment.mockResolvedValue(comment);

                await controller.handleCommand(message, ctx);

                expect(issueHttpService.createComment).toHaveBeenCalledTimes(2);
                expect(resultProducer.send).toHaveBeenCalledWith(
                    expect.objectContaining({status: 'SUCCEEDED', result: comment}),
                );
            });

            it('결과를 모를 때 마커 유무를 확정하지 못하면 새 댓글 없이 RESULT_UNKNOWN으로 끝내고 이후 재전달도 만들지 않는다', async () => {
                await loseFirstCreateResponse();
                issueHttpService.findCommentByOperation.mockResolvedValue('unknown');

                await controller.handleCommand(message, ctx);
                await controller.handleCommand(message, ctx);

                expect(issueHttpService.createComment).toHaveBeenCalledTimes(1);
                expect(issueHttpService.findCommentByOperation).toHaveBeenCalledTimes(1);
                expect(resultProducer.send).toHaveBeenCalledTimes(1);
                expect(resultProducer.send).toHaveBeenCalledWith(
                    expect.objectContaining({
                        status: 'FAILED',
                        error: expect.objectContaining({code: 'RESULT_UNKNOWN'}) as unknown,
                    }),
                );
            });
        });
    });

    describe('UPDATE_COMMENT', () => {
        it('댓글 수정 성공 시 SUCCEEDED 결과를 보낸다', async () => {
            issueHttpService.updateComment.mockResolvedValue({
                id: 100,
                author: 'octocat',
                body: 'updated',
                htmlUrl: 'https://github.com/my-org/my-repo/issues/1#issuecomment-100',
                createdAt: '2026-09-26T00:00:00.000Z',
                updatedAt: '2026-09-26T00:00:00.000Z',
            });

            const message = {
                ...baseEnvelope,
                commandType: 'UPDATE_COMMENT',
                payload: {commentId: 100, body: 'updated'},
            };

            await controller.handleCommand(message, ctx);

            expect(issueHttpService.updateComment).toHaveBeenCalledWith(
                'my-org',
                'my-repo',
                100,
                'updated',
            );
            expect(resultProducer.send).toHaveBeenCalledWith(
                expect.objectContaining({
                    status: 'SUCCEEDED',
                    commandType: 'UPDATE_COMMENT',
                }),
            );
        });
    });

    describe('DELETE_COMMENT', () => {
        it('댓글 삭제 성공 시 result 없이 SUCCEEDED 결과를 보낸다', async () => {
            issueHttpService.deleteComment.mockResolvedValue(undefined);

            const message = {
                ...baseEnvelope,
                commandType: 'DELETE_COMMENT',
                payload: {commentId: 100},
            };

            await controller.handleCommand(message, ctx);

            expect(issueHttpService.deleteComment).toHaveBeenCalledWith(
                'my-org',
                'my-repo',
                100,
            );
            expect(resultProducer.send).toHaveBeenCalledWith({
                schemaVersion: 1,
                operationId,
                idempotencyKey: 'idem-key-1',
                commandType: 'DELETE_COMMENT',
                status: 'SUCCEEDED',
                occurredAt: expect.any(String) as string,
            });
        });
    });

    describe('검증 실패', () => {
        it('commandType이 알 수 없는 값이면 서비스 호출 없이 결과 없이 오프셋을 커밋한다', async () => {
            const message = {
                ...baseEnvelope,
                commandType: 'UNKNOWN',
                payload: {},
            };

            await controller.handleCommand(message, ctx);

            expect(issueHttpService.createComment).not.toHaveBeenCalled();
            expect(resultProducer.send).not.toHaveBeenCalled();
            expect(commitOffsets).toHaveBeenCalledTimes(1);
        });

        it('payload가 객체가 아니면 서비스 호출 없이 오프셋을 커밋한다', async () => {
            await controller.handleCommand(null, ctx);

            expect(resultProducer.send).not.toHaveBeenCalled();
            expect(commitOffsets).toHaveBeenCalledTimes(1);
        });

        it('operationId가 UUID 형식이 아니면 결과 없이 오프셋을 커밋한다', async () => {
            const message = {
                ...baseEnvelope,
                operationId: 'not-a-uuid',
                commandType: 'CREATE_COMMENT',
                payload: {
                    issueNumber: 1,
                    body: 'hello',
                    requesterGithubUsername: 'octocat',
                },
            };

            await controller.handleCommand(message, ctx);

            expect(issueHttpService.createComment).not.toHaveBeenCalled();
            expect(resultProducer.send).not.toHaveBeenCalled();
            expect(commitOffsets).toHaveBeenCalledTimes(1);
        });

        it('commandType에 맞지 않는 payload는 FAILED 결과를 보내고 오프셋을 커밋한다', async () => {
            const message = {
                ...baseEnvelope,
                commandType: 'CREATE_COMMENT',
                payload: {issueNumber: 1},
            };

            await controller.handleCommand(message, ctx);

            expect(issueHttpService.createComment).not.toHaveBeenCalled();
            expect(resultProducer.send).toHaveBeenCalledWith(
                expect.objectContaining({status: 'FAILED'}),
            );
            expect(commitOffsets).toHaveBeenCalledTimes(1);
        });
    });
});
