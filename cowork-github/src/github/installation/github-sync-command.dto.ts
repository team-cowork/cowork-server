import {
    IsIn,
    IsInt,
    IsNotEmpty,
    IsString,
    ValidateIf,
} from 'class-validator';

export class GithubSyncCommandDto {
    @IsIn([1])
    schemaVersion: number;

    @IsIn(['ORG_REPOSITORIES', 'REPOSITORY'])
    target: 'ORG_REPOSITORIES' | 'REPOSITORY';

    @IsString()
    @IsNotEmpty()
    owner: string;

    @ValidateIf((command: GithubSyncCommandDto) => command.target === 'REPOSITORY')
    @IsString()
    @IsNotEmpty()
    repo?: string;

    @ValidateIf((command: GithubSyncCommandDto) => command.target === 'ORG_REPOSITORIES')
    @IsInt()
    installationId?: number;
}
