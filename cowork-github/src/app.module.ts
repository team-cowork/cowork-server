import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { AppController } from './app.controller';
import { AppConfigService } from './config/app-config.service';
import { envValidationSchema } from './config/env.validation';
import { GithubModule } from './github/github.module';

@Module({
  imports: [
    ConfigModule.forRoot({
      isGlobal: true,
      validationSchema: envValidationSchema,
    }),
    GithubModule,
  ],
  providers: [AppConfigService],
  controllers: [AppController],
})
export class AppModule {}
