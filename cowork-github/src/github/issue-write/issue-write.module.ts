import { Module } from '@nestjs/common';
import { AppConfigService } from '../../config/app-config.service';
import { IssueHttpModule } from '../issue-http/issue-http.module';
import { LabelHttpModule } from '../label-http/label-http.module';
import { IssueWriteCommandController } from './issue-write-command.controller';
import { IssueWriteResultProducer } from './issue-write-result.producer';

@Module({
  imports: [IssueHttpModule, LabelHttpModule],
  providers: [AppConfigService, IssueWriteResultProducer],
  controllers: [IssueWriteCommandController],
})
export class IssueWriteModule {}
