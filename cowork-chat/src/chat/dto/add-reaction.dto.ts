import { ApiProperty } from '@nestjs/swagger';
import { IsString, Matches } from 'class-validator';

export class AddReactionDto {
    @ApiProperty({ example: '👍' })
    @IsString()
    @Matches(/^(\p{Emoji_Presentation}|\p{Extended_Pictographic})+$/u, {
        message: 'Invalid emoji format',
    })
    emoji!: string;
}
