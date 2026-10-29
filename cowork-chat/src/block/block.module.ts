import {Module as nestModule} from '@nestjs/common';
import {ConfigModule} from '@nestjs/config';
import {BlockRedis} from './block.redis.js';
import {BlockService} from './block.service.js';
import {BlockController} from './block.controller.js';

@nestModule({
	imports: [ConfigModule],
	controllers: [BlockController],
	providers: [BlockRedis, BlockService],
	exports: [BlockService],
})
export class BlockModule {}
