import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { BlockRedis } from './block.redis';
import { BlockService } from './block.service';
import { BlockController } from './block.controller';

@Module({
    imports: [ConfigModule],
    controllers: [BlockController],
    providers: [BlockRedis, BlockService],
    exports: [BlockService],
})
export class BlockModule {}
