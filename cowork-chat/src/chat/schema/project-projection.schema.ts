import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';
import { HydratedDocument } from 'mongoose';

export type ProjectProjectionDocument = HydratedDocument<ProjectProjection>;

/** `project.event`의 중복·역순 처리를 위한 프로젝트 lifecycle 읽기 모델. */
@Schema({ timestamps: true, versionKey: false })
export class ProjectProjection {
    @Prop({ required: true }) projectId!: number;
    @Prop({ required: true }) teamId!: number;
    @Prop({ required: true, default: '' }) name!: string;
    @Prop({ type: String, default: null }) description!: string | null;
    @Prop({ required: true, default: '' }) status!: string;
    @Prop({ required: true, default: 0 }) position!: number;
    @Prop({ required: true, default: false }) deleted!: boolean;
    @Prop({ required: true, type: Date }) sourceOccurredAt!: Date;
    @Prop({ required: true }) sourceVersion!: bigint;
}

export const ProjectProjectionSchema = SchemaFactory.createForClass(ProjectProjection);
ProjectProjectionSchema.index({ projectId: 1 }, { unique: true });
ProjectProjectionSchema.index({ teamId: 1, position: 1, projectId: 1 });
