CREATE TABLE IF NOT EXISTS signal_reports (
    hash TEXT PRIMARY KEY,
    origin_server TEXT NOT NULL,
    origin_seq INTEGER NOT NULL,
    reporter_callsign TEXT NOT NULL,
    reporter_grid TEXT,
    reporter_public_key TEXT NOT NULL,
    heard_callsign TEXT NOT NULL,
    heard_grid TEXT,
    frequency_hz INTEGER NOT NULL,
    mode TEXT,
    snr_db REAL,
    reported_at_epoch_ms INTEGER NOT NULL,
    received_at_epoch_ms INTEGER NOT NULL,
    extra_json TEXT,
    signature TEXT NOT NULL,
    origin_signature TEXT NOT NULL,
    inserted_at INTEGER NOT NULL,
    UNIQUE(origin_server, origin_seq)
);

CREATE INDEX IF NOT EXISTS idx_reports_reporter ON signal_reports(reporter_callsign);
CREATE INDEX IF NOT EXISTS idx_reports_heard ON signal_reports(heard_callsign);
CREATE INDEX IF NOT EXISTS idx_reports_freq ON signal_reports(frequency_hz);
CREATE INDEX IF NOT EXISTS idx_reports_received ON signal_reports(received_at_epoch_ms);
CREATE INDEX IF NOT EXISTS idx_reports_origin_seq ON signal_reports(origin_server, origin_seq);

CREATE TABLE IF NOT EXISTS callsign_registry (
    callsign TEXT PRIMARY KEY,
    public_key TEXT NOT NULL,
    last_seen_epoch_ms INTEGER NOT NULL,
    origin_server TEXT,
    last_report_seq INTEGER,
    registered_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS peer_watermarks (
    peer_node_id TEXT NOT NULL,
    origin_server TEXT NOT NULL,
    last_seq INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY(peer_node_id, origin_server)
);

CREATE TABLE IF NOT EXISTS peer_directory (
    node_id TEXT PRIMARY KEY,
    callsign TEXT,
    base_url TEXT NOT NULL,
    public_key TEXT NOT NULL,
    role TEXT,
    last_seen INTEGER NOT NULL,
    trusted INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS seq_counters (
    origin_server TEXT PRIMARY KEY,
    last_seq INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp INTEGER NOT NULL,
    event TEXT NOT NULL,
    actor TEXT,
    detail TEXT
);

CREATE TABLE IF NOT EXISTS seen_nonces (
    nonce TEXT PRIMARY KEY,
    expires_at INTEGER NOT NULL
);