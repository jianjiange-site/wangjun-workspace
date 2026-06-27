CREATE TABLE gateway_account (
  id BIGSERIAL PRIMARY KEY,
  account_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL DEFAULT 0,
  account_type VARCHAR(32) NOT NULL DEFAULT '',
  account_key_hash VARCHAR(128) NOT NULL DEFAULT '',
  hash_version SMALLINT NOT NULL DEFAULT 1,
  user_register_status SMALLINT NOT NULL DEFAULT 0,
  status SMALLINT NOT NULL DEFAULT 1,
  last_login_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT '1970-01-01T00:00:00Z',
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  deleted SMALLINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_gateway_account_id ON gateway_account(account_id);
CREATE UNIQUE INDEX uk_gateway_account_key ON gateway_account(account_type, account_key_hash, deleted);
CREATE UNIQUE INDEX uk_gateway_account_user ON gateway_account(user_id);

CREATE TABLE gateway_device (
  id BIGSERIAL PRIMARY KEY,
  device_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL DEFAULT 0,
  user_id BIGINT NOT NULL DEFAULT 0,
  device_key_hash VARCHAR(128) NOT NULL DEFAULT '',
  hash_version SMALLINT NOT NULL DEFAULT 1,
  device_name VARCHAR(128) NOT NULL DEFAULT '',
  client_type VARCHAR(32) NOT NULL DEFAULT '',
  status SMALLINT NOT NULL DEFAULT 1,
  last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT '1970-01-01T00:00:00Z',
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  deleted SMALLINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_gateway_device_id ON gateway_device(device_id);
CREATE UNIQUE INDEX uk_gateway_device_key ON gateway_device(device_key_hash, deleted);
CREATE INDEX idx_gateway_device_account ON gateway_device(account_id, deleted);
CREATE INDEX idx_gateway_device_user ON gateway_device(user_id, deleted);

CREATE TABLE gateway_refresh_token (
  id BIGSERIAL PRIMARY KEY,
  refresh_token_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL DEFAULT 0,
  device_id BIGINT NOT NULL DEFAULT 0,
  user_id BIGINT NOT NULL DEFAULT 0,
  token_jti VARCHAR(128) NOT NULL DEFAULT '',
  token_hash VARCHAR(128) NOT NULL DEFAULT '',
  status SMALLINT NOT NULL DEFAULT 1,
  issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  revoked_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT '1970-01-01T00:00:00Z',
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  deleted SMALLINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_gateway_refresh_token_id ON gateway_refresh_token(refresh_token_id);
CREATE UNIQUE INDEX uk_gateway_refresh_token_jti ON gateway_refresh_token(token_jti);
CREATE INDEX idx_gateway_refresh_token_account ON gateway_refresh_token(account_id, status, deleted);
CREATE INDEX idx_gateway_refresh_token_user ON gateway_refresh_token(user_id, status, deleted);
CREATE INDEX idx_gateway_refresh_token_expire ON gateway_refresh_token(expires_at, status, deleted);
