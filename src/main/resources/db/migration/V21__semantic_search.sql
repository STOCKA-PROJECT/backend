-- V21: semantic piece search backed by XEYE.
--
-- search_lists links every organization to the XEYE list that indexes its pieces and tracks the
-- training debounce (pending_since) plus the last launch. search_entries maps each piece to its
-- XEYE element id; it keeps the organization id because a soft-deleted piece can no longer be
-- loaded when its element has to be removed.
--
-- Uses IF NOT EXISTS so a partially applied run can be repeated.

CREATE TABLE IF NOT EXISTS search_lists (
    organization_id INT NOT NULL,
    xeye_list_id BIGINT NOT NULL,
    list_name VARCHAR(100) NOT NULL,
    pending_since DATETIME(6) NULL,
    last_training_launched_at DATETIME(6) NULL,
    last_training_id BIGINT NULL,
    PRIMARY KEY (organization_id),
    CONSTRAINT fk_search_lists_organization FOREIGN KEY (organization_id) REFERENCES organizations (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS search_entries (
    piece_id INT NOT NULL,
    organization_id INT NOT NULL,
    xeye_element_id BIGINT NOT NULL,
    indexed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (piece_id),
    KEY idx_search_entries_organization (organization_id),
    CONSTRAINT fk_search_entries_piece FOREIGN KEY (piece_id) REFERENCES pieces (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
