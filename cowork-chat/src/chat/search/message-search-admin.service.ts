import {Injectable} from '@nestjs/common';
import {ElasticsearchService} from '../../search/elasticsearch.service';
import {MessageSearchIndexRepository} from '../repository/message-search-index.repository';
import {MessageSearchIndexStateRepository} from '../repository/message-search-index-state.repository';
import {MessageSearchTombstoneRepository} from '../repository/message-search-tombstone.repository';
import {MessageSearchRebuilder} from './message-search-rebuilder';

@Injectable()
export class MessageSearchAdminService {
    constructor(
        private readonly elasticsearchService: ElasticsearchService,
        private readonly indexRepository: MessageSearchIndexRepository,
        private readonly tombstoneRepository: MessageSearchTombstoneRepository,
        private readonly stateRepository: MessageSearchIndexStateRepository,
        private readonly rebuilder: MessageSearchRebuilder,
    ) {}

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
            rebuildRunning: state?.rebuildLockedAt != null,
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
