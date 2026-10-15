import { Controller, Logger } from '@nestjs/common';
import {
  Ctx,
  EventPattern,
  KafkaContext,
  Payload,
} from '@nestjs/microservices';
import { plainToInstance } from 'class-transformer';
import { validate } from 'class-validator';
import { GithubClientError } from '../github.errors';
import { IssueHttpService } from '../issue-http/issue-http.service';
import { LabelHttpService } from '../label-http/label-http.service';
import { CreateCommentPayloadDto } from './dto/create-comment-payload.dto';
import { DeleteCommentPayloadDto } from './dto/delete-comment-payload.dto';
import { IssueWriteCommandEnvelopeDto } from './dto/issue-write-command-envelope.dto';
import { ReplaceLabelsPayloadDto } from './dto/replace-labels-payload.dto';
import { UpdateCommentPayloadDto } from './dto/update-comment-payload.dto';
import { IssueWriteResultEvent } from './event/issue-write-result.event';
import { IssueWriteResultProducer } from './issue-write-result.producer';
import { IssueWriteCommandType } from './issue-write-command-type';

type PayloadValidationResult =
  | { ok: true; payload: unknown }
  | { ok: false; errors: string[] };

@Controller()
export class IssueWriteCommandController {
  private readonly logger = new Logger(IssueWriteCommandController.name);
  private readonly exitProcess = (code: number): never => process.exit(code);

  constructor(
    private readonly issueHttpService: IssueHttpService,
    private readonly labelHttpService: LabelHttpService,
    private readonly resultProducer: IssueWriteResultProducer,
  ) {}

  @EventPattern('github-app.issue-write.command')
  async handleCommand(
    @Payload() data: unknown,
    @Ctx() context: KafkaContext,
  ): Promise<void> {
    const shouldCommit = await this.processMessage(data);
    if (shouldCommit) await this.commitOffset(context);
  }

  private async processMessage(data: unknown): Promise<boolean> {
    if (data === null || typeof data !== 'object') {
      this.logger.error('Invalid payload type, skipping message');
      return true;
    }

    const envelope = plainToInstance(IssueWriteCommandEnvelopeDto, data);
    const envelopeErrors = await validate(envelope, { whitelist: true });

    if (envelopeErrors.length > 0) {
      // 봉투 자체가 형식에 맞지 않으면 operationId를 신뢰할 수 없으므로
      // 결과 이벤트 없이 로그만 남기고 스킵한다 (github.controller.ts와 동일 정책).
      this.logger.error('Invalid envelope, skipping message', {
        errors: envelopeErrors.map((e) => e.toString()),
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
      const result = await this.dispatch(envelope, payloadResult.payload);
      await this.resultProducer.send(this.buildSuccessResult(envelope, result));
      return true;
    } catch (error) {
      if (error instanceof GithubClientError) {
        this.logger.error('GitHub client error, skipping message', {
          owner: envelope.owner,
          repo: envelope.repo,
          commandType: envelope.commandType,
          operationId: envelope.operationId,
          statusCode: error.statusCode,
          message: error.message,
        });
        await this.resultProducer.send(
          this.buildFailureResult(
            envelope,
            error.statusCode.toString(),
            error.message,
          ),
        );
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

  private async validatePayload(
    envelope: IssueWriteCommandEnvelopeDto,
  ): Promise<PayloadValidationResult> {
    const payloadClass = this.payloadClassFor(envelope.commandType);
    const payload = plainToInstance(payloadClass, envelope.payload);
    const errors = await validate(payload, { whitelist: true });
    if (errors.length > 0) {
      return { ok: false, errors: errors.map((e) => e.toString()) };
    }
    return { ok: true, payload };
  }

  private payloadClassFor(
    commandType: IssueWriteCommandType,
  ): new () => object {
    switch (commandType) {
      case IssueWriteCommandType.REPLACE_LABELS:
        return ReplaceLabelsPayloadDto;
      case IssueWriteCommandType.CREATE_COMMENT:
        return CreateCommentPayloadDto;
      case IssueWriteCommandType.UPDATE_COMMENT:
        return UpdateCommentPayloadDto;
      case IssueWriteCommandType.DELETE_COMMENT:
        return DeleteCommentPayloadDto;
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
        return { labels };
      }
      case IssueWriteCommandType.CREATE_COMMENT: {
        const p = payload as CreateCommentPayloadDto;
        return this.issueHttpService.createComment(
          envelope.owner,
          envelope.repo,
          p.issueNumber,
          p.body,
          p.requesterGithubUsername,
        );
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
      ...(result !== undefined ? { result } : {}),
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
      error: { code, message },
      occurredAt: new Date().toISOString(),
    };
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
