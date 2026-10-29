import {Injectable as injectable, Inject as inject} from '@nestjs/common';
import {ElasticsearchService} from '../../search/elasticsearch.service.js';
import {MessageSearchIndexRepository} from '../repository/message-search-index.repository.js';
import {MessageSearchIndexStateRepository} from '../repository/message-search-index-state.repository.js';
import {MessageSearchTombstoneRepository} from '../repository/message-search-tombstone.repository.js';
import {MessageSearchRebuilder} from './message-search-rebuilder.js';

@injectable()
export class MessageSearchAdminService {
	@inject()
	private readonly elasticsearchService!: ElasticsearchService;

	@inject()
	private readonly indexRepository!: MessageSearchIndexRepository;

	@inject()
	private readonly tombstoneRepository!: MessageSearchTombstoneRepository;

	@inject()
	private readonly stateRepository!: MessageSearchIndexStateRepository;

	@inject()
	private readonly rebuilder!: MessageSearchRebuilder;

	async status() {
		const [backlog, tombstones, state, aliasTargets, indexScopeMessages] = await Promise.all([
			this.indexRepository.backlog(),
			this.tombstoneRepository.countByStatus(),
			this.stateRepository.get(),
			this.elasticsearchService.getAliasTargets(),
			this.indexRepository.countIndexScope(),
		]);

		return {
			messages: backlog.counts,
			tombstones,
			aliasTargets,
			indexScopeMessages,
			oldestPendingAt: backlog.oldestPendingAt,
			rebuildRunning: state?.rebuildLockedAt !== null && state?.rebuildLockedAt !== undefined,
			lastRebuildStartedAt: state?.lastRebuildStartedAt ?? null,
			lastRebuildSucceededAt: state?.lastRebuildSucceededAt ?? null,
			lastRebuildFailedAt: state?.lastRebuildFailedAt ?? null,
			lastRebuildIndex: state?.lastRebuildIndex ?? null,
			lastRebuildError: state?.lastRebuildError ?? null,
		};
	}

	async retryFailed(): Promise<{messages: number; tombstones: number}> {
		const [messages, tombstones] = await Promise.all([
			this.indexRepository.retryFailed(),
			this.tombstoneRepository.retryFailed(),
		]);
		return {messages, tombstones};
	}

	async startRebuild(isResume: boolean): Promise<boolean> {
		return this.rebuilder.start({resume: isResume});
	}
}
