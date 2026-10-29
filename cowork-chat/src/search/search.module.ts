import {Module as nestModule} from '@nestjs/common';
import {ConfigModule} from '@nestjs/config';
import {ElasticsearchModule} from './elasticsearch.module.js';

@nestModule({
	imports: [ConfigModule, ElasticsearchModule],
	exports: [ElasticsearchModule],
})
export class SearchModule {}
