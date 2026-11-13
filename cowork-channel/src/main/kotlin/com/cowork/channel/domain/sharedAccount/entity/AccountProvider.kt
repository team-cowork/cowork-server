package com.cowork.channel.domain.sharedAccount.entity

enum class AccountProvider(val displayName: String?, val loginUrl: String?) {
    GITHUB("GitHub", "https://github.com/login"),
    NOTION("Notion", "https://www.notion.so/login"),
    JIRA("Jira", "https://id.atlassian.com/login"),
    GOOGLE("Google", "https://accounts.google.com"),
    FACEBOOK("Facebook", "https://www.facebook.com/login"),
    INSTAGRAM("Instagram", "https://www.instagram.com/accounts/login"),
    NPM("npm", "https://www.npmjs.com/login"),
    OPENAI("OpenAI", "https://platform.openai.com/login"),
    PYPI("PyPI", "https://pypi.org/account/login"),
    VERCEL("Vercel", "https://vercel.com/login"),
    AWS("AWS", "https://console.aws.amazon.com"),
    CUSTOM(null, null),
}
