-- /mute 로 건 채널 단위 mute. 해제/만료되어도 지우지 않고 ended_* 를 채워 이력으로 남긴다.
-- 활성 mute: ended_at IS NULL AND expires_at > now
CREATE TABLE mute_rules (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    channel_id  VARCHAR(32)  NOT NULL,              -- 명령어를 입력한 Discord 채널
    keyword     VARCHAR(200) NOT NULL DEFAULT '',   -- 소문자. '' = 채널 전체
    expires_at  DATETIME(6)  NOT NULL,
    created_by  VARCHAR(32)  NOT NULL,              -- Discord user id
    created_at  DATETIME(6)  NOT NULL,
    ended_at    DATETIME(6)  NULL,
    end_reason  VARCHAR(16)  NULL,                  -- UNMUTED / EXPIRED
    ended_by    VARCHAR(32)  NULL,
    PRIMARY KEY (id),
    KEY idx_mute_active (channel_id, ended_at, expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
