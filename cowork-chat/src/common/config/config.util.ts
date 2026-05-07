import { ConfigService } from '@nestjs/config';

type ConfigKey = string | string[];

function normalizeKeys(keys: ConfigKey): string[] {
    return Array.isArray(keys) ? keys : [keys];
}

export function getRequiredConfig(configService: ConfigService, keys: ConfigKey): string {
    for (const key of normalizeKeys(keys)) {
        const value = configService.get<string>(key) ?? process.env[key];
        if (value !== undefined && value !== '') {
            return value;
        }
    }

    throw new Error(`Required configuration is missing: ${normalizeKeys(keys).join(' or ')}`);
}

export function getOptionalConfig(configService: ConfigService, keys: ConfigKey): string | undefined {
    for (const key of normalizeKeys(keys)) {
        const value = configService.get<string>(key) ?? process.env[key];
        if (value !== undefined && value !== '') {
            return value;
        }
    }

    return undefined;
}

export function getRequiredCsvConfig(configService: ConfigService, keys: ConfigKey): string[] {
    return getRequiredConfig(configService, keys)
        .split(',')
        .map((value) => value.trim())
        .filter(Boolean);
}
