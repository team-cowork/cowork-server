import { Module } from '@nestjs/common';
import { ConfigModule, ConfigService } from '@nestjs/config';
import { Client } from '@elastic/elasticsearch';
import { ElasticsearchService } from './elasticsearch.service';
import { getRequiredConfig } from '../common/config/config.util';

export const ELASTICSEARCH_CLIENT = 'ELASTICSEARCH_CLIENT';

@Module({
    imports: [ConfigModule],
    providers: [
        {
            provide: ELASTICSEARCH_CLIENT,
            inject: [ConfigService],
            useFactory: (configService: ConfigService) =>
                new Client({ node: getRequiredConfig(configService, 'ELASTICSEARCH_URL') }),
        },
        ElasticsearchService,
    ],
    exports: [ElasticsearchService],
})
export class ElasticsearchModule {}
