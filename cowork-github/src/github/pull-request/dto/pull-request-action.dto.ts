import { IsNotEmpty, IsNumber, IsOptional, IsString } from 'class-validator';

export class PullRequestActionDto {
  @IsString()
  @IsNotEmpty()
  owner: string;

  @IsString()
  @IsNotEmpty()
  repo: string;

  @IsNumber()
  prNumber: number;

  @IsString()
  @IsNotEmpty()
  requesterGithubUsername: string;

  @IsNumber()
  @IsOptional()
  channelId?: number;

  @IsNumber()
  @IsOptional()
  teamId?: number;
}
