import * as Joi from 'joi';

export const envValidationSchema = Joi.object({
  PORT: Joi.number().port().default(3000),
  KAFKA_BROKERS: Joi.string().trim().optional(),
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
    .default(86400),
  GITHUB_ISSUE_MAX_RETRIES: Joi.number().integer().min(1).default(3),
});
