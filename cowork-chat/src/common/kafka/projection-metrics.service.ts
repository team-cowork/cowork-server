import { Injectable } from '@nestjs/common';
import { Counter, Histogram, register } from 'prom-client';
import { ProjectionRunMode } from './projection-dataset.schema';

@Injectable()
export class ProjectionMetricsService {
    private readonly replayRecords: Counter<'stream' | 'mode'>;
    private readonly catchupSeconds: Histogram<'stream' | 'mode'>;
    private readonly rebuilds: Counter<'stream' | 'result'>;

    constructor() {
        this.replayRecords = this.counter(
            'cowork_chat_projection_replay_records_total',
            'Projection records applied by stream and run mode.',
            ['stream', 'mode'],
        );
        this.catchupSeconds = this.histogram(
            'cowork_chat_projection_catchup_duration_seconds',
            'Projection catch-up duration by stream and run mode.',
            ['stream', 'mode'],
        );
        this.rebuilds = this.counter(
            'cowork_chat_projection_rebuild_total',
            'Projection rebuild requests and outcomes.',
            ['stream', 'result'],
        );
    }

    recordReplay(stream: string, mode: ProjectionRunMode): void {
        this.replayRecords.inc({ stream, mode });
    }

    recordCatchup(stream: string, mode: ProjectionRunMode, seconds: number): void {
        this.catchupSeconds.observe({ stream, mode }, seconds);
    }

    recordRebuild(stream: string, result: 'requested' | 'completed' | 'failed'): void {
        this.rebuilds.inc({ stream, result });
    }

    private counter<L extends string>(name: string, help: string, labelNames: L[]): Counter<L> {
        return (register.getSingleMetric(name) as Counter<L> | undefined)
            ?? new Counter({ name, help, labelNames });
    }

    private histogram<L extends string>(name: string, help: string, labelNames: L[]): Histogram<L> {
        return (register.getSingleMetric(name) as Histogram<L> | undefined)
            ?? new Histogram({ name, help, labelNames });
    }
}
