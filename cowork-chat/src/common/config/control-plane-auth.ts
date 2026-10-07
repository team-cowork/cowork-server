import { isIPv4 } from 'node:net';

/** Bootstrap credentials must come from the process environment, before fetching configuration. */
const username = process.env.CONFIG_CLIENT_USERNAME;
const password = process.env.CONFIG_CLIENT_PASSWORD;
const profile = process.env.APP_PROFILE ?? process.env.SPRING_PROFILES_ACTIVE ?? 'local';

export function controlPlaneAuthorization(url: string): string {
    const endpoint = new URL(url);
    if (endpoint.username || endpoint.password || !['http:', 'https:'].includes(endpoint.protocol)) {
        throw new Error('Use a Config/Eureka HTTP(S) URL without credentials');
    }

    if (profile === 'prod' && endpoint.protocol !== 'https:' && !isPrivateIpv4(endpoint.hostname)) {
        throw new Error('Use HTTPS or a private IPv4 HTTP URL for production Config/Eureka');
    }

    if (!username || !password) {
        throw new Error('Provide CONFIG_CLIENT_USERNAME and CONFIG_CLIENT_PASSWORD');
    }

    return `Basic ${Buffer.from(`${username}:${password}`, 'utf8').toString('base64')}`;
}

// Only RFC1918 IPv4 literals: a host name would let DNS redirect plaintext credentials.
function isPrivateIpv4(host: string): boolean {
    if (!isIPv4(host)) {
        return false;
    }

    const [first, second] = host.split('.').map(Number);
    return first === 10 || (first === 172 && second >= 16 && second <= 31) || (first === 192 && second === 168);
}
