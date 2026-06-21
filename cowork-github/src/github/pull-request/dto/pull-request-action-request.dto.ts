import { IsNotEmpty, IsString } from 'class-validator';

export class PullRequestActionRequestDto {
  @IsString()
  @IsNotEmpty()
  requesterGithubUsername: string;
}
