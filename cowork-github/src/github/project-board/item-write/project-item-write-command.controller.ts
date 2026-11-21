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
import {
	AddItemPayloadDto,
	ClearFieldPayloadDto,
	ConvertDraftPayloadDto,
	CreateDraftPayloadDto,
	MoveItemPayloadDto,
	ReorderItemPayloadDto,
	SetAssigneesPayloadDto,
	SetFieldPayloadDto,
	UpdateDraftPayloadDto,
} from './dto/project-item-write-payload.dto.js';
import {ProjectItemEditorService, type ProjectItemTarget, type ProjectItemWriteResult} from './project-item-editor.service.js';
import {type ProjectItemWriteResultEvent} from './project-item-write-result.event.js';
import {ProjectItemWriteResultProducer} from './project-item-write-result.producer.js';
import {PROJECT_ITEM_WRITE_COMMAND_TOPIC, ProjectItemWriteCommandType} from './project-item-write-topics.js';

/**
 카드 쓰기 커맨드를 실행하고 결과를 발행한다. Projection은 결과가 아니라 GitHub 웹훅으로 바뀐다.
 */
type ItemTarget = {owner: string; projectNodeId: string; itemId: string};

type CommandPayload = MoveItemPayloadDto | ReorderItemPayloadDto | SetFieldPayloadDto | ClearFieldPayloadDto
	| CreateDraftPayloadDto | UpdateDraftPayloadDto | AddItemPayloadDto
	| ConvertDraftPayloadDto | SetAssigneesPayloadDto;

// 커맨드마다 payload DTO와 실행 함수를 둔다. 새 커맨드는 CommandPayload와 이 목록에 항목을 더한다.
type CommandHandler = {
	payloadType?: new () => CommandPayload;
	createsItem?: boolean;
	run: (target: ProjectItemTarget, payload: CommandPayload, operationId: string) => Promise<ProjectItemWriteResult | void>;
};

@controller()
export class ProjectItemWriteCommandController {
	private readonly logger = new Logger(ProjectItemWriteCommandController.name);

	private readonly handlers: Record<ProjectItemWriteCommandType, CommandHandler> = {
		[ProjectItemWriteCommandType.MOVE_ITEM]: {
			payloadType: MoveItemPayloadDto,
			run: async (target, payload) => this.moveItem(target as ItemTarget, payload as MoveItemPayloadDto),
		},
		[ProjectItemWriteCommandType.REORDER_ITEM]: {
			payloadType: ReorderItemPayloadDto,
			run: async (target, payload) => this.reorderItem(target as ItemTarget, payload as ReorderItemPayloadDto),
		},
		[ProjectItemWriteCommandType.SET_FIELD]: {
			payloadType: SetFieldPayloadDto,
			run: async (target, payload) => this.editor.setField(target, payload as SetFieldPayloadDto),
		},
		[ProjectItemWriteCommandType.CLEAR_FIELD]: {
			payloadType: ClearFieldPayloadDto,
			run: async (target, payload) => this.editor.clearField(target, (payload as ClearFieldPayloadDto).fieldId),
		},
		[ProjectItemWriteCommandType.CREATE_DRAFT]: {
			payloadType: CreateDraftPayloadDto,
			createsItem: true,
			run: async (target, payload, operationId) => this.editor.createDraft(target, payload as CreateDraftPayloadDto, operationId),
		},
		[ProjectItemWriteCommandType.UPDATE_DRAFT]: {
			payloadType: UpdateDraftPayloadDto,
			run: async (target, payload) => this.editor.updateDraft(target, payload as UpdateDraftPayloadDto),
		},
		[ProjectItemWriteCommandType.ADD_ITEM]: {
			payloadType: AddItemPayloadDto,
			createsItem: true,
			run: async (target, payload) => this.editor.addItem(target, payload as AddItemPayloadDto),
		},
		[ProjectItemWriteCommandType.REMOVE_ITEM]: {
			run: async target => this.editor.removeItem(target),
		},
		[ProjectItemWriteCommandType.CONVERT_DRAFT]: {
			payloadType: ConvertDraftPayloadDto,
			run: async (target, payload) => this.editor.convertDraft(target, (payload as ConvertDraftPayloadDto).repo),
		},
		[ProjectItemWriteCommandType.SET_ASSIGNEES]: {
			payloadType: SetAssigneesPayloadDto,
			run: async (target, payload) => this.editor.setAssignees(target, payload as SetAssigneesPayloadDto),
		},
	};

	constructor(
		private readonly api: ProjectBoardApiService,
		private readonly resultProducer: ProjectItemWriteResultProducer,
		private readonly editor: ProjectItemEditorService,
	) {}

	private result(
		envelope: ProjectItemWriteCommandEnvelopeDto,
		error?: {code: string; message: string},
		result?: ProjectItemWriteResult | void,
	): ProjectItemWriteResultEvent {
		return {
			schemaVersion: 1,
			operationId: envelope.operationId,
			idempotencyKey: envelope.idempotencyKey,
			commandType: envelope.commandType,
			status: error === undefined ? 'SUCCEEDED' : 'FAILED',
			...(error !== undefined && {error}),
			...(result !== undefined && {result}),
			occurredAt: new Date().toISOString(),
		};
	}

	private async validatePayload(handler: CommandHandler, payload: Record<string, unknown>): Promise<CommandPayload | undefined> {
		if (handler.payloadType === undefined) {
			return Object.keys(payload).length === 0 ? new ReorderItemPayloadDto() : undefined;
		}

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
		const hasItem = typeof envelope.itemId === 'string' && envelope.itemId.length > 0;
		if (payload === undefined || (handler.createsItem === true ? hasItem : !hasItem)) {
			await this.resultProducer.send(this.result(envelope, {code: 'INVALID_PAYLOAD', message: '잘못된 커맨드 페이로드입니다.'}));
			return true;
		}

		try {
			const result = await handler.run(
				{owner: envelope.owner, projectNodeId: envelope.projectNodeId, itemId: envelope.itemId}, payload, envelope.operationId,
			);
			await this.resultProducer.send(this.result(envelope, undefined, result));
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
