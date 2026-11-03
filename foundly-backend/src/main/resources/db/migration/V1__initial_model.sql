CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE app_user (
    id uuid PRIMARY KEY,
    auth_issuer varchar(512) NOT NULL,
    auth_subject varchar(255) NOT NULL,
    display_name varchar(100) NOT NULL CHECK (length(trim(display_name)) > 0),
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_app_user_identity UNIQUE (auth_issuer, auth_subject)
);

CREATE TABLE category (
    id uuid PRIMARY KEY,
    code varchar(40) NOT NULL UNIQUE,
    name varchar(100) NOT NULL,
    sort_order integer NOT NULL CHECK (sort_order >= 0),
    active boolean NOT NULL DEFAULT true
);

CREATE TABLE report (
    id uuid PRIMARY KEY,
    author_id uuid NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    category_id uuid NOT NULL REFERENCES category(id) ON DELETE RESTRICT,
    title varchar(150) NOT NULL CHECK (length(trim(title)) BETWEEN 3 AND 150),
    type varchar(10) NOT NULL CHECK (type IN ('LOST', 'FOUND')),
    status varchar(12) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'RESOLVED')),
    description text NOT NULL CHECK (length(trim(description)) BETWEEN 10 AND 5000),
    color varchar(80),
    location_label varchar(255) NOT NULL CHECK (length(trim(location_label)) BETWEEN 3 AND 255),
    location_point geography(Point,4326) NOT NULL,
    event_date date NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    resolved_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_report_resolution CHECK (
        (status = 'ACTIVE' AND resolved_at IS NULL)
        OR (status = 'RESOLVED' AND resolved_at IS NOT NULL)
    )
);

CREATE INDEX ix_report_author ON report(author_id, created_at DESC, id DESC);
CREATE INDEX ix_report_category_active ON report(category_id, type, event_date) WHERE status = 'ACTIVE';
CREATE INDEX ix_report_created_active ON report(created_at DESC, id DESC) WHERE status = 'ACTIVE';
CREATE INDEX ix_report_location_active ON report USING gist(location_point) WHERE status = 'ACTIVE';

INSERT INTO category(id, code, name, sort_order) VALUES
('00000000-0000-0000-0000-000000000001', 'ELECTRONICS', 'Elektronika', 1),
('00000000-0000-0000-0000-000000000002', 'KEYS', 'Klucze', 2),
('00000000-0000-0000-0000-000000000003', 'WALLETS_DOCUMENTS', 'Portfele i dokumenty', 3),
('00000000-0000-0000-0000-000000000004', 'BAGS_BACKPACKS', 'Torby i plecaki', 4),
('00000000-0000-0000-0000-000000000005', 'JEWELLERY', 'Biżuteria', 5),
('00000000-0000-0000-0000-000000000006', 'OTHER', 'Inne', 6);
