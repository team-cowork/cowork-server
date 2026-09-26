import { IsArray, IsNumber, IsString } from 'class-validator';

export class ReplaceLabelsPayloadDto {
  @IsNumber()
  issueNumber: number;

  @IsArray()
  @IsString({ each: true })
  labels: string[];
}
