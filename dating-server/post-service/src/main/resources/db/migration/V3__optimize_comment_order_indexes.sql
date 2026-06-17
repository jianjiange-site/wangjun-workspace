CREATE INDEX idx_comment_post_level_status_created_id
    ON comment (post_no, level, status, created_at ASC, id ASC);

CREATE INDEX idx_comment_root_level_status_created_id
    ON comment (root_comment_id, level, status, created_at ASC, id ASC);
