import {HttpModule} from '@nestjs/axios';
import {Module} from '@nestjs/common';
import {AppConfigService} from '../../config/app-config.service';
import {GithubAuthModule} from '../auth/github-auth.module';
import {IssueHttpApiClient} from './client/issue-http-api.client';
import {IssueHttpService} from './issue-http.service';

@Module({
    imports: [HttpModule, GithubAuthModule],
    providers: [
        AppConfigService,
        IssueHttpApiClient,
        IssueHttpService,
    ],
    exports: [IssueHttpService],
})
export class IssueHttpModule {}
