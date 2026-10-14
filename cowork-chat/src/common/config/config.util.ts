import { type ConfigService } from '@nestjs/config';

type ConfigKey = string | string[];

function normalizeKeys(keys: ConfigKey): string[] {
    return Array.isArray(keys) ? keys : [keys];
}

export function getRequiredConfig(configService: ConfigService, keys: ConfigKey): string {
    const value = getConfigValue(configService, keys);
    if (value !== undefined) {
        return value;
    }

    throw new Error(`Required configuration is missing: ${normalizeKeys(keys).join(' or ')}`);
}

export function getOptionalConfig(configService: ConfigService, keys: ConfigKey): string | undefined {
    return getConfigValue(configService, keys);
}

export function getMongoDbUri(configService: ConfigService): string {
    const host = getOptionalConfig(configService, 'DB_HOST');
    if (host === undefined) {
        return getRequiredConfig(configService, 'MONGODB_URI');
    }

    // Slot DB inputs take precedence over a URI returned by Config Server.
    const port = getRequiredConfig(configService, 'DB_PORT');
    const database = encodeURIComponent(getRequiredConfig(configService, 'DB_NAME'));
    const username = encodeURIComponent(getRequiredConfig(configService, 'DB_USERNAME'));
    const password = encodeURIComponent(getRequiredConfig(configService, 'DB_PASSWORD'));
    return `mongodb://${username}:${password}@${host}:${port}/${database}?authSource=admin`;
}

export function getRequiredCsvConfig(configService: ConfigService, keys: ConfigKey): string[] {
    const values = getRequiredConfig(configService, keys)
        .split(',')
        .map(value => value.trim())
        .filter(Boolean);

    if (values.length === 0) {
        throw new Error(`Required CSV configuration is empty: ${normalizeKeys(keys).join(' or ')}`);
    }

    return values;
}

export function requireEnv(key: string): string {
    const value = process.env[key];
    if (value !== undefined && value !== '') {
        return value;
    }

    throw new Error(`Required environment variable is missing: ${key}`);
}

function getConfigValue(configService: ConfigService, keys: ConfigKey): string | undefined {
    for (const key of normalizeKeys(keys)) {
        const value = configService.get<string>(key) ?? process.env[key];
        if (value !== undefined && value !== '') {
            return value;
        }
    }

    return undefined;
}
