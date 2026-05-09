type PropertySource = {
    name: string;
    source: Record<string, unknown>;
};

type ConfigServerResponse = {
    name: string;
    profiles: string[];
    propertySources: PropertySource[];
};

const DEFAULT_CONFIG_SERVER_URL = 'http://localhost:8761';
const DEFAULT_PROFILE = 'local';
const APP_NAME = 'cowork-chat';

export async function loadConfigServerEnv(): Promise<void> {
    const baseUrl = process.env.APP_CONFIG_URL ?? DEFAULT_CONFIG_SERVER_URL;
    const profile = process.env.APP_PROFILE ?? process.env.SPRING_PROFILES_ACTIVE ?? DEFAULT_PROFILE;
    const url = `${baseUrl.replace(/\/$/, '')}/${APP_NAME}/${profile}`;

    const response = await fetch(url, {
        headers: { Accept: 'application/json' },
        signal: AbortSignal.timeout(5000),
    });

    if (!response.ok) {
        throw new Error(`Config server returned ${response.status} for ${url}`);
    }

    const body = await response.json() as ConfigServerResponse;
    const flatMap = flattenPropertySources(body.propertySources ?? []);

    for (const [key, value] of Object.entries(flatMap)) {
        if (process.env[key] === undefined || process.env[key] === '') {
            process.env[key] = value;
        }
    }
}

function flattenPropertySources(propertySources: PropertySource[]): Record<string, string> {
    const flatMap: Record<string, string> = {};

    for (let i = propertySources.length - 1; i >= 0; i -= 1) {
        const source = propertySources[i]?.source ?? {};
        for (const [key, value] of Object.entries(source)) {
            flatMap[key] = value == null ? '' : String(value);
        }
    }

    return flatMap;
}
