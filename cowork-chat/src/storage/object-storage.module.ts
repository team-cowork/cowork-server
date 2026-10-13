import { MongooseModule } from '@nestjs/mongoose';
import { Module } from '@nestjs/common';
import { ConfigModule, ConfigService } from '@nestjs/config';
import { S3Client } from '@aws-sdk/client-s3';
import { AttachmentUploadRepository } from './attachment-upload.repository';
import { AttachmentUpload, AttachmentUploadSchema } from './attachment-upload.schema';
import { S3_CLIENT, S3_PUBLIC_CLIENT } from './object-storage.constants';
import { buildObjectStorageConfig } from './object-storage.config';
import { ObjectStorageService } from './object-storage.service';

function createClient(configService: ConfigService, publicEndpoint: boolean): S3Client {
    const config = buildObjectStorageConfig(configService);
    return new S3Client({
        endpoint: publicEndpoint ? config.publicEndpoint : config.internalEndpoint,
        region: config.region,
        credentials: { accessKeyId: config.accessKey, secretAccessKey: config.secretKey },
        forcePathStyle: true,
        // A browser uploads the body later; do not sign the SDK's checksum for an empty body.
        requestChecksumCalculation: 'WHEN_REQUIRED',
    });
}

@Module({
    imports: [ConfigModule, MongooseModule.forFeature([{ name: AttachmentUpload.name, schema: AttachmentUploadSchema }])],
    providers: [
        {
            provide: S3_CLIENT,
            inject: [ConfigService],
            useFactory: (configService: ConfigService) => createClient(configService, false),
        },
        {
            provide: S3_PUBLIC_CLIENT,
            inject: [ConfigService],
            useFactory: (configService: ConfigService) => createClient(configService, true),
        },
        ObjectStorageService,
        AttachmentUploadRepository,
    ],
    exports: [ObjectStorageService],
})
export class ObjectStorageModule {}
