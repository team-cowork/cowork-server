import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';
import { HydratedDocument } from 'mongoose';

export type ProjectGithubRepoProjectionDocument = HydratedDocument<ProjectGithubRepoProjection>;

/** `project.github-repo.event`로 동기화되는 프로젝트 GitHub 저장소 연결 읽기 모델. */
@Schema({ timestamps: true, versionKey: false })
export class ProjectGithubRepoProjection {
    @Prop({ required: true }) repoId!: number;
    @Prop({ required: true }) projectId!: number;
    @Prop({ required: true }) teamId!: number;
    @Prop({ required: true, default: '' }) githubRepoUrl!: string;
    @Prop({ required: true, default: '' }) owner!: string;
    @Prop({ required: true, default: '' }) repo!: string;
    @Prop({ type: Number, default: null }) webhookChannelId!: number | null;
    @Prop({ required: true, default: false }) deleted!: boolean;
    @Prop({ required: true, type: Date }) sourceOccurredAt!: Date;
    @Prop({ required: true }) sourceVersion!: bigint;
}

export const ProjectGithubRepoProjectionSchema = SchemaFactory.createForClass(ProjectGithubRepoProjection);
ProjectGithubRepoProjectionSchema.index({ repoId: 1 }, { unique: true });
ProjectGithubRepoProjectionSchema.index({ projectId: 1, repoId: 1 });
ProjectGithubRepoProjectionSchema.index({ owner: 1, repo: 1, deleted: 1 });
