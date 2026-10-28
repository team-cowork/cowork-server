import {Module} from '@nestjs/common';
import {AppConfigService} from '../../config/app-config.service';
import {GithubAuthModule} from '../auth/github-auth.module';
import {IssueHttpModule} from '../issue-http/issue-http.module';
import {LabelHttpModule} from '../label-http/label-http.module';
import {CreateCommentHistory} from './create-comment-history';
import {IssueWriteCommandController} from './issue-write-command.controller';
import {IssueWriteResultProducer} from './issue-write-result.producer';

@Module({
    imports: [GithubAuthModule, IssueHttpModule, LabelHttpModule],
    providers: [AppConfigService, CreateCommentHistory, IssueWriteResultProducer],
    controllers: [IssueWriteCommandController],
})
export class IssueWriteModule {}
