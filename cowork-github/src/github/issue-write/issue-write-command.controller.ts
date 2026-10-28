import {createHash} from 'node:crypto';
import {Controller, Logger} from '@nestjs/common';
import {
    Ctx,
    EventPattern,
    KafkaContext,
    Payload,
} from '@nestjs/microservices';
import {plainToInstance} from 'class-transformer';
import {validate} from 'class-validator';
import {GithubClientError} from '../github.errors';
import {IssueHttpService} from '../issue-http/issue-http.service';
import {LabelHttpService} from '../label-http/label-http.service';
import {CreateCommentPayloadDto} from './dto/create-comment-payload.dto';
import {DeleteCommentPayloadDto} from './dto/delete-comment-payload.dto';
import {IssueWriteCommandEnvelopeDto} from './dto/issue-write-command-envelope.dto';
import {ReplaceLabelsPayloadDto} from './dto/replace-labels-payload.dto';
import {UpdateCommentPayloadDto} from './dto/update-comment-payload.dto';
import {IssueWriteResultEvent} from './event/issue-write-result.event';
import {CreateCommentHistory} from './create-comment-history';
import {IssueWriteResultProducer} from './issue-write-result.producer';
import {IssueWriteCommandType} from './issue-write-command-type';
import {ISSUE_WRITE_COMMAND_TOPIC} from './issue-write-topics';

type PayloadValidationResult =
    | {ok: true; payload: unknown}
    | {ok: false; errors: string[]};

// 접수 시각은 이 서버 시계, 댓글 updated_at은 GitHub 시계라 그 차이만큼 마커 조회 범위를 앞당긴다.
const CREATE_COMMENT_CLOCK_SKEW_MS = 5 * 60 * 1000;

@Controller()
export class IssueWriteCommandController {
    private readonly logger = new Logger(IssueWriteCommandController.name);
    private readonly exitProcess = (code: number): never => process.exit(code);

    constructor(
        private readonly issueHttpService: IssueHttpService,
        private readonly labelHttpService: LabelHttpService,
        private readonly resultProducer: IssueWriteResultProducer,
        private readonly commentHistory: CreateCommentHistory,
    ) {}

    @EventPattern(ISSUE_WRITE_COMMAND_TOPIC)
    async handleCommand(
        @Payload() data: unknown,
        @Ctx() context: KafkaContext,
    ): Promise<void> {
        const shouldCommit = await this.processMessage(data);
        if (shouldCommit) {
            await this.commitOffset(context);
        }
    }

    private async processMessage(data: unknown): Promise<boolean> {
        if (data === null || typeof data !== 'object') {
            this.logger.error('Invalid payload type, skipping message');
            return true;
        }

        const envelope = plainToInstance(IssueWriteCommandEnvelopeDto, data);
        const envelopeErrors = await validate(envelope, {whitelist: true});

        if (envelopeErrors.length > 0) {
            // 봉투 자체가 형식에 맞지 않으면 operationId를 신뢰할 수 없으므로
            // 결과 이벤트 없이 로그만 남기고 스킵한다 (github.controller.ts와 동일 정책).
            this.logger.error('Invalid envelope, skipping message', {
                errors: envelopeErrors.map(error => error.toString()),
            });
            return true;
        }

        const payloadResult = await this.validatePayload(envelope);
        if (!payloadResult.ok) {
            this.logger.error('Invalid command payload, skipping message', {
                operationId: envelope.operationId,
                commandType: envelope.commandType,
                errors: payloadResult.errors,
            });
            await this.resultProducer.send(
                this.buildFailureResult(
                    envelope,
                    'INVALID_PAYLOAD',
                    '잘못된 커맨드 페이로드입니다.',
                ),
            );
            return true;
        }

        try {
            if (envelope.commandType === IssueWriteCommandType.CREATE_COMMENT) {
                await this.processCreateComment(envelope, payloadResult.payload as CreateCommentPayloadDto);
                return true;
            }

            const result = await this.dispatch(envelope, payloadResult.payload);
            await this.resultProducer.send(this.buildSuccessResult(envelope, result));
            return true;
        } catch (error) {
            if (error instanceof GithubClientError) {
                await this.resultProducer.send(this.buildClientFailureResult(envelope, error));
                return true;
            }

            this.logger.error('GitHub server error, consumer will stop for retry', {
                owner: envelope.owner,
                repo: envelope.repo,
                commandType: envelope.commandType,
                operationId: envelope.operationId,
                message: (error as Error).message,
            });

            this.exitProcess(1);
            throw error;
        }
    }

    // 댓글 생성은 GitHub 쓰기와 결과 발행·오프셋 저장을 한 트랜잭션으로 묶을 수 없어,
    // operationId별 처리 이력으로 재전달을 댓글 하나와 결과 하나로 수렴시킨다.
    // ponytail: 단일 소비자(Nomad count=1, 파티션 1)를 전제로 한다. 동시 소비가 생기면 RECEIVED 선점에 lease가 필요하다.
    private async processCreateComment(
        envelope: IssueWriteCommandEnvelopeDto,
        payload: CreateCommentPayloadDto,
    ): Promise<void> {
        const fingerprint = createHash('sha256')
            .update(JSON.stringify([
                envelope.idempotencyKey,
                envelope.owner,
                envelope.repo,
                envelope.requestedBy,
                payload.issueNumber,
                payload.body,
                payload.requesterGithubUsername,
            ]))
            .digest('hex');
        const {record, claimed} = await this.commentHistory.claim(envelope.operationId, fingerprint);

        if (record.fingerprint !== fingerprint) {
            // 이 operationId의 결과는 원래 명령의 원장에 반영되므로, 다른 요청에는 결과를 보내지 않는다.
            this.logger.error('Idempotency key reused with a different command, skipping message', {
                operationId: envelope.operationId,
                idempotencyKey: envelope.idempotencyKey,
            });
            return;
        }

        if (record.state === 'DELIVERED') {
            this.logger.log('Create-comment result already delivered, skipping message', {
                operationId: envelope.operationId,
            });
            return;
        }

        let event: IssueWriteResultEvent;
        if (record.state === 'RECEIVED') {
            event = await this.resolveCreateComment(envelope, payload, record.receivedAt, !claimed);
            await this.commentHistory.save(envelope.operationId, {...record, state: 'COMPLETED', event});
        } else {
            ({event} = record);
        }

        await this.resultProducer.send(event);
        await this.commentHistory.save(envelope.operationId, {...record, state: 'DELIVERED', event});
    }

    // 재전달에서 RECEIVED를 만나면 이전 처리가 GitHub 호출 뒤 결과를 남기지 못한 것이므로, 마커로 기존 댓글을 먼저 찾는다.
    private async resolveCreateComment(
        envelope: IssueWriteCommandEnvelopeDto,
        payload: CreateCommentPayloadDto,
        receivedAt: string,
        isResultUnknown: boolean,
    ): Promise<IssueWriteResultEvent> {
        try {
            if (isResultUnknown) {
                const since = new Date(Date.parse(receivedAt) - CREATE_COMMENT_CLOCK_SKEW_MS).toISOString();
                const found = await this.issueHttpService.findCommentByOperation({
                    owner: envelope.owner,
                    repo: envelope.repo,
                    issueNumber: payload.issueNumber,
                    operationId: envelope.operationId,
                    since,
                });
                if (found === 'unknown') {
                    this.logger.error('Create-comment result unknown, not creating another comment', {
                        operationId: envelope.operationId,
                    });
                    return this.buildFailureResult(
                        envelope,
                        'RESULT_UNKNOWN',
                        '댓글 생성 결과를 확인할 수 없어 다시 작성하지 않았습니다. 수동 확인이 필요합니다.',
                    );
                }

                if (found !== 'absent') {
                    this.logger.warn('Recovered created comment by operation marker', {
                        operationId: envelope.operationId,
                        commentId: found.id,
                    });
                    return this.buildSuccessResult(envelope, found);
                }
            }

            const comment = await this.issueHttpService.createComment({
                owner: envelope.owner,
                repo: envelope.repo,
                issueNumber: payload.issueNumber,
                body: payload.body,
                requesterGithubUsername: payload.requesterGithubUsername,
                operationId: envelope.operationId,
            });
            return this.buildSuccessResult(envelope, comment);
        } catch (error) {
            if (error instanceof GithubClientError) {
                return this.buildClientFailureResult(envelope, error);
            }

            throw error;
        }
    }

    private async validatePayload(
        envelope: IssueWriteCommandEnvelopeDto,
    ): Promise<PayloadValidationResult> {
        const payloadClass = this.payloadClassFor(envelope.commandType);
        const payload = plainToInstance(payloadClass, envelope.payload);
        const errors = await validate(payload, {whitelist: true});
        return errors.length > 0 ? {ok: false, errors: errors.map(error => error.toString())} : {ok: true, payload};
    }

    private payloadClassFor(
        commandType: IssueWriteCommandType,
    ): new () => object {
        switch (commandType) {
            case IssueWriteCommandType.REPLACE_LABELS: {
                return ReplaceLabelsPayloadDto;
            }

            case IssueWriteCommandType.CREATE_COMMENT: {
                return CreateCommentPayloadDto;
            }

            case IssueWriteCommandType.UPDATE_COMMENT: {
                return UpdateCommentPayloadDto;
            }

            case IssueWriteCommandType.DELETE_COMMENT: {
                return DeleteCommentPayloadDto;
            }
        }
    }

    private async dispatch(
        envelope: IssueWriteCommandEnvelopeDto,
        payload: unknown,
    ): Promise<unknown> {
        switch (envelope.commandType) {
            case IssueWriteCommandType.REPLACE_LABELS: {
                const p = payload as ReplaceLabelsPayloadDto;
                const labels = await this.labelHttpService.replaceLabels(
                    envelope.owner,
                    envelope.repo,
                    p.issueNumber,
                    p.labels,
                );
                return {labels};
            }

            case IssueWriteCommandType.CREATE_COMMENT: {
                throw new Error('CREATE_COMMENT is handled by processCreateComment');
            }

            case IssueWriteCommandType.UPDATE_COMMENT: {
                const p = payload as UpdateCommentPayloadDto;
                return this.issueHttpService.updateComment(
                    envelope.owner,
                    envelope.repo,
                    p.commentId,
                    p.body,
                );
            }

            case IssueWriteCommandType.DELETE_COMMENT: {
                const p = payload as DeleteCommentPayloadDto;
                await this.issueHttpService.deleteComment(
                    envelope.owner,
                    envelope.repo,
                    p.commentId,
                );
                return undefined;
            }
        }
    }

    private buildSuccessResult(
        envelope: IssueWriteCommandEnvelopeDto,
        result: unknown,
    ): IssueWriteResultEvent {
        return {
            schemaVersion: 1,
            operationId: envelope.operationId,
            idempotencyKey: envelope.idempotencyKey,
            commandType: envelope.commandType,
            status: 'SUCCEEDED',
            ...((result !== undefined) && {result}),
            occurredAt: new Date().toISOString(),
        };
    }

    private buildFailureResult(
        envelope: IssueWriteCommandEnvelopeDto,
        code: string,
        message: string,
    ): IssueWriteResultEvent {
        return {
            schemaVersion: 1,
            operationId: envelope.operationId,
            idempotencyKey: envelope.idempotencyKey,
            commandType: envelope.commandType,
            status: 'FAILED',
            error: {code, message},
            occurredAt: new Date().toISOString(),
        };
    }

    private buildClientFailureResult(
        envelope: IssueWriteCommandEnvelopeDto,
        error: GithubClientError,
    ): IssueWriteResultEvent {
        this.logger.error('GitHub client error, skipping message', {
            owner: envelope.owner,
            repo: envelope.repo,
            commandType: envelope.commandType,
            operationId: envelope.operationId,
            statusCode: error.statusCode,
            message: error.message,
        });
        return this.buildFailureResult(envelope, error.statusCode.toString(), error.message);
    }

    private async commitOffset(context: KafkaContext): Promise<void> {
        const message = context.getMessage();
        await context.getConsumer().commitOffsets([
            {
                topic: context.getTopic(),
                partition: context.getPartition(),
                offset: (Number(message.offset) + 1).toString(),
            },
        ]);
    }
}
