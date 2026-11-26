import {Injectable as injectable} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {LazyKafkaProducer} from '../../common/kafka/lazy-kafka-producer.js';
import {ChatGithubIssueCreateCommand} from './event/chat-github-issue.event.js';

@injectable()
export class ChatGithubIssueCommandProducer extends LazyKafkaProducer {
	constructor(configService: ConfigService) {
		super(configService, {clientId: 'cowork-chat-chat-github-issue-command', label: 'Chat GitHub issue command producer'});
	}

	/**
     같은 채널의 이슈 요청이 순서대로 전달되도록 `channelId`를 키로 쓴다.
     */
	async send(command: ChatGithubIssueCreateCommand): Promise<void> {
		await this.publish([
			{
				topic: 'project.chat-github-issue.command',
				key: command.channelId.toString(),
				value: JSON.stringify(command),
			},
		]);
	}
}
