import {
	IsIn as isIn,
	IsInt as isInt,
	IsNotEmpty as isNotEmpty,
	IsString as isString,
	ValidateIf as validateIf,
} from 'class-validator';

export class GithubSyncCommandDto {
	@isIn([1])
	schemaVersion: number;

	@isIn(['ORG_REPOSITORIES', 'REPOSITORY'])
	target: 'ORG_REPOSITORIES' | 'REPOSITORY';

	@isString()
	@isNotEmpty()
	owner: string;

	@validateIf((command: GithubSyncCommandDto) => command.target === 'REPOSITORY')
	@isString()
	@isNotEmpty()
	repo?: string;

	@validateIf((command: GithubSyncCommandDto) => command.target === 'ORG_REPOSITORIES')
	@isInt()
	installationId?: number;
}
