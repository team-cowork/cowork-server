import {Injectable} from '@nestjs/common';
import {IssueCommentStateProducer} from './issue-comment-state.producer';
import {IssueStateProducer} from './issue-state.producer';
import {LabelStateProducer} from './label-state.producer';
import {PullRequestStateProducer} from './pull-request-state.producer';
import {RepoStateProducer} from './repo-state.producer';

/**
 Repo/issue/PR/라벨/댓글 read-model state producer 5개를 한데 묶는다 — webhook 컨트롤러와
 backfill 서비스 둘 다 이 5개를 전부 쓰므로, 묶어서 주입받으면 생성자 파라미터가 줄어든다.
 */
@Injectable()
export class GithubReadModelEventProducers {
    // eslint-disable-next-line max-params -- NestJS 생성자 주입이라 의존성 수만큼 파라미터가 필요하다.
    constructor(
        readonly repo: RepoStateProducer,
        readonly issue: IssueStateProducer,
        readonly pullRequest: PullRequestStateProducer,
        readonly label: LabelStateProducer,
        readonly issueComment: IssueCommentStateProducer,
    ) {}
}
