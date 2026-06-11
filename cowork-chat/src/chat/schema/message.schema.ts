import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';
import { HydratedDocument, Types } from 'mongoose';

/** Mongoose 문서 타입. HydratedDocument로 래핑되어 _id, save() 등 Mongoose 메서드를 포함합니다. */
export type MessageDocument = HydratedDocument<Message>;

/**
 * 메시지에 첨부된 파일 정보를 나타내는 서브도큐먼트 스키마.
 *
 * `_id: false` 옵션으로 서브도큐먼트 자체에는 `_id`가 생성되지 않습니다.
 */
@Schema({ _id: false })
class Attachment {
    /** 첨부 파일의 원본 파일명 */
    @Prop({ required: true }) name!: string;

    /** 파일에 접근 가능한 URL (오브젝트 스토리지 경로 등) */
    @Prop({ required: true }) url!: string;

    /** 파일 크기 (바이트 단위) */
    @Prop({ required: true }) size!: number;

    /** 파일의 MIME 타입 (예: `image/png`, `application/pdf`) */
    @Prop({ required: true }) mimeType!: string;
}

/**
 * 메시지 편집 이력을 나타내는 서브도큐먼트 스키마.
 *
 * `_id: false` 옵션으로 서브도큐먼트 자체에는 `_id`가 생성되지 않습니다.
 * 편집 시 기존 내용을 이 배열에 추가하여 변경 이력을 보존합니다.
 */
@Schema({ _id: false })
class EditHistory {
    /** 편집 이전의 메시지 내용 */
    @Prop({ required: true }) content!: string;

    /** 편집이 발생한 시각 */
    @Prop({ required: true }) editedAt!: Date;
}

/**
 * 이모지 반응을 나타내는 서브도큐먼트 스키마.
 *
 * `_id: false` 옵션으로 서브도큐먼트 자체에는 `_id`가 생성되지 않습니다.
 * 동일 이모지에 대해 반응한 사용자 ID를 배열로 관리하며,
 * 유저 제거 후 `userIds`가 빈 배열이 되면 해당 항목 자체가 삭제됩니다.
 */
@Schema({ _id: false })
class Reaction {
    /** 이모지 문자열 (예: `👍`, `❤️`) */
    @Prop({ required: true }) emoji!: string;

    /** 해당 이모지로 반응한 사용자 ID 목록 */
    @Prop({ type: [Number], default: [] }) userIds!: number[];
}

/**
 * 채팅 메시지를 나타내는 MongoDB 도큐먼트 스키마.
 *
 * - `timestamps: true` 옵션으로 `createdAt`, `updatedAt` 필드가 자동 관리됩니다.
 * - `versionKey: false` 옵션으로 Mongoose 기본 `__v` 버전 필드를 비활성화합니다.
 * - `type` 필드로 일반 텍스트(`TEXT`), 파일 첨부(`FILE`), 시스템 메시지(`SYSTEM`)를 구분합니다.
 * - `clientMessageId`는 클라이언트가 생성한 멱등성 키로, sparse 유니크 인덱스가 적용되어
 *   있어 `null`/`undefined`인 도큐먼트는 중복 검사에서 제외됩니다.
 * - `notificationStatus`는 아웃박스(outbox) 패턴으로 알림 발송을 추적합니다.
 */
@Schema({ timestamps: true, versionKey: false })
export class Message {
    /** Mongoose `timestamps` 옵션에 의해 자동 설정되는 도큐먼트 생성 시각 */
    createdAt!: Date;

    /** Mongoose `timestamps` 옵션에 의해 자동 설정되는 도큐먼트 최종 수정 시각 */
    updatedAt!: Date;

    /** 메시지가 속한 팀의 식별자. DM 채널 메시지는 팀에 속하지 않으므로 `null`. */
    @Prop({ type: Number, default: null }) teamId!: number | null;

    /**
     * 메시지가 속한 프로젝트의 식별자.
     * 프로젝트와 무관한 채널의 경우 `null`입니다.
     */
    @Prop({ type: Number, default: null }) projectId!: number | null;

    /** 메시지가 게시된 채널의 식별자 */
    @Prop({ required: true }) channelId!: number;

    /** 메시지를 작성한 사용자의 식별자 */
    @Prop({ required: true }) authorId!: number;

    /**
     * 메시지 본문. 최대 25,000자까지 허용됩니다.
     * `FILE` 타입 메시지의 경우 파일 설명 텍스트가 저장됩니다.
     */
    @Prop({ required: true, maxlength: 25000 }) content!: string;

    /**
     * 메시지 유형.
     * - `TEXT`: 일반 텍스트 메시지
     * - `FILE`: 파일 첨부 메시지
     * - `SYSTEM`: 채널 입퇴장 등 시스템이 생성하는 메시지
     */
    @Prop({ enum: ['TEXT', 'FILE', 'SYSTEM'], default: 'TEXT' }) type!: string;

    /**
     * 첨부 파일 목록. `type`이 `FILE`인 경우에만 값이 채워집니다.
     * `attachments.0` 존재 여부로 실제 첨부가 있는 메시지를 필터링합니다.
     */
    @Prop({ type: [Attachment], default: [] }) attachments!: Attachment[];

    /**
     * 스레드(답글)의 부모 메시지 ObjectId.
     * 최상위 메시지이거나 스레드가 아닌 경우 `null`입니다.
     * `$lookup` 집계로 부모 메시지 정보를 `mentionedMessage` 필드에 조인합니다.
     */
    @Prop({ type: Types.ObjectId, default: null }) parentMessageId!: Types.ObjectId | null;

    /**
     * 메시지가 편집된 적 있는지 여부.
     * 클라이언트에서 편집 표시(예: `(수정됨)`) 렌더링에 사용합니다.
     */
    @Prop({ default: false }) isEdited!: boolean;

    /** 이전 편집 내용의 이력 목록. 편집 시 기존 내용이 앞에 추가됩니다. */
    @Prop({ type: [EditHistory], default: [] }) editHistory!: EditHistory[];

    /**
     * 메시지 고정 여부.
     * `(isPinned, channelId)` 복합 인덱스로 채널별 고정 메시지를 빠르게 조회합니다.
     */
    @Prop({ default: false }) isPinned!: boolean;

    /** 이모지 반응 목록. 각 항목은 이모지 문자열과 반응한 사용자 ID 배열을 포함합니다. */
    @Prop({ type: [Reaction], default: [] }) reactions!: Array<{ emoji: string; userIds: number[] }>;

    /**
     * 클라이언트가 생성한 멱등성 키.
     * 네트워크 재시도 등으로 인한 메시지 중복 생성을 방지하기 위해 사용합니다.
     * sparse 유니크 인덱스가 적용되어 값이 없는 도큐먼트는 중복 검사 대상에서 제외됩니다.
     */
    @Prop({ type: String }) clientMessageId?: string | null;

    /**
     * 메시지에서 멘션된 사용자 ID 목록.
     * `@사용자명` 형식으로 언급된 사용자를 빠르게 조회하기 위한 배열 인덱스가 적용됩니다.
     */
    @Prop({ type: [Number], default: [] }) mentions!: number[];

    /**
     * 알림 발송 상태. 아웃박스(outbox) 패턴으로 알림 파이프라인을 추적합니다.
     * - `PENDING`: 발송 대기 중
     * - `PROCESSING`: 발송 처리 중 (워커가 원자적으로 전환)
     * - `SENT`: 발송 완료
     * - `FAILED`: 재시도 한도 초과로 발송 실패
     */
    @Prop({ enum: ['PENDING', 'PROCESSING', 'SENT', 'FAILED'], default: 'PENDING' }) notificationStatus!: string;

    /**
     * 알림 발송 재시도 횟수. 발송 실패 시마다 증가하며,
     * 한도 초과 시 `notificationStatus`가 `FAILED`로 전환됩니다.
     */
    @Prop({ default: 0 }) notificationRetryCount!: number;
}

/** {@link Message} 클래스로부터 생성된 Mongoose 스키마 인스턴스 */
export const MessageSchema = SchemaFactory.createForClass(Message);

/** 채널별 최신순 메시지 조회를 위한 복합 인덱스 (`channelId` 오름차순, `_id` 내림차순) */
MessageSchema.index({ channelId: 1, _id: -1 });

/** 특정 작성자의 메시지를 빠르게 조회하기 위한 단일 필드 인덱스 */
MessageSchema.index({ authorId: 1 });

/** 스레드 답글 조회 시 부모 메시지 기준으로 빠르게 필터링하기 위한 인덱스 */
MessageSchema.index({ parentMessageId: 1 });

/** 채널 내 스레드 답글 목록 조회를 위한 복합 인덱스 (`channelId`, `parentMessageId`, `_id` 내림차순) */
MessageSchema.index({ channelId: 1, parentMessageId: 1, _id: -1 });

/** 채널별 고정 메시지 목록 조회를 위한 복합 인덱스 */
MessageSchema.index({ isPinned: 1, channelId: 1 });

/**
 * `clientMessageId` 유니크 인덱스.
 * `sparse: true`로 값이 없는(`null`/`undefined`) 도큐먼트는 중복 검사에서 제외합니다.
 */
MessageSchema.index({ clientMessageId: 1 }, { unique: true, sparse: true });

/** 멘션된 사용자 ID 기준 조회를 위한 배열 요소 인덱스 */
MessageSchema.index({ mentions: 1 });

/**
 * 아웃박스 워커가 PENDING 상태 메시지를 발생 시간 순으로 처리하기 위한 복합 인덱스.
 * `(notificationStatus, createdAt)` 순으로 정렬하여 오래된 메시지부터 처리합니다.
 */
MessageSchema.index({ notificationStatus: 1, createdAt: 1 });
