import {
	IsIn as isIn,
	IsInt as isInt,
	IsNotEmpty as isNotEmpty,
	IsPositive as isPositive,
	IsString as isString,
	ValidateIf as validateIf,
} from 'class-validator';

export class GithubSyncCommandDto {
	@isIn([1])
	schemaVersion: number;

	@isIn(['ORG_REPOSITORIES', 'REPOSITORY', 'PROJECT', 'TIMELINE'])
	target: 'ORG_REPOSITORIES' | 'REPOSITORY' | 'PROJECT' | 'TIMELINE';

	@isString()
	@isNotEmpty()
	owner: string;

	@validateIf((command: GithubSyncCommandDto) => command.target === 'REPOSITORY' || command.target === 'TIMELINE')
	@isString()
	@isNotEmpty()
	repo?: string;

	@validateIf((command: GithubSyncCommandDto) => command.target === 'ORG_REPOSITORIES')
	@isInt()
	installationId?: number;

	@validateIf((command: GithubSyncCommandDto) => command.target === 'TIMELINE')
	@isInt()
	@isPositive()
	number?: number;

	@validateIf((command: GithubSyncCommandDto) => command.target === 'PROJECT')
	@isInt()
	@isPositive()
	projectNumber?: number;
}
