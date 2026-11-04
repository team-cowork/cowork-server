import {Inject as inject, Injectable as injectable} from '@nestjs/common';
import {IssueCommentStateProducer} from './issue-comment-state.producer.js';
import {IssueStateProducer} from './issue-state.producer.js';
import {LabelStateProducer} from './label-state.producer.js';
import {PullRequestStateProducer} from './pull-request-state.producer.js';
import {RepoStateProducer} from './repo-state.producer.js';

/**
 Repo/issue/PR/라벨/댓글 read-model state producer 5개를 한데 묶는다 — webhook 컨트롤러와
 backfill 서비스 둘 다 이 5개를 전부 쓰므로, 묶어서 주입받으면 생성자 파라미터가 줄어든다.
 */
@injectable()
export class GithubReadModelEventProducers {
	@inject(RepoStateProducer)
	readonly repo!: RepoStateProducer;

	@inject(IssueStateProducer)
	readonly issue!: IssueStateProducer;

	@inject(PullRequestStateProducer)
	readonly pullRequest!: PullRequestStateProducer;

	@inject(LabelStateProducer)
	readonly label!: LabelStateProducer;

	@inject(IssueCommentStateProducer)
	readonly issueComment!: IssueCommentStateProducer;
}
