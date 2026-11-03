ALTER TABLE app_user
    ADD COLUMN email varchar(254),
    ADD COLUMN email_verified boolean NOT NULL DEFAULT false,
    ADD COLUMN updated_at timestamptz,
    ADD COLUMN identity_claims_issued_at timestamptz;

UPDATE app_user SET updated_at = created_at;
ALTER TABLE app_user ALTER COLUMN updated_at SET NOT NULL;
ALTER TABLE app_user ADD CONSTRAINT ck_app_user_verified_email
    CHECK (NOT email_verified OR email IS NOT NULL);
