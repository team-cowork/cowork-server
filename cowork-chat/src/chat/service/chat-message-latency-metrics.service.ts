// @todo 2026-11-18 KST 추가한 임시 지연 측정 코드다. 2026-12-02까지 제거한다. 이 파일 전체가 제거 대상이다.
import {Injectable as injectable} from '@nestjs/common';
import {Histogram, register} from 'prom-client';

/**
 메시지 전송 지연을 나누어 재는 구간이다.

 - `accept`: 전송 요청 처리 시작부터 Kafka 발행이 반환될 때까지
 - `queue`: 이벤트 `occurredAt`부터 consumer가 record를 받을 때까지
 - `validate_save`: consumer의 범위 재검증과 MongoDB 저장
 - `emit`: 소켓 전송
 - `total`: 이벤트 `occurredAt`부터 소켓 전송이 끝날 때까지
 */
export type ChatMessageLatencyStage = 'accept' | 'queue' | 'validate_save' | 'emit' | 'total';

/**
 채팅 지연은 수 밀리초부터 수십 초까지 퍼지므로 그 범위를 덮는 버킷을 쓴다. 단위는 초다.
 */
const LATENCY_BUCKETS_SECONDS = [0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60];

/**
 두 시각(밀리초) 사이의 경과 시간을 초로 바꾼다.

 `occurredAt`은 다른 인스턴스의 시계로 찍히므로 시계 차이로 끝 시각이 앞설 수 있다. 그때는 0으로 본다.

 @returns 경과 시간(초). 음수나 숫자가 아니면 0
 */
export function elapsedSeconds(startMs: number, endMs: number): number {
	const elapsedMs = endMs - startMs;
	return Number.isFinite(elapsedMs) && elapsedMs > 0 ? elapsedMs / 1000 : 0;
}

/**
 메시지 접수부터 브로드캐스트까지 구간별 지연을 Prometheus histogram으로 남긴다.
 */
@injectable()
export class ChatMessageLatencyMetricsService {
	private readonly latency: Histogram<'stage'>;

	constructor() {
		const name = 'cowork_chat_message_latency_seconds';
		this.latency = (register.getSingleMetric(name) as Histogram<'stage'> | undefined)
			?? new Histogram({
				name,
				help: 'Chat message latency from send acceptance to socket broadcast, by stage.',
				labelNames: ['stage'],
				buckets: LATENCY_BUCKETS_SECONDS,
			});
	}

	record(stage: ChatMessageLatencyStage, startMs: number, endMs: number): void {
		this.latency.observe({stage}, elapsedSeconds(startMs, endMs));
	}
}
