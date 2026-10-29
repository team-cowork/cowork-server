import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';
import {type Nullable} from '../../common/type/nullable.js';

/**
`project.github-repo.event`로 동기화되는 프로젝트 GitHub 저장소 연결 읽기 모델.
*/
@schema({timestamps: true, versionKey: false})
export class ProjectGithubRepoProjection {
	@prop({required: true}) repoId!: number;
	@prop({required: true}) projectId!: number;
	@prop({required: true}) teamId!: number;
	@prop({required: true, default: ''}) githubRepoUrl!: string;
	@prop({required: true, default: ''}) owner!: string;
	@prop({required: true, default: ''}) repo!: string;
	@prop({type: Number, default: null}) webhookChannelId!: Nullable<number>;
	@prop({required: true, default: false}) deleted!: boolean;
	@prop({required: true, type: Date}) sourceOccurredAt!: Date;
	@prop({required: true}) sourceVersion!: bigint;
}

export const projectGithubRepoProjectionSchema = SchemaFactory.createForClass(ProjectGithubRepoProjection)
	.index({repoId: 1}, {unique: true})
	.index({projectId: 1, repoId: 1})
	.index({owner: 1, repo: 1, deleted: 1});
