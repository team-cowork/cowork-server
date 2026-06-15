import { Module } from '@nestjs/common';
import { BlockRedis } from './block.redis';
import { BlockService } from './block.service';
import { BlockController } from './block.controller';

@Module({
    controllers: [BlockController],
    providers: [BlockRedis, BlockService],
    exports: [BlockService],
})
export class BlockModule {}
