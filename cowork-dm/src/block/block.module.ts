import { Module } from '@nestjs/common';
import { BlockRedis } from './block.redis';
import { BlockService } from './block.service';
import { BlockController } from './block.controller';
import { BlockProducer } from '../kafka/block.producer';

@Module({
    controllers: [BlockController],
    providers: [BlockRedis, BlockService, BlockProducer],
    exports: [BlockService],
})
export class BlockModule {}
