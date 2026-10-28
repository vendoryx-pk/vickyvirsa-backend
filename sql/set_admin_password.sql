-- Set admin password (replace HASH from GenBcrypt CLI).
-- java -cp "target/classes;target/dependency/*" com.vendoryx.tools.GenBcrypt 'YourStrongPassword'

UPDATE admin_users
SET password_hash = '$2a$10$REPLACE_WITH_BCRYPT_HASH',
    updated_at = now()
WHERE username = 'owner';

-- Optional: create STAFF admin (hash first with GenBcrypt)
-- INSERT INTO admin_users (username, email, password_hash, full_name, role, is_active)
-- VALUES ('staff1', 'staff@vickyvirsa.pk', '$2a$10$REPLACE', 'Staff User', 'STAFF', true);
