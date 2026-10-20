CREATE TABLE IF NOT EXISTS config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    config_key VARCHAR(100) UNIQUE,
    config_value TEXT,
    config_type VARCHAR(30),
    category VARCHAR(50),
    description TEXT,
    created_at DATETIME,
    updated_at DATETIME
);
