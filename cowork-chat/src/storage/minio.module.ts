import { Module } from '@nestjs/common';
import { ConfigModule, ConfigService } from '@nestjs/config';
import * as Minio from 'minio';
import { MINIO_CLIENT } from './minio.constants';
import { buildMinioConfig } from './minio.config';
import { MinioService } from './minio.service';

@Module({
    imports: [ConfigModule],
    providers: [
        {
            provide: MINIO_CLIENT,
            inject: [ConfigService],
            useFactory: (configService: ConfigService) => {
                const minioConfig = buildMinioConfig(configService);

                return new Minio.Client({
                    endPoint: minioConfig.endPoint,
                    port: minioConfig.port,
                    useSSL: minioConfig.useSSL,
                    accessKey: minioConfig.accessKey,
                    secretKey: minioConfig.secretKey,
                });
            },
        },
        MinioService,
    ],
    exports: [MinioService],
})
export class MinioModule {}

