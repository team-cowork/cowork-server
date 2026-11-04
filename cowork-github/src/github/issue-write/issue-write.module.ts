import {Module as nestModule} from '@nestjs/common';
import {AppConfigService} from '../../config/app-config.service.js';
import {GithubAuthModule} from '../auth/github-auth.module.js';
import {IssueHttpModule} from '../issue-http/issue-http.module.js';
import {LabelHttpModule} from '../label-http/label-http.module.js';
import {CreateCommentHistory} from './create-comment-history.js';
import {IssueWriteCommandController} from './issue-write-command.controller.js';
import {IssueWriteResultProducer} from './issue-write-result.producer.js';

@nestModule({
	imports: [GithubAuthModule, IssueHttpModule, LabelHttpModule],
	providers: [AppConfigService, CreateCommentHistory, IssueWriteResultProducer],
	controllers: [IssueWriteCommandController],
})
export class IssueWriteModule {}
