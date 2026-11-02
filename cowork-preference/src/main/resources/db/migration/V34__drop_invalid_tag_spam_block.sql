UPDATE resource_setting
SET settings = settings - 'tag_spam_block'
WHERE resource_type = 'TEAM'
  AND settings ? 'tag_spam_block'
  AND jsonb_typeof(settings -> 'tag_spam_block') <> 'boolean';
