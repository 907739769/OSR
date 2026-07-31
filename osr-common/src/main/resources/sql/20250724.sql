-- 优化查询性能

ALTER TABLE openlist_strm
    MODIFY COLUMN strm_path VARCHAR(1024),
    MODIFY COLUMN strm_file_name VARCHAR(255);

ALTER TABLE openlist_strm
    ADD UNIQUE INDEX idx_path_name (strm_path(255), strm_file_name);

ALTER TABLE openlist_copy
    MODIFY COLUMN copy_src_path VARCHAR(1024),
    MODIFY COLUMN copy_dst_path VARCHAR(1024),
    MODIFY COLUMN copy_src_file_name VARCHAR(255),
    MODIFY COLUMN copy_dst_file_name VARCHAR(255);

ALTER TABLE openlist_copy
    ADD INDEX idx_copy_locator (
    copy_src_path(255),
    copy_dst_path(255),
    copy_src_file_name(100),
    copy_dst_file_name(100)
);

