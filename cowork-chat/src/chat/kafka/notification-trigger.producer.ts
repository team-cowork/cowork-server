import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Kafka, Producer } from 'kafkajs';
import { getRequiredCsvConfig } from '../../common/config/config.util';

/**
 * 알림 트리거 Kafka 이벤트 페이로드.
 *
 * `targetUserIds`: 채널 멤버 중 알림을 받을 후보 유저 목록.
 *   서버 측에서 조용히 설정 상태를 확인한 뒤 실제 발송 여부를 결정한다.
 * `forcedUserIds`: 알림 설정과 무관하게 반드시 알림을 받아야 하는 유저 목록
 *   (예: 멘션된 사용자).
 */
export interface NotificationTriggerEvent {
    /** 알림 종류 식별자 (예: CHAT_MESSAGE) */
    type: string;
    /** 알림 수신 후보 유저 ID 목록 */
    targetUserIds: number[];
    /** 알림 설정 무시하고 강제 수신할 유저 ID 목록 (멘션 등) */
    forcedUserIds: number[];
    data: {
        channelId: number;
        /** DM 채널 메시지는 팀에 속하지 않으므로 null */
        teamId: number | null;
        authorId: number;
        content: string;
        /** 이벤트 발생 시각 (ISO 8601, 서버 기준) */
        occurredAt: string;
    };
}

/**
 * `notification.trigger` Kafka 토픽으로 알림 이벤트를 발행하는 프로듀서.
 *
 * 모듈 초기화 시점에 브로커 연결을 비동기로 시작하며,
 * 연결이 완료되기 전에 `send`가 호출되면 연결 완료를 기다린 뒤 발행한다.
 * 이를 통해 앱 기동 시 Kafka 가용성에 의존하지 않고 지연 연결을 허용한다.
 */
@Injectable()
export class NotificationTriggerProducer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(NotificationTriggerProducer.name);
    private producer!: Producer;
    private isConnected = false;
    /**
     * 진행 중인 연결 Promise를 캐싱해 동시 다중 호출 시 연결을 중복 생성하지 않도록 한다.
     * 연결 실패 시 undefined로 초기화하여 재시도를 허용한다.
     */
    private connectPromise?: Promise<void>;

    constructor(private readonly configService: ConfigService) {}

    onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-chat-notification',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        this.producer = kafka.producer();
        void this.ensureConnected().catch((error: unknown) => {
            this.logger.error(`Notification trigger producer bootstrap connect failed: ${this.formatError(error)}`);
        });
    }

    async onModuleDestroy() {
        if (this.isConnected) {
            await this.producer.disconnect();
        }
    }

    /**
     * `notification.trigger` 토픽으로 이벤트를 발행한다.
     * 브로커에 아직 연결되지 않은 경우 연결 완료 후 발행한다.
     *
     * @param event 발행할 알림 트리거 이벤트
     * @throws 브로커 연결 또는 메시지 전송 실패 시 에러를 던진다
     */
    async send(event: NotificationTriggerEvent): Promise<void> {
        await this.ensureConnected();
        await this.producer.send({
            topic: 'notification.trigger',
            messages: [{ value: JSON.stringify(event) }],
        });
    }

    /**
     * 브로커 연결을 보장한다. 이미 연결된 경우 즉시 반환한다.
     * 동시 호출이 들어와도 단일 연결 Promise를 공유해 중복 연결을 방지한다.
     * 연결 실패 시 `connectPromise`를 초기화하여 다음 호출 시 재시도할 수 있게 한다.
     */
    private ensureConnected(): Promise<void> {
        if (this.isConnected) {
            return Promise.resolve();
        }

        if (!this.connectPromise) {
            this.connectPromise = this.producer
                .connect()
                .then(() => {
                    this.isConnected = true;
                    this.logger.log('Notification trigger producer connected');
                })
                .catch((error: unknown) => {
                    this.connectPromise = undefined;
                    throw error;
                });
        }

        return this.connectPromise;
    }

    /**
     * 알 수 없는 타입의 에러를 문자열로 변환한다.
     * KafkaJS 등 서드파티 라이브러리가 Error 인스턴스가 아닌 값을 던질 수 있어 방어적으로 처리한다.
     */
    private formatError(error: unknown): string {
        return error instanceof Error ? error.message : String(error);
    }
}
