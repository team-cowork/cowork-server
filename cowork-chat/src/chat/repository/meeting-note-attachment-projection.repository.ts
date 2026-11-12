import {Injectable as injectable} from '@nestjs/common';
import {InjectModel as injectModel} from '@nestjs/mongoose';
import {Model, type mongo} from 'mongoose';
import {MeetingNoteAttachmentProjection, type MeetingNoteAttachment} from '../schema/meeting-note-attachment-projection.schema.js';
import {activeProjectionCondition, deletedProjectionCondition, projectionTimestampFields} from './versioned-projection.util.js';

export type MeetingNoteAttachmentState = {
	noteId: number;
	channelId: number;
	authorId: number;
	attachments: MeetingNoteAttachment[];
	deleted: boolean;
	occurredAt: Date;
	sourceVersion: mongo.Long;
};

@injectable()
export class MeetingNoteAttachmentProjectionRepository {
	constructor(@injectModel(MeetingNoteAttachmentProjection.name) private readonly model: Model<MeetingNoteAttachmentProjection>) {}

	async find(noteId: number, channelId: number) {
		return this.model.findOne({noteId, channelId, deleted: false}).lean();
	}

	async references(objectKey: string): Promise<boolean> {
		return (await this.model.exists({'attachments.objectKey': objectKey, deleted: false})) !== null;
	}

	async apply(state: MeetingNoteAttachmentState): Promise<void> {
		const condition = state.deleted ? deletedProjectionCondition(state.sourceVersion) : activeProjectionCondition(state.sourceVersion);
		const values = {
			channelId: state.channelId, authorId: state.authorId, attachments: state.deleted ? [] : state.attachments,
			deleted: state.deleted, sourceVersion: state.sourceVersion, sourceOccurredAt: state.occurredAt,
		};
		await this.model.updateOne({noteId: state.noteId}, [{
			$set: {
				noteId: state.noteId,
				...Object.fromEntries(Object.entries(values).map(([key, value]) => [key, {$cond: [condition, {$literal: value}, `$${key}`]}])),
				...projectionTimestampFields(condition, state.occurredAt),
			},
		}], {upsert: true, timestamps: false, updatePipeline: true});
	}
}
