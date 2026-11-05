import {ApiProperty as apiProperty, ApiPropertyOptional as apiPropertyOptional} from '@nestjs/swagger';
import {type Nullable} from '../../type/nullable.js';

const modes = ['BOOTSTRAP', 'INCREMENTAL', 'REBUILD'];
const statuses = ['INITIALIZING', 'ACTIVE', 'REBUILD_REQUESTED', 'RESETTING', 'REBUILDING', 'REBUILD_REQUIRED'];

class ProjectionPartitionStatusDto {
	@apiProperty() partition!: number;
	@apiPropertyOptional() startOffset?: string;
	@apiPropertyOptional() currentOffset?: string;
	@apiProperty() targetOffset!: string;
	@apiProperty() lowOffset!: string;
	@apiPropertyOptional() lag?: string;
}

export class ProjectionStreamStatusDto {
	@apiProperty() ready!: boolean;
	@apiProperty({enum: modes}) mode!: string;
	@apiProperty({enum: statuses}) status!: string;
	@apiProperty() datasetGeneration!: string;
	@apiProperty() sourceGeneration!: string;
	@apiPropertyOptional() reason?: string;
	@apiProperty({type: [ProjectionPartitionStatusDto]}) partitions!: ProjectionPartitionStatusDto[];
}

class ProjectionPartitionOffsetDto {
	@apiProperty() partition!: number;
	@apiProperty() offset!: string;
}

export class ProjectionDatasetDto {
	@apiProperty() stream!: string;
	@apiProperty() groupId!: string;
	@apiProperty() topic!: string;
	@apiProperty() sourceGeneration!: string;
	@apiProperty() datasetGeneration!: string;
	@apiProperty({enum: modes}) mode!: string;
	@apiProperty({enum: statuses}) status!: string;
	@apiPropertyOptional() reason?: string;
	@apiPropertyOptional({format: 'date-time'}) rebuildRequestedAt?: string;
	@apiPropertyOptional({format: 'date-time'}) activatedAt?: string;
	@apiProperty() activationDocumentCount!: number;
	@apiProperty({type: Number, nullable: true}) lastObservedDocumentCount!: Nullable<number>;
	@apiProperty({type: [ProjectionPartitionOffsetDto]}) baseOffsets!: ProjectionPartitionOffsetDto[];
	@apiProperty({type: [ProjectionPartitionOffsetDto]}) targetOffsets!: ProjectionPartitionOffsetDto[];
}
