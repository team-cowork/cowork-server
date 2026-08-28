import { BadRequestException, Injectable, PipeTransform } from '@nestjs/common';
import { isSafePositiveInteger } from '../util/safe-integer.util';

@Injectable()
export class SafePositiveIntPipe implements PipeTransform<string, number> {
    transform(value: string): number {
        if (!/^[1-9]\d*$/.test(value)) {
            throw new BadRequestException('ID must be a positive integer');
        }
        const parsed = Number(value);
        if (!isSafePositiveInteger(parsed)) {
            throw new BadRequestException('ID exceeds the JavaScript safe integer range');
        }
        return parsed;
    }
}
