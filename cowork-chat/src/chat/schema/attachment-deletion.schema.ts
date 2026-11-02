import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';
import {type Nullable} from '../../common/type/nullable.js';

/**
 메시지 첨부 객체 삭제 의도.

 메시지를 지우기 전에 기록해, 메시지가 사라진 뒤에도 지울 객체 키가 남게 한다. 워커는 메시지가
 사라진 것을 확인한 뒤에만 객체를 지우고, 모두 지우면 이 기록을 제거한다.
 */
@schema({collection: 'attachment_deletions', timestamps: true, versionKey: false})
export class AttachmentDeletion {
	createdAt!: Date;
	updatedAt!: Date;

	@prop({required: true}) messageId!: string;
	@prop({required: true}) channelId!: number;
	@prop({type: [String], required: true}) objectKeys!: string[];

	/**
    마지막 삭제 요청 시각. 메시지 삭제가 중단돼 메시지가 남은 의도를 정리하는 기준이다.
    */
	@prop({required: true}) requestedAt!: Date;

	/**
    다음 처리 가능 시각. 워커가 점유할 때 앞으로 미뤄 다른 replica와 겹치지 않게 한다.
    */
	@prop({required: true}) nextAttemptAt!: Date;

	/**
    점유 호출마다 새로 발급하는 식별자. 같은 밀리초에 점유한 두 워커를 구분한다.
    */
	@prop({type: String, default: null}) claimId!: Nullable<string>;

	@prop({default: 0}) attempts!: number;
	@prop({type: String, default: null}) lastError!: Nullable<string>;
}

export const attachmentDeletionSchema = SchemaFactory.createForClass(AttachmentDeletion)
	.index({messageId: 1}, {unique: true})
	.index({nextAttemptAt: 1});
