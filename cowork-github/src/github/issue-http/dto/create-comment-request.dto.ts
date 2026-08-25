import { IsNotEmpty, IsString } from 'class-validator';

export class CreateCommentRequestDto {
  @IsString()
  @IsNotEmpty()
  body: string;

  @IsString()
  @IsNotEmpty()
  requesterGithubUsername: string;
}
