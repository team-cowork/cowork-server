import {Controller as controller, Logger} from '@nestjs/common';
import {
	Ctx as messageContext,
	EventPattern as eventPattern,
	KafkaContext,
	Payload as messagePayload,
} from '@nestjs/microservices';
import {plainToInstance} from 'class-transformer';
import {validate} from 'class-validator';
import {GithubClientError} from '../../github.errors.js';
import {ProjectBoardApiService} from '../project-board-api.service.js';
import {ProjectItemWriteCommandEnvelopeDto} from './dto/project-item-write-command-envelope.dto.js';
import {MoveItemPayloadDto, ReorderItemPayloadDto} from './dto/project-item-write-payload.dto.js';
import {type ProjectItemWriteResultEvent} from './project-item-write-result.event.js';
import {ProjectItemWriteResultProducer} from './project-item-write-result.producer.js';
import {PROJECT_ITEM_WRITE_COMMAND_TOPIC, ProjectItemWriteCommandType} from './project-item-write-topics.js';

/**
 카드 이동 커맨드를 GitHub GraphQL mutation으로 실행하고 결과를 발행한다. 두 mutation은 같은 값을 다시 적용해도
 결과가 같으므로, 재전달된 커맨드를 다시 실행해도 안전하다. Projection은 결과가 아니라 `projects_v2_item` 웹훅으로 바뀐다.
 */
type ItemTarget = {owner: string; projectNodeId: string; itemId: string};

type CommandPayload = MoveItemPayloadDto | ReorderItemPayloadDto;

// 커맨드마다 payload DTO와 실행 함수를 둔다. 새 커맨드는 CommandPayload와 이 목록에 항목을 더한다.
type CommandHandler = {
	payloadType: new () => CommandPayload;
	run: (target: ItemTarget, payload: CommandPayload) => Promise<void>;
};

@controller()
export class ProjectItemWriteCommandController {
	private readonly logger = new Logger(ProjectItemWriteCommandController.name);

	private readonly handlers: Record<ProjectItemWriteCommandType, CommandHandler> = {
		[ProjectItemWriteCommandType.MOVE_ITEM]: {
			payloadType: MoveItemPayloadDto,
			run: async (target, payload) => this.moveItem(target, payload as MoveItemPayloadDto),
		},
		[ProjectItemWriteCommandType.REORDER_ITEM]: {
			payloadType: ReorderItemPayloadDto,
			run: async (target, payload) => this.reorderItem(target, payload),
		},
	};

	constructor(
		private readonly api: ProjectBoardApiService,
		private readonly resultProducer: ProjectItemWriteResultProducer,
	) {}

	private result(
		envelope: ProjectItemWriteCommandEnvelopeDto,
		error?: {code: string; message: string},
	): ProjectItemWriteResultEvent {
		return {
			schemaVersion: 1,
			operationId: envelope.operationId,
			idempotencyKey: envelope.idempotencyKey,
			commandType: envelope.commandType,
			status: error === undefined ? 'SUCCEEDED' : 'FAILED',
			...(error !== undefined && {error}),
			occurredAt: new Date().toISOString(),
		};
	}

	private async validatePayload(handler: CommandHandler, payload: Record<string, unknown>): Promise<CommandPayload | undefined> {
		const instance = plainToInstance(handler.payloadType, payload);
		const errors = await validate(instance, {whitelist: true, forbidNonWhitelisted: true});
		return errors.length === 0 ? instance : undefined;
	}

	private async moveItem(target: ItemTarget, payload: MoveItemPayloadDto): Promise<void> {
		const fieldTarget = {...target, fieldId: payload.fieldId};
		if (typeof payload.singleSelectOptionId === 'string') {
			await this.api.updateFieldValue({...fieldTarget, value: {singleSelectOptionId: payload.singleSelectOptionId}});
		} else if (typeof payload.iterationId === 'string') {
			await this.api.updateFieldValue({...fieldTarget, value: {iterationId: payload.iterationId}});
		} else {
			await this.api.clearFieldValue(fieldTarget);
		}

		if (payload.reposition) {
			await this.reorderItem(target, payload);
		}
	}

	private async reorderItem(target: ItemTarget, payload: ReorderItemPayloadDto): Promise<void> {
		await this.api.updatePosition({...target, afterId: payload.afterItemId ?? null});
	}

	/**
     커밋해도 되면 true다. GitHub 서버 오류는 던져서 같은 커맨드를 다시 받는다.
     */
	private async processMessage(data: unknown): Promise<boolean> {
		if (data === null || typeof data !== 'object') {
			this.logger.error('Invalid project item write payload type, skipping message');
			return true;
		}

		const envelope = plainToInstance(ProjectItemWriteCommandEnvelopeDto, data);
		const envelopeErrors = await validate(envelope, {whitelist: true});
		if (envelopeErrors.length > 0) {
			// 봉투가 잘못되면 operationId를 믿을 수 없어 결과 없이 건너뛴다.
			this.logger.error('Invalid project item write envelope, skipping message', {
				errors: envelopeErrors.map(error => error.toString()),
			});
			return true;
		}

		const handler = this.handlers[envelope.commandType];
		const payload = await this.validatePayload(handler, envelope.payload);
		if (payload === undefined || envelope.itemId === undefined) {
			await this.resultProducer.send(this.result(envelope, {code: 'INVALID_PAYLOAD', message: '잘못된 커맨드 페이로드입니다.'}));
			return true;
		}

		try {
			await handler.run({owner: envelope.owner, projectNodeId: envelope.projectNodeId, itemId: envelope.itemId}, payload);
			await this.resultProducer.send(this.result(envelope));
			return true;
		} catch (error) {
			if (error instanceof GithubClientError) {
				this.logger.warn('GitHub rejected project item write', {
					operationId: envelope.operationId,
					commandType: envelope.commandType,
					statusCode: error.statusCode,
					message: error.message,
				});
				await this.resultProducer.send(this.result(envelope, {
					code: error.statusCode.toString(),
					message: error.message.slice(0, 500),
				}));
				return true;
			}

			this.logger.error('GitHub server error, project item write will be redelivered', {
				operationId: envelope.operationId,
				commandType: envelope.commandType,
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

	@eventPattern(PROJECT_ITEM_WRITE_COMMAND_TOPIC)
	async handleCommand(
		@messagePayload() data: unknown,
		@messageContext() context: KafkaContext,
	): Promise<void> {
		if (await this.processMessage(data)) {
			await this.commitOffset(context);
		}
	}
}
