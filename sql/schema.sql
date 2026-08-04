-- QuantumLink IM 建库建表脚本
-- 可靠性锚点:MySQL。先落库,后缓存。
-- 独立库 quantumlink(与旧项目 im 库隔离,只放本项目的表)

CREATE DATABASE IF NOT EXISTS `quantumlink` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `quantumlink`;

-- 用户表:user_id 服务端分配(用户身份)
CREATE TABLE IF NOT EXISTS `im_user` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id`       VARCHAR(64)  NOT NULL COMMENT '服务端分配,用户身份',
    `username`      VARCHAR(64)  NOT NULL COMMENT '登录名',
    `password_hash` VARCHAR(128) NOT NULL COMMENT '密码哈希',
    `avatar_url`    VARCHAR(255) NULL COMMENT '头像 URL(MinIO)',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_id` (`user_id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB COMMENT = '用户';

-- 设备表:device_id 服务端分配(区分客户端 / 多端同步基础)
CREATE TABLE IF NOT EXISTS `im_device` (
    `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `device_id`      VARCHAR(64)  NOT NULL COMMENT '服务端分配,设备身份',
    `user_id`        VARCHAR(64)  NOT NULL,
    `device_type`    VARCHAR(32)  NOT NULL COMMENT 'web / desktop / mobile',
    `token`          VARCHAR(128) NULL COMMENT '该设备登录凭证',
    `token_expire`   BIGINT       NULL,
    `last_active_at` DATETIME     NULL,
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_device_id` (`device_id`),
    KEY `idx_user` (`user_id`)
) ENGINE = InnoDB COMMENT = '设备';

-- 会话表:last_seq 事务内原子自增(seq 分配)
CREATE TABLE IF NOT EXISTS `im_conversation` (
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `conversation_id` VARCHAR(128) NOT NULL COMMENT 'A#B',
    `last_seq`        BIGINT       NOT NULL DEFAULT 0 COMMENT '事务内原子自增',
    `last_msg_id`     VARCHAR(64)  NULL,
    `last_msg_time`   DATETIME     NULL,
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conversation_id` (`conversation_id`)
) ENGINE = InnoDB COMMENT = '会话';

-- 消息表:主键自增即 server_msg_id;client_msg_id 客户端生成(幂等去重键)
CREATE TABLE IF NOT EXISTS `im_message` (
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键自增,即 server_msg_id',
    `client_msg_id`   VARCHAR(128) NOT NULL COMMENT '客户端生成(device_id+自增),幂等去重键',
    `conversation_id` VARCHAR(128) NOT NULL,
    `sender_id`       VARCHAR(64)  NOT NULL,
    `receiver_id`     VARCHAR(64)  NOT NULL,
    `msg_type`        VARCHAR(16)  NOT NULL DEFAULT 'TEXT',
    `content`         TEXT         NULL,
    `seq`             BIGINT       NOT NULL COMMENT '服务端生成,会话内单调',
    `status`          VARCHAR(16)  NOT NULL DEFAULT 'SENT' COMMENT 'SENT / DELIVERED',
    `server_time`     BIGINT       NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sender_clientmsg` (`sender_id`, `client_msg_id`),
    KEY `idx_conv_seq` (`conversation_id`, `seq`),
    KEY `idx_receiver_seq` (`receiver_id`, `seq`)
) ENGINE = InnoDB COMMENT = '消息';

-- 群表:群身份(群消息的接收方维度)
CREATE TABLE IF NOT EXISTS `im_group` (
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `group_id`   VARCHAR(64)  NOT NULL COMMENT '服务端分配,群身份',
    `name`       VARCHAR(128) NOT NULL COMMENT '群名',
    `owner_id`   VARCHAR(64)  NOT NULL COMMENT '群主 userId',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_group_id` (`group_id`)
) ENGINE = InnoDB COMMENT = '群';

-- 群成员表:群 → 成员 多对多
CREATE TABLE IF NOT EXISTS `im_group_member` (
    `id`        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `group_id`  VARCHAR(64) NOT NULL,
    `user_id`   VARCHAR(64) NOT NULL,
    `role`      VARCHAR(16) NOT NULL DEFAULT 'MEMBER' COMMENT 'OWNER / MEMBER',
    `joined_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_group_user` (`group_id`, `user_id`),
    KEY `idx_user` (`user_id`)
) ENGINE = InnoDB COMMENT = '群成员';

-- 群消息表:与单聊表分离(分库分表时群按 group_id 分片)
-- 群消息不回 DELIVER(无"对方已送达"),status 恒 SENT
CREATE TABLE IF NOT EXISTS `im_group_message` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键自增,即 server_msg_id',
    `client_msg_id` VARCHAR(128) NOT NULL COMMENT '客户端生成,幂等去重键',
    `group_id`      VARCHAR(64)  NOT NULL,
    `sender_id`     VARCHAR(64)  NOT NULL,
    `msg_type`      VARCHAR(16)  NOT NULL DEFAULT 'TEXT',
    `content`       TEXT         NULL,
    `seq`           BIGINT       NOT NULL COMMENT '群维度 Redis INCR,群内单调',
    `status`        VARCHAR(16)  NOT NULL DEFAULT 'SENT',
    `server_time`   BIGINT       NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sender_clientmsg` (`sender_id`, `client_msg_id`),
    KEY `idx_group_seq` (`group_id`, `seq`)
) ENGINE = InnoDB COMMENT = '群消息';

-- 默认测试用户(便于本地验证;生产应走注册接口)
INSERT IGNORE INTO `im_user` (`user_id`, `username`, `password_hash`)
VALUES ('user_001', 'alice', 'dev-only'),
       ('user_002', 'bob', 'dev-only');
