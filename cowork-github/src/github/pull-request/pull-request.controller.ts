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
import { PullRequestActionDto } from './dto/pull-request-action.dto';
import { PullRequestResultProducer } from './kafka/pull-request-result.producer';
import { PullRequestService } from './pull-request.service';

@Controller()
export class PullRequestController {
  private readonly logger = new Logger(PullRequestController.name);
  private readonly exitProcess = (code: number): never => process.exit(code);

  constructor(
    private readonly pullRequestService: PullRequestService,
    private readonly resultProducer: PullRequestResultProducer,
  ) {}

  @EventPattern('github.pr.merge')
  async handleMerge(
    @Payload() data: unknown,
    @Ctx() context: KafkaContext,
  ): Promise<void> {
    const shouldCommit = await this.processMessage(data, 'merge');
    if (shouldCommit) await this.commitOffset(context);
  }

  @EventPattern('github.pr.approve')
  async handleApprove(
    @Payload() data: unknown,
    @Ctx() context: KafkaContext,
  ): Promise<void> {
    const shouldCommit = await this.processMessage(data, 'approve');
    if (shouldCommit) await this.commitOffset(context);
  }

  private async processMessage(
    data: unknown,
    action: 'merge' | 'approve',
  ): Promise<boolean> {
    const resultTopic =
      action === 'merge'
        ? 'github.pr.merge.result'
        : 'github.pr.approve.result';

    if (data === null || typeof data !== 'object') {
      this.logger.error('Invalid payload type, skipping message');
      return true;
    }

    const dto = plainToInstance(PullRequestActionDto, data);
    const errors = await validate(dto, { whitelist: true });

    if (errors.length > 0) {
      this.logger.error('Invalid payload, skipping message', {
        errors: errors.map((e) => e.toString()),
      });
      if (dto.channelId != null && dto.teamId != null) {
        await this.resultProducer.send(resultTopic, {
          channelId: dto.channelId,
          teamId: dto.teamId,
          success: false,
          prNumber: dto.prNumber,
          error: '잘못된 요청입니다.',
        });
      }
      return true;
    }

    try {
      const result =
        action === 'merge'
          ? await this.pullRequestService.mergePullRequest(dto)
          : await this.pullRequestService.approvePullRequest(dto);

      if (dto.channelId != null && dto.teamId != null) {
        await this.resultProducer.send(resultTopic, {
          channelId: dto.channelId,
          teamId: dto.teamId,
          success: true,
          prNumber: result.prNumber,
          prUrl: result.prUrl,
        });
      }
      return true;
    } catch (error) {
      if (error instanceof GithubClientError) {
        this.logger.error('GitHub client error, skipping message', {
          owner: dto.owner,
          repo: dto.repo,
          prNumber: dto.prNumber,
          statusCode: error.statusCode,
          message: error.message,
        });
        if (dto.channelId != null && dto.teamId != null) {
          await this.resultProducer.send(resultTopic, {
            channelId: dto.channelId,
            teamId: dto.teamId,
            success: false,
            prNumber: dto.prNumber,
            error: error.message,
          });
        }
        return true;
      }

      this.logger.error('GitHub server error, consumer will stop for retry', {
        owner: dto.owner,
        repo: dto.repo,
        prNumber: dto.prNumber,
        message: (error as Error).message,
      });

      this.exitProcess(1);
      throw error;
    }
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
