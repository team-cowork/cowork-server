import os from 'os';
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
        this.heartbeatTimer = setInterval(async () => {
            if (this.isPolling) return;
            this.isPolling = true;
            try {
                await this.request(`/apps/${this.config.appName}/${this.config.instanceId}`, {
                    method: 'PUT',
                });
            } catch (err: unknown) {
                console.warn('eureka heartbeat failed', err);
                if (err instanceof Error && err.message.includes('404')) {
                    await this.register().catch((regErr: unknown) => {
                        console.error('eureka re-registration failed', regErr);
                    });
                }
            } finally {
                this.isPolling = false;
            }
        }, this.config.leaseRenewalIntervalSeconds * 1000);
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
                if ((iface.family === 'IPv4' || (iface.family as any) === 4) && !iface.internal) {
                    return iface.address;
                }
            }
        }
        return '127.0.0.1';
    }
}
