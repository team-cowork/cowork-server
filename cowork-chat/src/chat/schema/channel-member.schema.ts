import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';
import { HydratedDocument, Types } from 'mongoose';

/** Mongoose 문서 타입. HydratedDocument로 래핑되어 _id, save() 등 Mongoose 메서드를 포함합니다. */
export type ChannelMemberDocument = HydratedDocument<ChannelMember>;

/**
 * 채널 멤버십을 나타내는 MongoDB 도큐먼트 스키마.
 *
 * - `timestamps: true` 옵션으로 `createdAt`, `updatedAt` 필드가 자동 관리됩니다.
 * - `versionKey: false` 옵션으로 Mongoose 기본 `__v` 버전 필드를 비활성화합니다.
 * - `(channelId, userId)` 복합 유니크 인덱스로 동일 사용자의 중복 가입을 DB 수준에서 방지합니다.
 */
@Schema({ timestamps: true, versionKey: false })
export class ChannelMember {
    /** 멤버가 속한 채널의 식별자 */
    @Prop({ required: true }) channelId!: number;

    /** 채널이 속한 팀의 식별자. DM 채널은 팀에 속하지 않으므로 `null`. */
    @Prop({ type: Number, default: null }) teamId!: number | null;

    /** 채널 타입 (TEXT, VOICE, DM). channel 모듈의 멤버십 이벤트로 동기화된다. */
    @Prop({ default: 'TEXT' }) channelType!: string;

    /**
     * DM 대화 숨김 여부 (Discord의 "닫기").
     * 목록에서만 제외되며, 상대방 메시지 수신 시 자동으로 `false`로 복구된다.
     */
    @Prop({ default: false }) isHidden!: boolean;

    /** 채널에 가입된 사용자의 식별자 */
    @Prop({ required: true }) userId!: number;

    /**
     * 채널 내 역할.
     * 기본값은 `'MEMBER'`이며, 추후 `'OWNER'`, `'ADMIN'` 등으로 확장 가능합니다.
     */
    @Prop({ default: 'MEMBER' }) role!: string;

    /** 이 채널에서 마지막으로 읽은 메시지의 ObjectId. 한 번도 읽지 않은 경우 `null`. */
    @Prop({ type: Types.ObjectId, default: null }) lastReadMessageId!: Types.ObjectId | null;
}

/** {@link ChannelMember} 클래스로부터 생성된 Mongoose 스키마 인스턴스 */
export const ChannelMemberSchema = SchemaFactory.createForClass(ChannelMember);

/**
 * `(channelId, userId)` 복합 유니크 인덱스.
 * 동일 사용자가 하나의 채널에 두 번 이상 등록되는 것을 방지합니다.
 */
ChannelMemberSchema.index({ channelId: 1, userId: 1 }, { unique: true });

/**
 * 사용자 단위·팀 단위 멤버십 조회를 함께 커버하는 복합 인덱스 (`userId`, `teamId`).
 * - `userId` 단독 조회(`findChannelIdsByUser`, `findDmMemberships`)는 선두 필드(prefix)로 처리된다.
 * - `{ teamId, userId }` 조회(`existsByTeam`, `findMembersByTeam`)는 두 필드가 모두 동등 조건이므로
 *   필드 순서와 무관하게 이 인덱스를 사용한다.
 * 단일 `{ userId }` 인덱스를 별도로 두지 않아 쓰기·저장 비용을 절감한다.
 */
ChannelMemberSchema.index({ userId: 1, teamId: 1 });
