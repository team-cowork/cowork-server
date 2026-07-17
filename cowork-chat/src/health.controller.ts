import { Controller, Get, HttpException, HttpStatus } from '@nestjs/common';
import { InjectConnection } from '@nestjs/mongoose';
import { Connection, ConnectionStates } from 'mongoose';
import { Public } from './common/guard/public.decorator';
import { RedisRateLimiter } from './common/util/redis-rate-limiter';
import { ChatMessageProducer } from './chat/kafka/chat-message.producer';

@Public()
@Controller('health')
export class HealthController {
    constructor(
        @InjectConnection() private readonly mongoConnection: Connection,
        private readonly redisRateLimiter: RedisRateLimiter,
        private readonly chatMessageProducer: ChatMessageProducer,
    ) {}

    @Get()
    health() {
        return { status: 'UP' };
    }

    /**
     * MongoDB/Redis/Kafka 의존성 상태를 점검하는 readiness 체크.
     * 하나라도 비정상이면 503을 반환해 오케스트레이터가 해당 인스턴스로 트래픽을 보내지 않도록 한다.
     */
    @Get('ready')
    async ready() {
        const dependencies = {
            mongo: this.mongoConnection.readyState === ConnectionStates.connected,
            redis: await this.redisRateLimiter.ping(),
            kafka: this.chatMessageProducer.isReady(),
        };

        const isReady = Object.values(dependencies).every(Boolean);
        if (!isReady) {
            throw new HttpException({ status: 'DOWN', dependencies }, HttpStatus.SERVICE_UNAVAILABLE);
        }
        return { status: 'UP', dependencies };
    }
}
