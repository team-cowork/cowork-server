import {HttpModule} from '@nestjs/axios';
import {Module} from '@nestjs/common';
import {AppConfigService} from '../../config/app-config.service';
import {GithubAuthModule} from '../auth/github-auth.module';
import {LabelHttpApiClient} from './client/label-http-api.client';
import {LabelHttpService} from './label-http.service';

@Module({
    imports: [HttpModule, GithubAuthModule],
    providers: [
        AppConfigService,
        LabelHttpApiClient,
        LabelHttpService,
    ],
    exports: [LabelHttpService],
})
export class LabelHttpModule {}
