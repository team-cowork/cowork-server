import {SetMetadata as setMetadata} from '@nestjs/common';

export const PUBLIC_METADATA_KEY = 'isPublic';
export const publicRoute = () => setMetadata(PUBLIC_METADATA_KEY, true);
