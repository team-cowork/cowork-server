import { IsNotEmpty, IsString } from 'class-validator';

export class UpdateCommentRequestDto {
  @IsString()
  @IsNotEmpty()
  body: string;
}
