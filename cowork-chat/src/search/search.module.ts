import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { ElasticsearchModule } from './elasticsearch.module';

@Module({
    imports: [ConfigModule, ElasticsearchModule],
    exports: [ElasticsearchModule],
})
export class SearchModule {}
