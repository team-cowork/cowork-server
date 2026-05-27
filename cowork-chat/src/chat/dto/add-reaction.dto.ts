import { ApiProperty } from '@nestjs/swagger';
import { IsString, Matches } from 'class-validator';
import { EMOJI_REGEX } from '../util/emoji';

export class AddReactionDto {
    @ApiProperty({ example: '👍' })
    @IsString()
    @Matches(EMOJI_REGEX, {
        message: 'Invalid emoji format',
    })
    emoji!: string;
}
