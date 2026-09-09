import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';

/** 상태 도큐먼트는 하나만 존재한다. 모든 replica가 같은 `_id`를 upsert한다. */
export const MESSAGE_SEARCH_INDEX_STATE_ID = 'message-search-index';

/**
 * 검색 색인 운영 상태.
 *
 * 마지막 전체 재구축 결과와 레거시 백필 완료 여부를 replica 간에 공유해,
 * 지표 노출과 운영자 판단(재시도 또는 전체 재구축)의 근거로 사용한다.
 */
@Schema({ timestamps: true, versionKey: false, collection: 'message_search_index_state' })
export class MessageSearchIndexState {
    @Prop({ type: String, default: MESSAGE_SEARCH_INDEX_STATE_ID }) _id!: string;

    @Prop({ type: Date, default: null }) lastRebuildStartedAt!: Date | null;
    @Prop({ type: Date, default: null }) lastRebuildSucceededAt!: Date | null;
    @Prop({ type: Date, default: null }) lastRebuildFailedAt!: Date | null;
    @Prop({ type: String, default: null }) lastRebuildIndex!: string | null;
    @Prop({ type: String, default: null }) lastRebuildError!: string | null;
    @Prop({ type: Number, default: null }) lastRebuildDocumentCount!: number | null;

    /** 재구축 snapshot scan이 마지막으로 처리한 `_id`. 중단 지점부터 재개할 때 사용한다. */
    @Prop({ type: String, default: null }) lastRebuildScanCursor!: string | null;

    /** 레거시 도큐먼트 백필이 끝난 시각. 완료 후에는 백필 스캔을 다시 돌리지 않는다. */
    @Prop({ type: Date, default: null }) legacyBackfillCompletedAt!: Date | null;

    /**
     * 진행 중인 재구축의 replica 간 락. `rebuildLockedAt`이 최근이면 다른 replica의 `rebuild`가
     * 이미 실행 중인 것으로 보고 새 실행을 거부한다. 락을 쥔 프로세스가 죽어도 오래돼 있으면
     * `rebuildLockId`와 무관하게 다시 점유할 수 있어 영구 교착으로 남지 않는다.
     */
    @Prop({ type: Date, default: null }) rebuildLockedAt!: Date | null;
    @Prop({ type: String, default: null }) rebuildLockId!: string | null;
}

export const MessageSearchIndexStateSchema = SchemaFactory.createForClass(MessageSearchIndexState);
