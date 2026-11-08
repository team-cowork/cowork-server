import {BadRequestException, Injectable as injectable, PipeTransform} from '@nestjs/common';

@injectable()
export class ObjectIdPipe implements PipeTransform<string, string> {
	transform(value: string): string {
		if (!/^[\da-f]{24}$/iv.test(value)) {
			throw new BadRequestException('ID must be a 24-character hex ObjectId');
		}

		return value;
	}
}
