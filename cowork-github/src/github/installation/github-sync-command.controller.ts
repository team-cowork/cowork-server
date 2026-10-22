import {Controller, Logger} from '@nestjs/common';
import {
    Ctx,
    EventPattern,
    KafkaContext,
    Payload,
} from '@nestjs/microservices';
import {plainToInstance} from 'class-transformer';
import {validate} from 'class-validator';
import {GithubBackfillService, type GithubSyncTarget} from './github-backfill.service';
import {GithubSyncCommandDto} from './github-sync-command.dto';

export const GITHUB_SYNC_COMMAND_TOPIC = 'github.sync.command.v1';

@Controller()
export class GithubSyncCommandController {
    private readonly logger = new Logger(GithubSyncCommandController.name);

    constructor(private readonly backfillService: GithubBackfillService) {}

    // 동기화는 수 분이 걸릴 수 있어 대기열에 넣기만 하고 바로 커밋한다. 핸들러에서 기다리면 같은
    // consumer group의 다른 커맨드(머지·승인·이슈 쓰기)가 그동안 막힌다.
    @EventPattern(GITHUB_SYNC_COMMAND_TOPIC)
    async handleCommand(
        @Payload() data: unknown,
        @Ctx() context: KafkaContext,
    ): Promise<void> {
        const target = await this.toTarget(data);
        if (target === undefined) {
            this.logger.error('Invalid GitHub sync command, skipping message');
        } else {
            void this.backfillService.enqueue(target);
        }

        await this.commitOffset(context);
    }

    private async toTarget(data: unknown): Promise<GithubSyncTarget | undefined> {
        if (data === null || typeof data !== 'object') {
            return undefined;
        }

        const command = plainToInstance(GithubSyncCommandDto, data);
        const errors = await validate(command, {whitelist: true});
        if (errors.length > 0) {
            return undefined;
        }

        return command.target === 'REPOSITORY'
            ? {type: 'REPOSITORY', owner: command.owner, repo: command.repo!}
            : {type: 'ORG_REPOSITORIES', installationId: command.installationId!, owner: command.owner};
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
