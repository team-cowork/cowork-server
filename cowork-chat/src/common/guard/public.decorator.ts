import {SetMetadata} from '@nestjs/common';

export const PUBLIC_METADATA_KEY = 'isPublic';
export const Public = () => SetMetadata(PUBLIC_METADATA_KEY, true);
