/** Bootstrap credentials must come from the process environment, before fetching configuration. */
const username = process.env.CONFIG_CLIENT_USERNAME;
const password = process.env.CONFIG_CLIENT_PASSWORD;
const profile = process.env.APP_PROFILE ?? process.env.SPRING_PROFILES_ACTIVE ?? 'local';

export function controlPlaneAuthorization(url: string): string {
    const endpoint = new URL(url);
    if (endpoint.username || endpoint.password || !['http:', 'https:'].includes(endpoint.protocol)) {
        throw new Error('Use a Config/Eureka HTTP(S) URL without credentials');
    }

    if (profile === 'prod' && endpoint.protocol !== 'https:') {
        throw new Error('Use HTTPS for production Config/Eureka');
    }

    if (!username || !password) {
        throw new Error('Provide CONFIG_CLIENT_USERNAME and CONFIG_CLIENT_PASSWORD');
    }

    return `Basic ${Buffer.from(`${username}:${password}`, 'utf8').toString('base64')}`;
}
