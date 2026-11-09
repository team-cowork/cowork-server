import {Controller as controller, Logger} from '@nestjs/common';
import {
	Ctx as messageContext,
	EventPattern as eventPattern,
	KafkaContext,
	Payload as messagePayload,
} from '@nestjs/microservices';
import {plainToInstance} from 'class-transformer';
import {validate} from 'class-validator';
import {isPresent} from './common/nullable.js';
import {CreateIssueDto} from './dto/create-issue.dto.js';
import {IssueService} from './issue/issue.service.js';
import {GithubClientError} from './github.errors.js';
import {IssueResultProducer} from './kafka/issue-result.producer.js';

@controller()
export class GithubController {
	private readonly logger = new Logger(GithubController.name);

	constructor(
		private readonly issueService: IssueService,
		private readonly issueResultProducer: IssueResultProducer,
	) {}

	private async processMessage(data: unknown): Promise<boolean> {
		if (data === null || typeof data !== 'object') {
			this.logger.error('Invalid payload type, skipping message');
			return true;
		}

		const dto = plainToInstance(CreateIssueDto, data);
		const errors = await validate(dto, {whitelist: true});

		if (errors.length > 0) {
			this.logger.error('Invalid payload, skipping message', {
				errors: errors.map(error => error.toString()),
			});
			if (isPresent(dto.channelId) && isPresent(dto.teamId)) {
				await this.issueResultProducer.send({
					channelId: dto.channelId,
					teamId: dto.teamId,
					success: false,
					error: '잘못된 이슈 생성 요청입니다.',
				});
			}

			return true;
		}

		try {
			const result = await this.issueService.createIssue(dto);
			if (isPresent(dto.channelId) && isPresent(dto.teamId)) {
				// 이슈는 이미 만들어졌으므로 전송 실패로 재전달하면 이슈가 한 번 더 생긴다. 결과 메시지 하나를 잃는 쪽을 택한다.
				await this.issueResultProducer.send({
					channelId: dto.channelId,
					teamId: dto.teamId,
					success: true,
					issueUrl: result.issueUrl,
					issueNumber: result.issueNumber,
				}).catch((error: unknown) => {
					this.logger.error('Failed to send issue result after the issue was created, skipping redelivery', {
						channelId: dto.channelId,
						issueNumber: result.issueNumber,
						message: error instanceof Error ? error.message : String(error),
					});
				});
			}

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
				if (isPresent(dto.channelId) && isPresent(dto.teamId)) {
					await this.issueResultProducer.send({
						channelId: dto.channelId,
						teamId: dto.teamId,
						success: false,
						error: error.message,
					});
				}

				return true;
			}

			this.logger.error('GitHub server error, message will be redelivered', {
				owner: dto.owner,
				repo: dto.repo,
				title: dto.title,
				message: (error as Error).message,
			});

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

	@eventPattern('github.issue.create')
	async handleIssueCreate(
		@messagePayload() data: unknown,
		@messageContext() context: KafkaContext,
	): Promise<void> {
		const shouldCommit = await this.processMessage(data);
		if (shouldCommit) {
			await this.commitOffset(context);
		}
	}
}
