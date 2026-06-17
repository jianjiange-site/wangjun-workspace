UPDATE post
SET deleted_at = '1970-01-01 00:00:00+00'
WHERE deleted_at IS NULL;

ALTER TABLE post ALTER COLUMN deleted_at SET DEFAULT '1970-01-01 00:00:00+00';
ALTER TABLE post ALTER COLUMN deleted_at SET NOT NULL;

UPDATE post_image
SET post_no = -1
WHERE post_no IS NULL;

UPDATE post_image
SET etag = ''
WHERE etag IS NULL;

UPDATE post_image
SET width = -1
WHERE width IS NULL;

UPDATE post_image
SET height = -1
WHERE height IS NULL;

UPDATE post_image
SET bound_at = '1970-01-01 00:00:00+00'
WHERE bound_at IS NULL;

ALTER TABLE post_image ALTER COLUMN post_no SET DEFAULT -1;
ALTER TABLE post_image ALTER COLUMN post_no SET NOT NULL;
ALTER TABLE post_image ALTER COLUMN etag SET DEFAULT '';
ALTER TABLE post_image ALTER COLUMN etag SET NOT NULL;
ALTER TABLE post_image ALTER COLUMN width SET DEFAULT -1;
ALTER TABLE post_image ALTER COLUMN width SET NOT NULL;
ALTER TABLE post_image ALTER COLUMN height SET DEFAULT -1;
ALTER TABLE post_image ALTER COLUMN height SET NOT NULL;
ALTER TABLE post_image ALTER COLUMN bound_at SET DEFAULT '1970-01-01 00:00:00+00';
ALTER TABLE post_image ALTER COLUMN bound_at SET NOT NULL;

UPDATE comment
SET parent_comment_id = -1
WHERE parent_comment_id IS NULL;

UPDATE comment
SET reply_to_user_id = -1
WHERE reply_to_user_id IS NULL;

UPDATE comment
SET deleted_at = '1970-01-01 00:00:00+00'
WHERE deleted_at IS NULL;

ALTER TABLE comment ALTER COLUMN parent_comment_id SET DEFAULT -1;
ALTER TABLE comment ALTER COLUMN parent_comment_id SET NOT NULL;
ALTER TABLE comment ALTER COLUMN reply_to_user_id SET DEFAULT -1;
ALTER TABLE comment ALTER COLUMN reply_to_user_id SET NOT NULL;
ALTER TABLE comment ALTER COLUMN deleted_at SET DEFAULT '1970-01-01 00:00:00+00';
ALTER TABLE comment ALTER COLUMN deleted_at SET NOT NULL;

UPDATE idempotent_request
SET biz_no = -1
WHERE biz_no IS NULL;

UPDATE idempotent_request
SET response_snapshot = ''
WHERE response_snapshot IS NULL;

ALTER TABLE idempotent_request ALTER COLUMN biz_no SET DEFAULT -1;
ALTER TABLE idempotent_request ALTER COLUMN biz_no SET NOT NULL;
ALTER TABLE idempotent_request ALTER COLUMN response_snapshot SET DEFAULT '';
ALTER TABLE idempotent_request ALTER COLUMN response_snapshot SET NOT NULL;
