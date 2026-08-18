import { Module } from '@nestjs/common';
import { ConfigModule, ConfigService } from '@nestjs/config';
import { S3Client } from '@aws-sdk/client-s3';
import { S3_CLIENT } from './object-storage.constants';
import { buildObjectStorageConfig } from './object-storage.config';
import { ObjectStorageService } from './object-storage.service';

const DEFAULT_REGION = 'us-east-1';

@Module({
    imports: [ConfigModule],
    providers: [
        {
            provide: S3_CLIENT,
            inject: [ConfigService],
            useFactory: (configService: ConfigService) => {
                const objectStorageConfig = buildObjectStorageConfig(configService);
                const protocol = objectStorageConfig.useSSL ? 'https' : 'http';
                const endpoint = objectStorageConfig.port
                    ? `${protocol}://${objectStorageConfig.endPoint}:${objectStorageConfig.port}`
                    : `${protocol}://${objectStorageConfig.endPoint}`;

                return new S3Client({
                    endpoint,
                    region: DEFAULT_REGION,
                    credentials: {
                        accessKeyId: objectStorageConfig.accessKey,
                        secretAccessKey: objectStorageConfig.secretKey,
                    },
                    forcePathStyle: true,
                });
            },
        },
        ObjectStorageService,
    ],
    exports: [ObjectStorageService],
})
export class ObjectStorageModule {}
