package com.cowork.channel.domain

enum class AccountProvider(
    val displayName: String?,
    val loginUrl: String?,
    val oauthSupported: Boolean,
) {
    GITHUB("GitHub", "https://github.com/login", true),
    NOTION("Notion", "https://www.notion.so/login", true),
    JIRA("Jira", "https://id.atlassian.com/login", true),
    GOOGLE("Google", "https://accounts.google.com", true),
    FACEBOOK("Facebook", "https://www.facebook.com/login", true),
    INSTAGRAM("Instagram", "https://www.instagram.com/accounts/login", false),
    NPM("npm", "https://www.npmjs.com/login", false),
    OPENAI("OpenAI", "https://platform.openai.com/login", false),
    PYPI("PyPI", "https://pypi.org/account/login", false),
    VERCEL("Vercel", "https://vercel.com/login", false),
    AWS("AWS", "https://console.aws.amazon.com", false),
    CUSTOM(null, null, false),
}
