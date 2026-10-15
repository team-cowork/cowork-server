import { IsNumber } from 'class-validator';

export class DeleteCommentPayloadDto {
  @IsNumber()
  commentId: number;
}
