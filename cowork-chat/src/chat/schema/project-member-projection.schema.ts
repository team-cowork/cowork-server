import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';
import { HydratedDocument } from 'mongoose';

export type ProjectMemberProjectionDocument = HydratedDocument<ProjectMemberProjection>;

/** `project.member.event`로 동기화되는 프로젝트 멤버십 읽기 모델. */
@Schema({ timestamps: true, versionKey: false })
export class ProjectMemberProjection {
    @Prop({ required: true }) projectId!: number;
    @Prop({ required: true }) userId!: number;
    @Prop({ required: true, default: false }) deleted!: boolean;
    @Prop({ required: true, type: Date }) sourceOccurredAt!: Date;
    @Prop({ required: true }) sourceVersion!: bigint;
}

export const ProjectMemberProjectionSchema = SchemaFactory.createForClass(ProjectMemberProjection);
ProjectMemberProjectionSchema.index({ projectId: 1, userId: 1 }, { unique: true });
ProjectMemberProjectionSchema.index({ userId: 1, projectId: 1 });
