DROP INDEX IF EXISTS idx_comment_root_level_status_created_id;
DROP INDEX IF EXISTS idx_comment_post_level_status_created_id;
DROP INDEX IF EXISTS idx_comment_root_created;

ALTER TABLE comment ADD COLUMN root_comment_no BIGINT;
ALTER TABLE comment ADD COLUMN parent_comment_no BIGINT DEFAULT -1;

UPDATE comment
SET root_comment_no = (
    SELECT root.comment_no
    FROM comment root
    WHERE root.id = comment.root_comment_id
);

UPDATE comment
SET root_comment_no = comment_no
WHERE level = 1
  AND root_comment_no IS NULL;

UPDATE comment
SET parent_comment_no = (
    SELECT parent_comment.comment_no
    FROM comment parent_comment
    WHERE parent_comment.id = comment.parent_comment_id
)
WHERE parent_comment_id <> -1;

UPDATE comment
SET parent_comment_no = -1
WHERE parent_comment_no IS NULL;

ALTER TABLE comment ALTER COLUMN root_comment_no SET NOT NULL;
ALTER TABLE comment ALTER COLUMN parent_comment_no SET DEFAULT -1;
ALTER TABLE comment ALTER COLUMN parent_comment_no SET NOT NULL;

ALTER TABLE comment DROP COLUMN root_comment_id;
ALTER TABLE comment DROP COLUMN parent_comment_id;

CREATE INDEX idx_comment_post_level_status_created_no
    ON comment (post_no, level, status, created_at ASC, comment_no ASC);

CREATE INDEX idx_comment_root_level_status_created_no
    ON comment (root_comment_no, level, status, created_at ASC, comment_no ASC);
