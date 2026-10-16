-- Aurora DSQL's dialect inside a cluster's PostgreSQL, installed by Floci as the superuser dbowner
-- every time the database starts. Every statement is idempotent. What DSQL answers was measured on
-- AWS (eu-west-1, 2026-10-16); the messages and SQLSTATEs below are its own.
SET dsql.internal = 'on';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'admin') THEN
        CREATE ROLE admin WITH LOGIN CREATEROLE CREATEDB;
    END IF;
END
$$;
GRANT CREATE ON DATABASE postgres TO admin;
ALTER SCHEMA public OWNER TO admin;
ALTER DATABASE postgres SET default_transaction_isolation TO 'repeatable read';

CREATE SCHEMA IF NOT EXISTS sys AUTHORIZATION dbowner;
GRANT USAGE ON SCHEMA sys TO PUBLIC;

-- ── Identifiers: a UUID as 26 characters of lowercase base32 ──────────────────

CREATE OR REPLACE FUNCTION sys.uuid_to_base32_str(u uuid) RETURNS text
LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE
    alphabet constant text := 'abcdefghijklmnopqrstuvwxyz234567';
    bytes bytea := uuid_send(u);
    buffer bigint := 0;
    bits int := 0;
    result text := '';
BEGIN
    FOR i IN 0..15 LOOP
        buffer := (buffer << 8) | get_byte(bytes, i);
        bits := bits + 8;
        WHILE bits >= 5 LOOP
            result := result || substr(alphabet, ((buffer >> (bits - 5)) & 31)::int + 1, 1);
            bits := bits - 5;
        END LOOP;
        buffer := buffer & ((1::bigint << bits) - 1);
    END LOOP;
    RETURN result || substr(alphabet, ((buffer << (5 - bits)) & 31)::int + 1, 1);
END
$$;

CREATE OR REPLACE FUNCTION sys.base32_str_to_uuid(s text) RETURNS uuid
LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE
    alphabet constant text := 'abcdefghijklmnopqrstuvwxyz234567';
    value int;
    buffer bigint := 0;
    bits int := 0;
    hex text := '';
BEGIN
    IF length(s) <> 26 THEN
        RAISE EXCEPTION USING ERRCODE = '22P02', MESSAGE = 'Unable to convert text to UUID';
    END IF;
    FOR i IN 1..26 LOOP
        value := strpos(alphabet, substr(lower(s), i, 1)) - 1;
        IF value < 0 THEN
            RAISE EXCEPTION USING ERRCODE = '22P02', MESSAGE = 'Unable to convert text to UUID';
        END IF;
        buffer := (buffer << 5) | value;
        bits := bits + 5;
        IF bits >= 8 THEN
            hex := hex || lpad(to_hex((buffer >> (bits - 8)) & 255), 2, '0');
            bits := bits - 8;
            buffer := buffer & ((1::bigint << bits) - 1);
        END IF;
    END LOOP;
    IF buffer <> 0 THEN
        RAISE EXCEPTION USING ERRCODE = '22P02', MESSAGE = 'Unable to convert text to UUID';
    END IF;
    RETURN hex::uuid;
END
$$;

-- ── Sessions and the cluster ──────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION sys.iam_session_user() RETURNS name
LANGUAGE sql STABLE AS $$ SELECT current_setting('dsql.iam_session_user', true)::name $$;

CREATE OR REPLACE FUNCTION sys.current_session_id() RETURNS text
LANGUAGE sql STABLE AS $$ SELECT current_setting('dsql.session_id', true) $$;

CREATE OR REPLACE FUNCTION sys.dsql_major_version() RETURNS integer
LANGUAGE sql IMMUTABLE AS $$ SELECT 1 $$;

CREATE OR REPLACE FUNCTION sys.cluster_size_limit() RETURNS bigint
LANGUAGE sql IMMUTABLE AS $$ SELECT 10995116277760::bigint $$;

CREATE OR REPLACE FUNCTION sys.cluster_size() RETURNS bigint
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$ SELECT pg_database_size('postgres') $$;

CREATE OR REPLACE VIEW sys.roles AS
SELECT rolname, rolsuper, rolinherit, rolcreaterole, rolcreatedb, rolcanlogin, rolreplication,
       rolconnlimit, '********'::text AS rolpassword, rolvaliduntil, rolbypassrls,
       s.setconfig AS rolconfig, pg_authid.oid
FROM pg_authid
LEFT JOIN pg_db_role_setting s ON pg_authid.oid = s.setrole AND s.setdatabase = 0::oid
WHERE pg_authid.rolname !~~ 'pg_%';
GRANT SELECT ON sys.roles TO PUBLIC;

-- ── A transaction's limits: one DDL statement, no DDL with DML, 3,000 rows ────

CREATE OR REPLACE FUNCTION sys._setting(name text) RETURNS bigint
LANGUAGE sql STABLE AS $$ SELECT coalesce(nullif(current_setting(name, true), ''), '0')::bigint $$;

CREATE OR REPLACE FUNCTION sys._count_ddl() RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    ddl bigint := sys._setting('dsql.ddl_count');
    dml bigint := sys._setting('dsql.dml_count');
BEGIN
    IF ddl >= 1 THEN
        RAISE EXCEPTION USING ERRCODE = '0A000',
            MESSAGE = 'multiple ddl statements not supported in a transaction';
    END IF;
    IF dml >= 1 THEN
        RAISE EXCEPTION USING ERRCODE = '0A000',
            MESSAGE = 'ddl and dml are not supported in the same transaction',
            DETAIL = format('ddl count: %s, dml count: %s', ddl + 1, dml);
    END IF;
    PERFORM set_config('dsql.ddl_count', (ddl + 1)::text, true);
END
$$;

CREATE OR REPLACE FUNCTION sys._count_dml() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    changed bigint;
    ddl bigint := sys._setting('dsql.ddl_count');
    dml bigint := sys._setting('dsql.dml_count') + 1;
    total bigint;
BEGIN
    SELECT count(*) INTO changed FROM dsql_rows;
    IF ddl >= 1 THEN
        RAISE EXCEPTION USING ERRCODE = '0A000',
            MESSAGE = 'ddl and dml are not supported in the same transaction',
            DETAIL = format('ddl count: %s, dml count: %s', ddl, dml);
    END IF;
    total := sys._setting('dsql.row_count') + changed;
    IF total > 3000 THEN
        RAISE EXCEPTION USING ERRCODE = '54000', MESSAGE = 'transaction row limit exceeded';
    END IF;
    PERFORM set_config('dsql.dml_count', dml::text, true);
    PERFORM set_config('dsql.row_count', total::text, true);
    RETURN NULL;
END
$$;

-- Every table a client creates gets three statement triggers that count its writes.
CREATE OR REPLACE FUNCTION sys._watch_table(relation regclass) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE format('CREATE TRIGGER dsql_rows_inserted AFTER INSERT ON %s REFERENCING NEW TABLE AS dsql_rows'
        ' FOR EACH STATEMENT EXECUTE FUNCTION sys._count_dml()', relation);
    EXECUTE format('CREATE TRIGGER dsql_rows_updated AFTER UPDATE ON %s REFERENCING NEW TABLE AS dsql_rows'
        ' FOR EACH STATEMENT EXECUTE FUNCTION sys._count_dml()', relation);
    EXECUTE format('CREATE TRIGGER dsql_rows_deleted AFTER DELETE ON %s REFERENCING OLD TABLE AS dsql_rows'
        ' FOR EACH STATEMENT EXECUTE FUNCTION sys._count_dml()', relation);
END
$$;

CREATE OR REPLACE FUNCTION sys._reject(state text, message text, detail text DEFAULT NULL) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    IF detail IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = state, MESSAGE = message;
    END IF;
    RAISE EXCEPTION USING ERRCODE = state, MESSAGE = message, DETAIL = detail;
END
$$;

-- ── What DDL DSQL refuses, read from the catalog ───────────────────────────────

CREATE OR REPLACE FUNCTION sys._on_ddl_start() RETURNS event_trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF current_setting('dsql.internal', true) = 'on' THEN
        RETURN;
    END IF;
    IF tg_tag = 'CREATE INDEX' AND coalesce(current_setting('dsql.async_index', true), '') <> 'on' THEN
        RAISE EXCEPTION USING ERRCODE = '0A000', MESSAGE = 'unsupported mode. please use CREATE INDEX ASYNC.';
    END IF;
    IF tg_tag = 'CREATE TRIGGER' THEN
        RAISE EXCEPTION USING ERRCODE = '0A000', MESSAGE = 'CREATE TRIGGER not supported';
    END IF;
    IF tg_tag = 'CREATE TYPE' THEN
        RAISE EXCEPTION USING ERRCODE = '0A000', MESSAGE = 'CREATE TYPE not supported';
    END IF;
    IF tg_tag = 'CREATE EXTENSION' THEN
        RAISE EXCEPTION USING ERRCODE = '0A000', MESSAGE = 'unsupported statement: CreateExtension';
    END IF;
    PERFORM sys._count_ddl();
END
$$;

CREATE OR REPLACE FUNCTION sys._on_ddl_end() RETURNS event_trigger
LANGUAGE plpgsql AS $$
DECLARE
    command record;
    relation record;
    key_type text;
BEGIN
    IF current_setting('dsql.internal', true) = 'on' THEN
        RETURN;
    END IF;
    FOR command IN SELECT * FROM pg_event_trigger_ddl_commands() LOOP
        IF command.object_type IN ('table', 'index') THEN
            SELECT c.relkind, c.relpersistence INTO relation FROM pg_class c WHERE c.oid = command.objid;
            IF relation.relpersistence = 't' THEN
                RAISE EXCEPTION USING ERRCODE = '0A000',
                    MESSAGE = 'TEMPORARY or TEMP table not supported for CREATE TABLE';
            END IF;
            IF relation.relkind = 'p' THEN
                RAISE EXCEPTION USING ERRCODE = '0A000',
                    MESSAGE = 'PARTITION BY clause not supported for CREATE TABLE';
            END IF;
            IF command.object_type = 'table' THEN
                SELECT format_type(a.atttypid, a.atttypmod) INTO key_type
                FROM pg_attribute a JOIN pg_type t ON t.oid = a.atttypid
                WHERE a.attrelid = command.objid AND a.attnum > 0 AND NOT a.attisdropped AND t.typcategory = 'A'
                LIMIT 1;
                IF key_type IS NOT NULL THEN
                    RAISE EXCEPTION USING ERRCODE = '0A000', MESSAGE = format('datatype %s not supported', key_type);
                END IF;
            END IF;
            -- A key may not hold jsonb or bytea: the table's own unique keys, or the index created.
            SELECT format_type(a.atttypid, a.atttypmod) INTO key_type
            FROM pg_index i
            JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)
            WHERE (i.indrelid = command.objid OR i.indexrelid = command.objid)
              AND a.atttypid IN ('jsonb'::regtype, 'bytea'::regtype)
            LIMIT 1;
            IF key_type IS NOT NULL THEN
                RAISE EXCEPTION USING ERRCODE = '0A000', MESSAGE = format('datatype %s is not supported in a key', key_type);
            END IF;
            IF command.object_type = 'table' AND command.command_tag IN ('CREATE TABLE', 'CREATE TABLE AS', 'SELECT INTO')
               AND relation.relkind = 'r' THEN
                PERFORM set_config('dsql.internal', 'on', true);
                PERFORM sys._watch_table(command.objid::regclass);
                PERFORM set_config('dsql.internal', 'off', true);
            END IF;
        ELSIF command.object_type IN ('function', 'procedure') THEN
            IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_language l ON l.oid = p.prolang
                       WHERE p.oid = command.objid AND l.lanname = 'plpgsql') THEN
                RAISE EXCEPTION USING ERRCODE = '0A000',
                    MESSAGE = format('CREATE %s with language plpgsql not supported', upper(command.object_type));
            END IF;
        END IF;
    END LOOP;
END
$$;

DROP EVENT TRIGGER IF EXISTS dsql_ddl_start;
CREATE EVENT TRIGGER dsql_ddl_start ON ddl_command_start EXECUTE FUNCTION sys._on_ddl_start();
DROP EVENT TRIGGER IF EXISTS dsql_ddl_end;
CREATE EVENT TRIGGER dsql_ddl_end ON ddl_command_end EXECUTE FUNCTION sys._on_ddl_end();

-- ── Asynchronous jobs: CREATE INDEX ASYNC and its job ─────────────────────────

CREATE TABLE IF NOT EXISTS sys.job (
    job_id uuid PRIMARY KEY,
    status "char" NOT NULL,
    details text,
    job_type text,
    class_id oid,
    object_id oid,
    object_name text,
    start_time timestamptz,
    update_time timestamptz
);
GRANT SELECT ON sys.job TO PUBLIC;

CREATE OR REPLACE VIEW sys.jobs AS
SELECT sys.uuid_to_base32_str(job_id) AS job_id,
       CASE status WHEN 's' THEN 'submitted' WHEN 'p' THEN 'processing' WHEN 'c' THEN 'completed'
                   WHEN 'f' THEN 'failed' WHEN 'x' THEN 'cancelled' END AS status,
       details, job_type, class_id, object_id, object_name, start_time, update_time
FROM sys.job;
GRANT SELECT ON sys.jobs TO PUBLIC;

-- The job and the invalid index below are written as dbowner, so each first checks that the caller
-- owns the index it names: a client can record or invalidate only what it built itself.
CREATE OR REPLACE FUNCTION sys._require_index_owner(index_oid oid) RETURNS void
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF index_oid IS NULL OR NOT coalesce(pg_has_role(session_user,
            (SELECT c.relowner FROM pg_class c WHERE c.oid = index_oid AND c.relkind = 'i'), 'USAGE'), false) THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'must be owner of index';
    END IF;
END
$$;

CREATE OR REPLACE FUNCTION sys._record_job(job uuid, state "char", detail text, kind text, object oid,
                                           object_label text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM sys._require_index_owner(object);
    INSERT INTO sys.job VALUES (job, state, detail, kind, 1259, object, object_label, now(), clock_timestamp());
END
$$;

-- An index whose build failed stays, invalid, as DSQL leaves it.
CREATE OR REPLACE FUNCTION sys._invalidate_index(index_oid oid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM sys._require_index_owner(index_oid);
    UPDATE pg_index SET indisvalid = false WHERE indexrelid = index_oid;
END
$$;

-- CREATE INDEX ASYNC, which Floci's endpoint hands here with ASYNC taken out: the index is built at
-- once, and a job records it. A unique index over duplicate keys fails its job.
CREATE OR REPLACE FUNCTION sys.create_index_async(statement text, index_name text, table_name text)
RETURNS TABLE (job_id text)
LANGUAGE plpgsql AS $$
DECLARE
    job uuid := gen_random_uuid();
    target regclass := to_regclass(table_name);
    schema_name text;
    index_oid oid;
BEGIN
    SELECT n.nspname INTO schema_name FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.oid = target;
    PERFORM set_config('dsql.async_index', 'on', true);
    BEGIN
        EXECUTE statement;
        SELECT i.indexrelid INTO index_oid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
        WHERE i.indrelid = target AND (index_name IS NULL OR c.relname = index_name)
        ORDER BY i.indexrelid DESC LIMIT 1;
        PERFORM sys._record_job(job, 'c', NULL, 'INDEX_BUILD', index_oid,
            coalesce(schema_name, current_schema()) || '.' || coalesce(index_name, (SELECT relname FROM pg_class WHERE oid = index_oid)));
    EXCEPTION WHEN unique_violation THEN
        EXECUTE regexp_replace(statement, '^\s*CREATE\s+UNIQUE\s+INDEX', 'CREATE INDEX', 'i');
        SELECT i.indexrelid INTO index_oid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
        WHERE i.indrelid = target AND c.relname = index_name;
        PERFORM sys._invalidate_index(index_oid);
        PERFORM sys._record_job(job, 'f', 'found duplicate key(s) while validating index uniqueness',
            'INDEX_BUILD', index_oid, coalesce(schema_name, current_schema()) || '.' || index_name);
    END;
    PERFORM set_config('dsql.async_index', 'off', true);
    RETURN QUERY SELECT sys.uuid_to_base32_str(job);
END
$$;

-- DSQL's own procedure, as its catalog holds it.
CREATE OR REPLACE PROCEDURE sys.wait_for_job(IN job_uuid_base32 text, INOUT succeeded boolean DEFAULT false)
LANGUAGE plpgsql AS $$
DECLARE
job_uuid uuid := sys.base32_str_to_uuid(job_uuid_base32);
BEGIN
LOOP
    IF EXISTS (SELECT 1 FROM sys.job WHERE job_id = job_uuid AND status = 'c') THEN
        RAISE NOTICE 'Job execution succeeded for job with id %', job_uuid_base32;
        succeeded := true;
        EXIT;
    ELSIF (SELECT 1 FROM sys.job WHERE job_id = job_uuid AND status = 'f') THEN
        RAISE NOTICE 'Job execution failed for job with id %', job_uuid_base32;
        succeeded := false;
        EXIT;
    ELSIF EXISTS (SELECT 1 FROM sys.job WHERE job_id = job_uuid AND status = 'x') THEN
        RAISE NOTICE 'Job execution was cancelled for job with id %', job_uuid_base32;
        succeeded := false;
        EXIT;
    END IF;
    PERFORM pg_sleep(1);
    COMMIT;
END LOOP;
RETURN;
END;
$$;

-- ── AWS IAM GRANT and REVOKE: which IAM role may log in as which database role ─

CREATE TABLE IF NOT EXISTS sys.iam_identity (
    oid bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    arn text COLLATE "C" NOT NULL UNIQUE
);
CREATE TABLE IF NOT EXISTS sys.iam_auth_member (
    member bigint NOT NULL REFERENCES sys.iam_identity (oid) ON DELETE CASCADE,
    roleid oid NOT NULL,
    grantor oid NOT NULL,
    PRIMARY KEY (member, roleid)
);

CREATE OR REPLACE VIEW sys.iam_pg_role_mappings AS
SELECT i.oid::oid AS iam_oid, i.arn, m.roleid AS pg_role_oid, a.rolname AS pg_role_name,
       m.grantor AS grantor_pg_role_oid, b.rolname AS grantor_pg_role_name
FROM sys.iam_identity i
JOIN sys.iam_auth_member m ON i.oid = m.member
LEFT JOIN pg_authid a ON a.oid = m.roleid
LEFT JOIN pg_authid b ON b.oid = m.grantor;
GRANT SELECT ON sys.iam_pg_role_mappings TO PUBLIC;

-- The role an IAM role may be trusted with: one that exists, is no superuser, and on which the caller
-- holds the ADMIN option, which admin holds on every role it created and on none other, itself
-- included.
CREATE OR REPLACE FUNCTION sys._trusted_role(role_name text, verb text, preposition text) RETURNS oid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    role_oid oid;
    superuser boolean;
BEGIN
    SELECT oid, rolsuper INTO role_oid, superuser FROM pg_roles WHERE rolname = role_name;
    IF role_oid IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '42704', MESSAGE = format('role "%s" does not exist', role_name);
    END IF;
    IF superuser THEN
        RAISE EXCEPTION USING ERRCODE = '0LP01', MESSAGE = format('cannot establish trust with role "%s"', role_name),
            DETAIL = 'cannot GRANT/REVOKE IAM for roles with the SUPERUSER attribute';
    END IF;
    IF NOT pg_has_role(session_user, role_oid, 'MEMBER WITH ADMIN OPTION') THEN
        RAISE EXCEPTION USING ERRCODE = '42501',
            MESSAGE = format('permission denied to %s IAM trust with role "%s"', verb, role_name),
            DETAIL = format('Only roles with the ADMIN option on role "%s" may %s IAM trust %s this role.',
                            role_name, verb, preposition);
    END IF;
    RETURN role_oid;
END
$$;

CREATE OR REPLACE PROCEDURE sys.aws_iam_grant(role_name text, iam_arn text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    role_oid oid;
    identity bigint;
    granted_by name;
BEGIN
    PERFORM sys._count_ddl();
    IF iam_arn !~ '^arn:aws[a-z-]*:iam::[0-9]{12}:role/.+' THEN
        RAISE EXCEPTION USING ERRCODE = '0P000', MESSAGE = 'IAM role must look like an IAM ARN';
    END IF;
    role_oid := sys._trusted_role(role_name, 'grant', 'to');
    INSERT INTO sys.iam_identity (arn) VALUES (iam_arn) ON CONFLICT (arn) DO NOTHING;
    SELECT oid INTO identity FROM sys.iam_identity WHERE arn = iam_arn;
    SELECT b.rolname INTO granted_by FROM sys.iam_auth_member m JOIN pg_authid b ON b.oid = m.grantor
    WHERE m.member = identity AND m.roleid = role_oid;
    IF granted_by IS NOT NULL THEN
        RAISE NOTICE 'IAM role "%" has already been granted access to role "%" by role "%"',
            iam_arn, role_name, granted_by;
        RETURN;
    END IF;
    INSERT INTO sys.iam_auth_member (member, roleid, grantor)
    VALUES (identity, role_oid, (SELECT oid FROM pg_roles WHERE rolname = session_user));
END
$$;

CREATE OR REPLACE PROCEDURE sys.aws_iam_revoke(role_name text, iam_arn text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    role_oid oid;
    identity bigint;
BEGIN
    PERFORM sys._count_ddl();
    IF iam_arn !~ '^arn:aws[a-z-]*:iam::[0-9]{12}:role/.+' THEN
        RAISE EXCEPTION USING ERRCODE = '0P000', MESSAGE = 'IAM role must look like an IAM ARN';
    END IF;
    role_oid := sys._trusted_role(role_name, 'revoke', 'from');
    SELECT oid INTO identity FROM sys.iam_identity WHERE arn = iam_arn;
    IF identity IS NULL OR NOT EXISTS (SELECT 1 FROM sys.iam_auth_member WHERE member = identity AND roleid = role_oid) THEN
        RAISE EXCEPTION USING ERRCODE = '42704', MESSAGE = format('IAM role "%s" does not exist', iam_arn);
    END IF;
    DELETE FROM sys.iam_auth_member WHERE member = identity AND roleid = role_oid;
    DELETE FROM sys.iam_identity i WHERE i.oid = identity
        AND NOT EXISTS (SELECT 1 FROM sys.iam_auth_member m WHERE m.member = identity);
END
$$;

REVOKE ALL ON sys.iam_identity, sys.iam_auth_member FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION sys._trusted_role(text, text, text) FROM PUBLIC;
RESET dsql.internal;
