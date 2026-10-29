import {MongooseModule} from '@nestjs/mongoose';
import {Module as nestModule} from '@nestjs/common';
import {ConfigModule, ConfigService} from '@nestjs/config';
import {S3Client} from '@aws-sdk/client-s3';
import {AttachmentUploadRepository} from './attachment-upload.repository.js';
import {AttachmentUpload, attachmentUploadSchema} from './attachment-upload.schema.js';
import {S3_CLIENT, S3_SIGNING_CLIENT} from './object-storage.constants.js';
import {buildObjectStorageConfig} from './object-storage.config.js';
import {ObjectStorageService} from './object-storage.service.js';

function createClient(configService: ConfigService, isSigning = false): S3Client {
	const config = buildObjectStorageConfig(configService);
	return new S3Client({
		endpoint: isSigning ? config.publicApiBaseUrl : config.internalEndpoint,
		region: config.region,
		credentials: {accessKeyId: config.accessKey, secretAccessKey: config.secretKey},
		forcePathStyle: true,
		// A browser uploads the body later; do not sign the SDK's checksum for an empty body.
		requestChecksumCalculation: 'WHEN_REQUIRED',
	});
}

@nestModule({
	imports: [ConfigModule, MongooseModule.forFeature([{name: AttachmentUpload.name, schema: attachmentUploadSchema}])],
	providers: [
		{
			provide: S3_CLIENT,
			inject: [ConfigService],
			useFactory: (configService: ConfigService) => createClient(configService),
		},
		{
			provide: S3_SIGNING_CLIENT,
			inject: [ConfigService],
			useFactory: (configService: ConfigService) => createClient(configService, true),
		},
		ObjectStorageService,
		AttachmentUploadRepository,
	],
	exports: [ObjectStorageService],
})
export class ObjectStorageModule {}
