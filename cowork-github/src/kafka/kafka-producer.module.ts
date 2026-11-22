import {Module as nestModule} from '@nestjs/common';
import {AppConfigService} from '../config/app-config.service.js';
import {KafkaProducerService} from './kafka-producer.service.js';

@nestModule({
	providers: [AppConfigService, KafkaProducerService],
	exports: [KafkaProducerService],
})
export class KafkaProducerModule {}
