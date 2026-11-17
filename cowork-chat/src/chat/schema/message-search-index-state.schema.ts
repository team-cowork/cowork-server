import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';
import {type Nullable} from '../../common/type/nullable.js';

/**
상태 도큐먼트는 하나만 존재한다. 모든 기록이 같은 `_id`를 upsert한다.
*/
export const MESSAGE_SEARCH_INDEX_STATE_ID = 'message-search-index';

/**
 검색 색인 운영 상태.

 마지막 전체 재구축 결과를 프로세스 재시작 뒤에도 남겨,
 지표 노출과 운영자 판단(재시도 또는 전체 재구축)의 근거로 사용한다.
 */
@schema({timestamps: true, versionKey: false, collection: 'message_search_index_state'})
export class MessageSearchIndexState {
	@prop({type: String, default: MESSAGE_SEARCH_INDEX_STATE_ID}) _id!: string;

	@prop({type: Date, default: null}) lastRebuildStartedAt!: Nullable<Date>;
	@prop({type: Date, default: null}) lastRebuildSucceededAt!: Nullable<Date>;
	@prop({type: Date, default: null}) lastRebuildFailedAt!: Nullable<Date>;
	@prop({type: String, default: null}) lastRebuildIndex!: Nullable<string>;
	@prop({type: String, default: null}) lastRebuildError!: Nullable<string>;
	@prop({type: Number, default: null}) lastRebuildDocumentCount!: Nullable<number>;

	/**
    재구축 snapshot scan이 마지막으로 처리한 `_id`. 중단 지점부터 재개할 때 사용한다.
    */
	@prop({type: String, default: null}) lastRebuildScanCursor!: Nullable<string>;
}

export const messageSearchIndexStateSchema = SchemaFactory.createForClass(MessageSearchIndexState);
