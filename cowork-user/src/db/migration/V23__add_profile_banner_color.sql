ALTER TABLE tb_profiles
    ADD COLUMN banner_color VARCHAR(20) NULL
        COMMENT '프로필 배경 색상 프리셋 키(RED, BLUE, GREEN, AMBER, PURPLE, NEUTRAL). NULL이면 기본 배경'
        AFTER description;
