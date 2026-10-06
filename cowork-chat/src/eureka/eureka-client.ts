import os from 'node:os';
import { Logger } from '@nestjs/common';
import { requireEnv } from '../common/config/config.util';
import { controlPlaneAuthorization } from '../common/config/control-plane-auth';

type EurekaConfig = {
    enabled: boolean;
    serverUrl: string;
    appName: string;
    host: string;
    port: number;
    instanceId: string;
    leaseRenewalIntervalSeconds: number;
};

export type EurekaInstanceStatus = 'UP' | 'OUT_OF_SERVICE';

export class EurekaClient {
    private readonly logger = new Logger(EurekaClient.name);
    private heartbeatTimer?: NodeJS.Timeout;
    private isPolling = false;
    private desiredStatus: EurekaInstanceStatus = 'UP';
    /** 등록된 동안 Eureka 서버에 반영한 status. 미등록이면 `undefined`다. */
    private appliedStatus?: EurekaInstanceStatus;
    private statusSync?: Promise<void>;

    constructor(private readonly config: EurekaConfig) {}

    static fromEnv(port: number): EurekaClient {
        const appName = process.env.EUREKA_APP_NAME ?? 'cowork-chat';
        const runtimeIdentity = os.hostname();
        const useRuntimeIdentity = process.env.EUREKA_USE_RUNTIME_HOSTNAME === 'true';
        const host = useRuntimeIdentity
            ? (process.env.EUREKA_ADVERTISE_HOST ?? resolveIpAddress())
            : requireEnv('EUREKA_INSTANCE_HOST');
        const instanceId = process.env.EUREKA_INSTANCE_ID
            ?? `${useRuntimeIdentity ? runtimeIdentity : host}:${appName}:${port}`;

        return new EurekaClient({
            enabled: process.env.EUREKA_ENABLED !== 'false',
            serverUrl: requireEnv('EUREKA_SERVER_URL').replace(/\/$/u, ''),
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

        const status = this.desiredStatus;
        await this.request(`/apps/${this.config.appName}`, {
            method: 'POST',
            body: JSON.stringify({
                instance: {
                    instanceId: this.config.instanceId,
                    hostName: this.config.host,
                    app: this.config.appName.toUpperCase(),
                    ipAddr: this.config.host,
                    vipAddress: this.config.appName,
                    secureVipAddress: this.config.appName,
                    status,
                    port: { $: this.config.port, '@enabled': 'true' },
                    securePort: { $: 443, '@enabled': 'false' },
                    healthCheckUrl: `http://${this.config.host}:${this.config.port}/health/ready`,
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

        this.appliedStatus = status;
        this.startHeartbeat();
        void this.syncStatus();
    }

    /**
     * 인스턴스가 트래픽을 받을 수 있는지 Eureka에 알린다. 프로세스와 lease는 유지한 채 status override만
     * 바꾸므로 Gateway는 `OUT_OF_SERVICE` 인스턴스를 라우팅에서 빼고, Prometheus는 계속 수집한다.
     * 호출은 직렬화하고 최신 상태만 반영하며, 실패는 다음 heartbeat에서 다시 맞춘다.
     */
    setStatus(status: EurekaInstanceStatus): void {
        this.desiredStatus = status;
        if (!this.config.enabled) {
            return;
        }

        void this.syncStatus();
    }

    async deregister(): Promise<void> {
        if (!this.config.enabled) {
            return;
        }

        this.stopHeartbeat();
        this.appliedStatus = undefined;
        await this.request(`/apps/${this.config.appName}/${this.config.instanceId}`, {
            method: 'DELETE',
        });
    }

    private async syncStatus(): Promise<void> {
        this.statusSync ??= this.applyStatus().finally(() => {
            this.statusSync = undefined;
        });
        return this.statusSync;
    }

    private async applyStatus(): Promise<void> {
        try {
            while (this.appliedStatus !== undefined && this.appliedStatus !== this.desiredStatus) {
                const status = this.desiredStatus;
                // UP 복구는 override를 지워야 이후 재등록·heartbeat가 OUT_OF_SERVICE로 고정되지 않는다.
                await this.request(`/apps/${this.config.appName}/${this.config.instanceId}/status?value=${status}`, {
                    method: status === 'UP' ? 'DELETE' : 'PUT',
                });
                if (this.appliedStatus !== undefined) {
                    this.appliedStatus = status;
                }

                this.logger.log(`eureka status → ${status}`);
            }
        } catch (error: unknown) {
            this.logger.warn(`eureka status update failed; retrying on next heartbeat: ${String(error)}`);
        }
    }

    private startHeartbeat(): void {
        this.stopHeartbeat();
        this.heartbeatTimer = setInterval(() => {
            void this.sendHeartbeat();
        }, this.config.leaseRenewalIntervalSeconds * 1000);
    }

    private async sendHeartbeat(): Promise<void> {
        if (this.isPolling) {
            return;
        }

        this.isPolling = true;
        try {
            await this.request(`/apps/${this.config.appName}/${this.config.instanceId}`, {
                method: 'PUT',
            });
            await this.syncStatus();
        } catch (error: unknown) {
            this.logger.warn(`eureka heartbeat failed: ${String(error)}`);
            if (error instanceof Error && error.message.includes('404')) {
                await this.register().catch((registrationError: unknown) => {
                    this.logger.error(`eureka re-registration failed: ${String(registrationError)}`);
                });
            }
        } finally {
            this.isPolling = false;
        }
    }

    private stopHeartbeat(): void {
        if (!this.heartbeatTimer) {
            return;
        }

        clearInterval(this.heartbeatTimer);
        this.heartbeatTimer = undefined;
    }

    private async request(path: string, init: RequestInit): Promise<void> {
        const response = await fetch(`${this.config.serverUrl}${path}`, {
            ...init,
            redirect: 'error',
            signal: init.signal ?? AbortSignal.timeout(5000),
            headers: {
                Accept: 'application/json',
                'Content-Type': 'application/json',
                ...init.headers,
                Authorization: controlPlaneAuthorization(this.config.serverUrl),
            },
        });

        if (!response.ok && response.status !== 204) {
            throw new Error(`Eureka request failed: ${init.method} ${path} -> ${response.status}`);
        }
    }
}

function resolveIpAddress(): string {
    for (const interfaces of Object.values(os.networkInterfaces())) {
        for (const iface of interfaces ?? []) {
            // Node.js 18 미만에서는 family가 숫자(4)였던 레거시 동작을 함께 지원한다.
            if ((iface.family === 'IPv4' || (iface.family as unknown) === 4) && !iface.internal) {
                return iface.address;
            }
        }
    }

    throw new Error('No non-loopback IPv4 address is available for Eureka registration');
}
