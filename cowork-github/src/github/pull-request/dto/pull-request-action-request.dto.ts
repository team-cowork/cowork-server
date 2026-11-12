import {IsNotEmpty as isNotEmpty, IsString as isString} from 'class-validator';
import {ApiProperty as apiProperty} from '@nestjs/swagger';

export class PullRequestActionRequestDto {
	@apiProperty({description: '작업을 요청한 사용자의 GitHub 로그인 이름', example: 'octocat', minLength: 1})
	@isString()
	@isNotEmpty()
	requesterGithubUsername: string;
}
