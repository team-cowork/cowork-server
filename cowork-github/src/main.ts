import {Logger, ValidationPipe} from '@nestjs/common';
import {NestFactory} from '@nestjs/core';
import {type MicroserviceOptions, Transport} from '@nestjs/microservices';
import {loadConfigServerEnv} from './config/config-server';
import {AppConfigService} from './config/app-config.service';
import {EurekaClient} from './eureka/eureka-client';

const EUREKA_RETRY_DELAY_MS = 5000;

async function bootstrap() {
    await loadConfigServerEnv();
    // ConfigModule validates process.env when AppModule is evaluated, so import it after loading Config Server values.
    const {AppModule} = await import('./app.module');
    const app = await NestFactory.create(AppModule, {rawBody: true});
    const config = app.get(AppConfigService);

    app.connectMicroservice<MicroserviceOptions>({
        transport: Transport.KAFKA,
        options: {
            client: {
                brokers: config.kafkaBrokers,
            },
            consumer: {
                groupId: config.kafkaGroupId,
                allowAutoTopicCreation: false,
            },
            run: {
                autoCommit: false,
            },
        },
    });

    app.useGlobalPipes(new ValidationPipe({whitelist: true}));

    await app.startAllMicroservices();
    await app.listen(config.port);

    const eureka = new EurekaClient({
        enabled: config.eurekaEnabled,
        serverUrl: config.eurekaEnabled ? config.eurekaServerUrl : '',
        appName: config.eurekaAppName,
        host: config.eurekaEnabled ? config.advertiseAddress : '',
        port: config.advertisePort,
        leaseRenewalIntervalSeconds: config.eurekaLeaseRenewalSeconds,
    });
    let isShuttingDown = false;
    const registration = registerUntilStopped(eureka, () => isShuttingDown);

    const shutdown = async () => {
        isShuttingDown = true;
        await registration;
        await eureka.deregister().catch((error: unknown) => {
            new Logger('Eureka').warn(`Failed to deregister: ${String(error)}`);
        });
    };

    const handleSignal = async (signal: NodeJS.Signals) => {
        try {
            await shutdown();
            await app.close();
        } finally {
            // Re-raise after cleanup so the default handler terminates despite open Redis sockets.
            process.kill(process.pid, signal);
        }
    };

    for (const signal of ['SIGTERM', 'SIGINT'] as const) {
        process.once(signal, () => {
            void handleSignal(signal);
        });
    }
}

async function registerUntilStopped(eureka: EurekaClient, isStopped: () => boolean): Promise<void> {
    while (!isStopped()) {
        try {
            // eslint-disable-next-line no-await-in-loop -- retry-with-backoff: each attempt must wait for the previous one to fail.
            await eureka.register();
            return;
        } catch (error: unknown) {
            new Logger('Eureka').warn(`Failed to register; retrying: ${String(error)}`);
            // eslint-disable-next-line no-await-in-loop -- retry-with-backoff: each attempt must wait for the previous one to fail.
            await new Promise(resolve => {
                setTimeout(resolve, EUREKA_RETRY_DELAY_MS);
            });
        }
    }
}

void bootstrap();
