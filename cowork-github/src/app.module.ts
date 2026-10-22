import {Module} from '@nestjs/common';
import {ConfigModule} from '@nestjs/config';
import {AppController} from './app.controller';
import {AppConfigService} from './config/app-config.service';
import {envValidationSchema} from './config/env.validation';
import {GithubModule} from './github/github.module';
import {MetricsController} from './metrics/metrics.controller';

@Module({
    imports: [
        ConfigModule.forRoot({
            isGlobal: true,
            ignoreEnvFile: true,
            validationSchema: envValidationSchema,
        }),
        GithubModule,
    ],
    providers: [AppConfigService],
    controllers: [AppController, MetricsController],
})
export class AppModule {}
