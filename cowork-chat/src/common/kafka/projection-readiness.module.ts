import { Global, Module } from '@nestjs/common';
import { MongooseModule } from '@nestjs/mongoose';
import { ProjectionCheckpoint, ProjectionCheckpointSchema } from './projection-checkpoint.schema';
import { ProjectionCheckpointRepository } from './projection-checkpoint.repository';
import {
    ProjectionQuarantineRecord,
    ProjectionQuarantineRecordSchema,
} from './projection-quarantine.schema';
import { ProjectionReadinessService } from './projection-readiness.service';

@Global()
@Module({
    imports: [
        MongooseModule.forFeature([
            { name: ProjectionCheckpoint.name, schema: ProjectionCheckpointSchema },
            { name: ProjectionQuarantineRecord.name, schema: ProjectionQuarantineRecordSchema },
        ]),
    ],
    providers: [ProjectionCheckpointRepository, ProjectionReadinessService],
    exports: [ProjectionReadinessService],
})
export class ProjectionReadinessModule {}
