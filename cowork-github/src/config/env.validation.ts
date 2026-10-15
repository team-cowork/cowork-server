import * as Joi from 'joi';

export const envValidationSchema = Joi.object({
    PORT: Joi.number().port().default(8090),
    KAFKA_BOOTSTRAP_SERVERS: Joi.string().trim().required(),
    KAFKA_GROUP_ID: Joi.string().trim().default('cowork-github-group'),
    GITHUB_APP_ID: Joi.string().trim().required(),
    GITHUB_PRIVATE_KEY: Joi.string().trim().required(),
    REDIS_HOST: Joi.string().trim().default('localhost'),
    REDIS_PORT: Joi.number().port().default(6379),
    GITHUB_TOKEN_CACHE_TTL_SECONDS: Joi.number()
        .integer()
        .positive()
        .default(3300),
    GITHUB_INSTALLATION_CACHE_TTL_SECONDS: Joi.number()
        .integer()
        .positive()
        .default(86_400),
    GITHUB_INSTALLATION_MEMORY_CACHE_MAX_SIZE: Joi.number()
        .integer()
        .min(1)
        .default(1000),
    GITHUB_ISSUE_MAX_RETRIES: Joi.number().integer().min(1).default(3),
    INTERNAL_API_KEY: Joi.string().trim().required(),
    GITHUB_WEBHOOK_SECRET: Joi.string().trim().required(),
    EUREKA_ENABLED: Joi.boolean().default(true),
    EUREKA_SERVER_URL: Joi.string().trim().uri({scheme: ['http', 'https']}),
    EUREKA_APP_NAME: Joi.string().trim().default('cowork-github'),
    EUREKA_LEASE_RENEWAL_SECONDS: Joi.number().integer().positive().default(30),
    APP_ADVERTISE_ADDRESS: Joi.string().trim(),
    APP_ADVERTISE_PORT: Joi.number().port(),
});
