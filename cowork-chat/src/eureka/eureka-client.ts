import os from 'os';
import { Logger } from '@nestjs/common';
import { requireEnv } from '../common/config/config.util';

type EurekaConfig = {
    enabled: boolean;
    serverUrl: string;
    appName: string;
    host: string;
    port: number;
    instanceId: string;
    leaseRenewalIntervalSeconds: number;
};

export class EurekaClient {
    private readonly logger = new Logger(EurekaClient.name);
    private heartbeatTimer?: NodeJS.Timeout;
    private isPolling = false;

    constructor(private readonly config: EurekaConfig) {}

    static fromEnv(port: number): EurekaClient {
        const appName = process.env.EUREKA_APP_NAME ?? 'cowork-chat';
        const host = requireEnv('EUREKA_INSTANCE_HOST');
        const instanceId = process.env.EUREKA_INSTANCE_ID ?? `${host}:${appName}:${port}`;

        return new EurekaClient({
            enabled: process.env.EUREKA_ENABLED !== 'false',
            serverUrl: requireEnv('EUREKA_SERVER_URL').replace(/\/$/, ''),
            appName,
            host,
            port,
            instanceId,
            leaseRenewalIntervalSeconds: Number(process.env.EUREKA_LEASE_RENEWAL_SECONDS ?? 30),
        });
    }

    async register(): Promise<void> {
        if (!this.config.enabled) {
            return;
        }

        await this.request(`/apps/${this.config.appName}`, {
            method: 'POST',
            body: JSON.stringify({
                instance: {
                    instanceId: this.config.instanceId,
                    hostName: this.config.host,
                    app: this.config.appName.toUpperCase(),
                    ipAddr: this.resolveIpAddress(),
                    vipAddress: this.config.appName,
                    secureVipAddress: this.config.appName,
                    status: 'UP',
                    port: { '$': this.config.port, '@enabled': 'true' },
                    securePort: { '$': 443, '@enabled': 'false' },
                    healthCheckUrl: `http://${this.config.host}:${this.config.port}/health`,
                    statusPageUrl: `http://${this.config.host}:${this.config.port}/health`,
                    homePageUrl: `http://${this.config.host}:${this.config.port}/`,
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

        this.stopHeartbeat();
        await this.request(`/apps/${this.config.appName}/${this.config.instanceId}`, {
            method: 'DELETE',
        });
    }

    private startHeartbeat(): void {
        this.stopHeartbeat();
        this.heartbeatTimer = setInterval(() => {
            void this.sendHeartbeat();
        }, this.config.leaseRenewalIntervalSeconds * 1000);
    }

    private async sendHeartbeat(): Promise<void> {
        if (this.isPolling) return;
        this.isPolling = true;
        try {
            await this.request(`/apps/${this.config.appName}/${this.config.instanceId}`, {
                method: 'PUT',
            });
        } catch (err: unknown) {
            this.logger.warn(`eureka heartbeat failed: ${String(err)}`);
            if (err instanceof Error && err.message.includes('404')) {
                await this.register().catch((regErr: unknown) => {
                    this.logger.error(`eureka re-registration failed: ${String(regErr)}`);
                });
            }
        } finally {
            this.isPolling = false;
        }
    }

    private stopHeartbeat(): void {
        if (this.heartbeatTimer) {
            clearInterval(this.heartbeatTimer);
            this.heartbeatTimer = undefined;
        }
    }

    private async request(path: string, init: RequestInit): Promise<void> {
        const response = await fetch(`${this.config.serverUrl}${path}`, {
            ...init,
            headers: {
                Accept: 'application/json',
                'Content-Type': 'application/json',
                ...(init.headers ?? {}),
            },
        });

        if (!response.ok && response.status !== 204) {
            throw new Error(`Eureka request failed: ${init.method} ${path} -> ${response.status}`);
        }
    }

    private resolveIpAddress(): string {
        for (const interfaces of Object.values(os.networkInterfaces())) {
            for (const iface of interfaces ?? []) {
                // Node.js 18 미만에서는 family가 숫자(4)였던 레거시 동작을 함께 지원한다.
                if ((iface.family === 'IPv4' || (iface.family as unknown) === 4) && !iface.internal) {
                    return iface.address;
                }
            }
        }
        return '127.0.0.1';
    }
}
