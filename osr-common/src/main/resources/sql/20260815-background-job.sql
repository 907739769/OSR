CREATE TABLE IF NOT EXISTS osr_background_job (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  owner_id BIGINT NOT NULL,
  kind VARCHAR(40) NOT NULL,
  state VARCHAR(24) NOT NULL,
  items_json MEDIUMTEXT NOT NULL,
  cancel_requested TINYINT(1) NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_background_owner_time(owner_id, create_time),
  INDEX idx_background_state(state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
