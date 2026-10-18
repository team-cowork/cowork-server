ALTER TABLE tb_user_profiles
    ADD COLUMN profile_image_key VARCHAR(500) DEFAULT NULL
        COMMENT 'S3 object key (profiles/{userId}/{uuid}.ext)'
        AFTER github_id;
