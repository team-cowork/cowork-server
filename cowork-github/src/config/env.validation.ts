import Joi from 'joi';
import {envName} from './env-name.js';

export const envValidationSchema = Joi.object({
	[envName.port]: Joi.number().port().default(8090),
	[envName.kafkaBootstrapServers]: Joi.string().trim().required(),
	[envName.kafkaSecurityProtocol]: Joi.string().uppercase().valid('PLAINTEXT', 'SSL', 'SASL_SSL').required(),
	[envName.kafkaGroupId]: Joi.string().trim().default('cowork-github-group'),
	[envName.githubAppId]: Joi.string().trim().pattern(/^\d+$/v).required(),
	[envName.githubPrivateKey]: Joi.string().trim().required(),
	[envName.redisHost]: Joi.string().trim().required(),
	[envName.redisPort]: Joi.number().port().required(),
	[envName.githubTokenCacheTtlSeconds]: Joi.number()
		.integer()
		.positive()
		.default(3300),
	[envName.githubInstallationCacheTtlSeconds]: Joi.number()
		.integer()
		.positive()
		.default(86_400),
	[envName.githubIssueMaxRetries]: Joi.number().integer().min(1).default(3),
	[envName.internalApiKey]: Joi.string().trim().required(),
	[envName.githubWebhookSecret]: Joi.string().trim().required(),
});
