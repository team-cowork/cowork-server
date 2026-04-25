import { Controller, Logger } from '@nestjs/common';
import {
  Ctx,
  EventPattern,
  KafkaContext,
  Payload,
} from '@nestjs/microservices';
import { plainToInstance } from 'class-transformer';
import { validate } from 'class-validator';
import { CreateIssueDto } from './dto/create-issue.dto';
import { IssueService } from './issue/issue.service';
import { GithubClientError } from './github.errors';

@Controller()
export class GithubController {
  private readonly logger = new Logger(GithubController.name);

  constructor(private readonly issueService: IssueService) {}

  @EventPattern('github.issue.create')
  async handleIssueCreate(
    @Payload() data: unknown,
    @Ctx() context: KafkaContext,
  ): Promise<void> {
    const shouldCommit = await this.processMessage(data);
    if (shouldCommit) await this.commitOffset(context);
  }

  private async processMessage(data: unknown): Promise<boolean> {
    const dto = plainToInstance(CreateIssueDto, data);
    const errors = await validate(dto, { whitelist: true });

    if (errors.length > 0) {
      this.logger.error('Invalid payload, skipping message', {
        errors: errors.map((e) => e.toString()),
      });
      return true;
    }

    try {
      await this.issueService.createIssue(dto);
      return true;
    } catch (error) {
      if (error instanceof GithubClientError) {
        this.logger.error('GitHub client error, skipping message', {
          owner: dto.owner,
          repo: dto.repo,
          title: dto.title,
          statusCode: error.statusCode,
          message: error.message,
        });
        return true;
      }
      this.logger.error('GitHub server error, offset not committed', {
        owner: dto.owner,
        repo: dto.repo,
        title: dto.title,
        message: (error as Error).message,
      });
      return false;
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
