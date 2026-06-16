CREATE TABLE post (
    id BIGINT PRIMARY KEY,
    post_no BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    content VARCHAR(2000) NOT NULL,
    image_count INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    like_count BIGINT NOT NULL DEFAULT 0,
    comment_count BIGINT NOT NULL DEFAULT 0,
    published_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_post_status CHECK (status IN ('PUBLISHED', 'USER_DELETED', 'AUDIT_REJECTED')),
    CONSTRAINT ck_post_image_count CHECK (image_count >= 0),
    CONSTRAINT ck_post_like_count CHECK (like_count >= 0),
    CONSTRAINT ck_post_comment_count CHECK (comment_count >= 0),
    CONSTRAINT uk_post_no UNIQUE (post_no)
);

CREATE INDEX idx_author_status_created ON post (author_id, status, created_at DESC);
CREATE INDEX idx_status_created ON post (status, created_at DESC);
CREATE INDEX idx_hot_window ON post (status, published_at DESC, like_count, comment_count);

CREATE TABLE post_image (
    id BIGINT PRIMARY KEY,
    image_no BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    post_id BIGINT,
    bucket VARCHAR(128) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    etag VARCHAR(128),
    width INTEGER,
    height INTEGER,
    sort_order INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    upload_expire_at TIMESTAMPTZ NOT NULL,
    bound_at TIMESTAMPTZ,
    retry_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_post_image_status CHECK (status IN ('TEMP', 'BOUND', 'CLEANING', 'CLEANED', 'DELETE_FAILED')),
    CONSTRAINT ck_post_image_size CHECK (size_bytes >= 0),
    CONSTRAINT ck_post_image_retry_count CHECK (retry_count >= 0),
    CONSTRAINT uk_post_image_no UNIQUE (image_no)
);

CREATE INDEX idx_user_status ON post_image (user_id, status);
CREATE INDEX idx_temp_expire ON post_image (status, upload_expire_at);
CREATE INDEX idx_post_sort ON post_image (post_id, sort_order);

CREATE TABLE post_like (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    post_author_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_user_post UNIQUE (user_id, post_id)
);

CREATE INDEX idx_user_created ON post_like (user_id, created_at DESC);
CREATE INDEX idx_user_author_created ON post_like (user_id, post_author_id, created_at DESC);

CREATE TABLE comment (
    id BIGINT PRIMARY KEY,
    comment_no BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    root_comment_id BIGINT NOT NULL,
    parent_comment_id BIGINT,
    reply_to_user_id BIGINT,
    content VARCHAR(1000) NOT NULL,
    level INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uk_comment_no UNIQUE (comment_no),
    CONSTRAINT ck_comment_level CHECK (level IN (1, 2)),
    CONSTRAINT ck_comment_status CHECK (status IN ('NORMAL', 'USER_DELETED'))
);

CREATE INDEX idx_comment_post_status_created ON comment (post_id, status, created_at DESC);
CREATE INDEX idx_comment_root_created ON comment (root_comment_id, created_at ASC);
CREATE INDEX idx_comment_author_created ON comment (author_id, created_at DESC);

CREATE TABLE idempotent_request (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    operation_type VARCHAR(64) NOT NULL,
    client_request_id VARCHAR(128) NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    biz_no BIGINT,
    response_snapshot TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    expire_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_user_op_req UNIQUE (user_id, operation_type, client_request_id)
);

CREATE INDEX idx_idempotent_expire ON idempotent_request (expire_at);
