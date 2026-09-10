import { Controller, Get, HttpException, HttpStatus } from '@nestjs/common';
import { InjectConnection } from '@nestjs/mongoose';
import { Connection, ConnectionStates } from 'mongoose';
import { Public } from './common/guard/public.decorator';
import { RedisRateLimiter } from './common/util/redis-rate-limiter';
import { ChatMessageProducer } from './chat/kafka/chat-message.producer';
import { ProjectionReadinessService } from './common/kafka/projection-readiness.service';
import { ElasticsearchService } from './search/elasticsearch.service';

@Public()
@Controller('health')
export class HealthController {
    constructor(
        @InjectConnection() private readonly mongoConnection: Connection,
        private readonly redisRateLimiter: RedisRateLimiter,
        private readonly chatMessageProducer: ChatMessageProducer,
        private readonly projectionReadiness: ProjectionReadinessService,
        private readonly elasticsearchService: ElasticsearchService,
    ) {}

    @Get()
    health() {
        return { status: 'UP' };
    }

    /**
     * MongoDB/Redis/Kafka 의존성 상태를 점검하는 readiness 체크.
     * 하나라도 비정상이면 503을 반환해 오케스트레이터가 해당 인스턴스로 트래픽을 보내지 않도록 한다.
     *
     * 검색 색인 준비 여부(`searchIndex`)는 readiness를 막지 않는다. Elasticsearch가 중단돼도
     * 메시지 송수신은 계속되어야 하고, 색인 의도는 아웃박스에 durable하게 쌓여 복구 후 수렴한다.
     * 대신 검색 API가 색인 미준비 상태에서 빈 결과 대신 503을 반환하고, 이 값은 지표·점검용으로만 노출한다.
     */
    @Get('ready')
    async ready() {
        const dependencies = {
            mongo: this.mongoConnection.readyState === ConnectionStates.connected,
            redis: await this.redisRateLimiter.ping(),
            kafka: this.chatMessageProducer.isReady(),
            projections: this.projectionReadiness.isReady(),
        };
        const projectionDetails = this.projectionReadiness.getDetailedStatus();
        const searchIndex = {
            ready: this.elasticsearchService.isReady(),
            lastError: this.elasticsearchService.getLastBootstrapError(),
        };

        const isReady = Object.values(dependencies).every(Boolean);
        if (!isReady) {
            throw new HttpException(
                { status: 'DOWN', dependencies, projectionDetails, searchIndex },
                HttpStatus.SERVICE_UNAVAILABLE,
            );
        }
        return { status: 'UP', dependencies, projectionDetails, searchIndex };
    }
}
