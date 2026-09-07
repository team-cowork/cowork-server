import 'dotenv/config';
import mongoose from 'mongoose';
import { Client } from '@elastic/elasticsearch';
import { Message, MessageSchema } from '../chat/schema/message.schema';
import {
    MessageSearchTombstone,
    MessageSearchTombstoneSchema,
} from '../chat/schema/message-search-tombstone.schema';
import {
    MessageSearchIndexState,
    MessageSearchIndexStateSchema,
} from '../chat/schema/message-search-index-state.schema';
import { MessageSearchIndexRepository } from '../chat/repository/message-search-index.repository';
import { MessageSearchTombstoneRepository } from '../chat/repository/message-search-tombstone.repository';
import { MessageSearchIndexStateRepository } from '../chat/repository/message-search-index-state.repository';
import { MessageSearchRebuilder } from '../chat/search/message-search-rebuilder';
import { ElasticsearchService } from '../search/elasticsearch.service';

const COMMANDS = ['status', 'retry-failed', 'rebuild', 'resume-rebuild'] as const;
type Command = typeof COMMANDS[number];

/* eslint-disable no-console -- 이 CLI의 목적은 운영자에게 색인 상태와 명령 결과를 출력하는 것이다. */

function model<T>(name: string, schema: mongoose.Schema): mongoose.Model<T> {
    return (mongoose.models[name] ?? mongoose.model(name, schema)) as mongoose.Model<T>;
}

async function main(): Promise<void> {
    const [command] = process.argv.slice(2) as [Command | undefined];
    const uri = process.env.MONGODB_URI;
    const node = process.env.ELASTICSEARCH_URL;

    if (!uri) throw new Error('MONGODB_URI is required');
    if (!node) throw new Error('ELASTICSEARCH_URL is required');
    if (!command || !COMMANDS.includes(command)) {
        throw new Error(`usage: message-search-index <${COMMANDS.join('|')}>`);
    }

    await mongoose.connect(uri);
    const indexRepository = new MessageSearchIndexRepository(model<Message>(Message.name, MessageSchema));
    const tombstoneRepository = new MessageSearchTombstoneRepository(
        model<MessageSearchTombstone>(MessageSearchTombstone.name, MessageSearchTombstoneSchema),
    );
    const stateRepository = new MessageSearchIndexStateRepository(
        model<MessageSearchIndexState>(MessageSearchIndexState.name, MessageSearchIndexStateSchema),
    );
    const elasticsearchService = new ElasticsearchService(new Client({ node }));
    await elasticsearchService.ensureIndexReady();

    if (command === 'status') {
        await printStatus(indexRepository, tombstoneRepository, stateRepository, elasticsearchService);
        return;
    }
    if (command === 'retry-failed') {
        const [messages, tombstones] = await Promise.all([
            indexRepository.retryFailed(),
            tombstoneRepository.retryFailed(),
        ]);
        console.log(`requeued messages=${messages} tombstones=${tombstones}`);
        return;
    }

    const rebuilder = new MessageSearchRebuilder(elasticsearchService, indexRepository, tombstoneRepository, stateRepository);
    const result = await rebuilder.rebuild({ resume: command === 'resume-rebuild' });
    console.table([result]);
}

async function printStatus(
    indexRepository: MessageSearchIndexRepository,
    tombstoneRepository: MessageSearchTombstoneRepository,
    stateRepository: MessageSearchIndexStateRepository,
    elasticsearchService: ElasticsearchService,
): Promise<void> {
    const [backlog, tombstones, state, aliasTargets, expected] = await Promise.all([
        indexRepository.backlog(),
        tombstoneRepository.countByStatus(),
        stateRepository.get(),
        elasticsearchService.getAliasTargets(),
        indexRepository.countIndexScope(),
    ]);

    console.log('# 메시지 색인 아웃박스');
    console.table(backlog.counts);
    console.log('# 삭제 tombstone');
    console.table(tombstones);
    console.table([{
        aliasTargets: aliasTargets.join(', ') || '(alias 없음)',
        indexScopeMessages: expected,
        oldestPendingAt: backlog.oldestPendingAt?.toISOString() ?? '-',
        lastRebuildSucceededAt: state?.lastRebuildSucceededAt?.toISOString() ?? '-',
        lastRebuildFailedAt: state?.lastRebuildFailedAt?.toISOString() ?? '-',
        lastRebuildIndex: state?.lastRebuildIndex ?? '-',
        lastRebuildError: state?.lastRebuildError ?? '-',
    }]);
}

void main()
    .catch((error: unknown) => {
        console.error(error instanceof Error ? error.message : error);
        process.exitCode = 1;
    })
    .finally(() => mongoose.disconnect());
