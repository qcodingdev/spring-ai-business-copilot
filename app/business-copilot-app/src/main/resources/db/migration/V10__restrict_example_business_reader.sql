-- V10: make the example database reader a true least-privilege boundary.
--
-- The login role is created by examples/postgres-init before Flyway starts.
-- Flyway owns the tables, so grants are applied here after every business and
-- platform table exists. A fixed NOLOGIN group supports custom reader usernames.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'business_copilot_reader') THEN
        CREATE ROLE business_copilot_reader
            NOLOGIN
            NOSUPERUSER
            NOCREATEDB
            NOCREATEROLE
            NOREPLICATION
            NOBYPASSRLS;
    END IF;
END
$$;

ALTER ROLE business_copilot_reader
    NOLOGIN
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOREPLICATION
    NOBYPASSRLS;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM business_copilot_reader;
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM business_copilot_reader;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM business_copilot_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    REVOKE ALL PRIVILEGES ON TABLES FROM business_copilot_reader;

GRANT USAGE ON SCHEMA public TO business_copilot_reader;
GRANT SELECT ON TABLE
    public.customers,
    public.products,
    public.orders,
    public.order_items,
    public.refunds,
    public.marketing_events
TO business_copilot_reader;

-- Repair the default role on existing example volumes created before V10.
DO $$
DECLARE
    owner_role TEXT := current_user;
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'business_reader') THEN
        ALTER ROLE business_reader
            INHERIT
            NOSUPERUSER
            NOCREATEDB
            NOCREATEROLE
            NOREPLICATION
            NOBYPASSRLS;
        REVOKE CREATE ON SCHEMA public FROM business_reader;
        REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM business_reader;
        REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM business_reader;
        EXECUTE format(
            'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public '
            'REVOKE ALL PRIVILEGES ON TABLES FROM business_reader',
            owner_role
        );
        GRANT business_copilot_reader TO business_reader;
    END IF;
END
$$;
