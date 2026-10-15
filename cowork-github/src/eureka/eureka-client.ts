import {Logger} from '@nestjs/common';
import {controlPlaneAuthorization} from '../config/control-plane-auth';

export type EurekaConfig = {
    enabled: boolean;
    serverUrl: string;
    appName: string;
    host: string;
    port: number;
    leaseRenewalIntervalSeconds: number;
};

export class EurekaClient {
    private readonly logger = new Logger(EurekaClient.name);
    private readonly serverUrl: string;
    private readonly instanceId: string;
    private heartbeatTimer?: NodeJS.Timeout;
    private heartbeat?: Promise<void>;
    private stopped = false;

    constructor(private readonly config: EurekaConfig) {
        this.serverUrl = config.serverUrl.replace(/\/$/v, '');
        this.instanceId = `${config.host}:${config.appName}:${config.port}`;
    }

    async register(): Promise<void> {
        if (!this.config.enabled || this.stopped) {
            return;
        }

        const baseUrl = `http://${this.config.host}:${this.config.port}`;
        await this.request(`/apps/${this.config.appName}`, {
            method: 'POST',
            body: JSON.stringify({
                instance: {
                    instanceId: this.instanceId,
                    hostName: this.config.host,
                    app: this.config.appName.toUpperCase(),
                    ipAddr: this.config.host,
                    vipAddress: this.config.appName,
                    secureVipAddress: this.config.appName,
                    status: 'UP',
                    port: {$: this.config.port, '@enabled': 'true'},
                    securePort: {$: 443, '@enabled': 'false'},
                    healthCheckUrl: `${baseUrl}/health`,
                    statusPageUrl: `${baseUrl}/health`,
                    homePageUrl: `${baseUrl}/`,
                    dataCenterInfo: {
                        '@class': 'com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo',
                        name: 'MyOwn',
                    },
                    metadata: {
                        'management.port': String(this.config.port),
                        'prometheus.scrape': 'true',
                        'prometheus.path': '/metrics',
                    },
                },
            }),
        });
        this.startHeartbeat();
    }

    async deregister(): Promise<void> {
        if (!this.config.enabled) {
            return;
        }

        this.stopped = true;
        this.stopHeartbeat();
        // 진행 중인 heartbeat가 해제 뒤 404를 받아 다시 등록하지 않도록 끝날 때까지 기다린다.
        await this.heartbeat;
        await this.request(`/apps/${this.config.appName}/${this.instanceId}`, {method: 'DELETE'});
    }

    private startHeartbeat(): void {
        this.stopHeartbeat();
        this.heartbeatTimer = setInterval(() => {
            void this.sendHeartbeat();
        }, this.config.leaseRenewalIntervalSeconds * 1000);
    }

    private async sendHeartbeat(): Promise<void> {
        this.heartbeat ??= this.renewLease().finally(() => {
            this.heartbeat = undefined;
        });
        return this.heartbeat;
    }

    private async renewLease(): Promise<void> {
        try {
            await this.request(`/apps/${this.config.appName}/${this.instanceId}`, {method: 'PUT'});
        } catch (error: unknown) {
            this.logger.warn(`Eureka heartbeat failed: ${String(error)}`);
            if (error instanceof EurekaRequestError && error.status === 404) {
                await this.register().catch((registrationError: unknown) => {
                    this.logger.error(`Eureka re-registration failed: ${String(registrationError)}`);
                });
            }
        }
    }

    private stopHeartbeat(): void {
        if (this.heartbeatTimer === undefined) {
            return;
        }

        clearInterval(this.heartbeatTimer);
        this.heartbeatTimer = undefined;
    }

    private async request(path: string, init: RequestInit): Promise<void> {
        const response = await fetch(`${this.serverUrl}${path}`, {
            ...init,
            redirect: 'error',
            signal: AbortSignal.timeout(5000),
            headers: {
                Accept: 'application/json',
                'Content-Type': 'application/json',
                Authorization: controlPlaneAuthorization(this.serverUrl),
            },
        });

        if (!response.ok) {
            throw new EurekaRequestError(`${init.method} ${path}`, response.status);
        }
    }
}

export class EurekaRequestError extends Error {
    constructor(
        request: string,
        readonly status: number,
    ) {
        super(`Eureka request failed: ${request} -> ${status}`);
        this.name = 'EurekaRequestError';
    }
}
