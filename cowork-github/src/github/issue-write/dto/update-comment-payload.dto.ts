import { IsNotEmpty, IsNumber, IsString } from 'class-validator';

export class UpdateCommentPayloadDto {
  @IsNumber()
  commentId: number;

  @IsString()
  @IsNotEmpty()
  body: string;
}
