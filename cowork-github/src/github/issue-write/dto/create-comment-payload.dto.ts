import { IsNotEmpty, IsNumber, IsString } from 'class-validator';

export class CreateCommentPayloadDto {
  @IsNumber()
  issueNumber: number;

  @IsString()
  @IsNotEmpty()
  body: string;

  @IsString()
  @IsNotEmpty()
  requesterGithubUsername: string;
}
