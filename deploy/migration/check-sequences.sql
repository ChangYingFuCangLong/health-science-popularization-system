-- 资深 DBA 复检：序列对齐检查（所有 IDENTITY 表 seq.last_value >= MAX(id)）
DO $$
DECLARE r record; lastv bigint; maxid bigint; bad text[] := '{}';
BEGIN
  FOR r IN SELECT c.relname AS tbl
             FROM pg_class c JOIN pg_attribute a ON a.attrelid = c.oid AND a.attidentity <> ''
            WHERE c.relkind = 'r' AND c.relnamespace = 'public'::regnamespace
  LOOP
    EXECUTE format('SELECT last_value FROM public.%I', r.tbl || '_id_seq') INTO lastv;
    EXECUTE format('SELECT COALESCE(MAX(id), 0) FROM public.%I', r.tbl) INTO maxid;
    IF lastv < maxid THEN
      bad := bad || format('%s(seq=%s,max=%s)', r.tbl, lastv, maxid);
    END IF;
    RAISE NOTICE 'SEQ %: last=% max=%', r.tbl, lastv, maxid;
  END LOOP;
  IF array_length(bad, 1) IS NULL THEN
    RAISE NOTICE 'VERDICT: ALL_SEQUENCES_OK';
  ELSE
    RAISE NOTICE 'VERDICT: BEHIND %', bad;
  END IF;
END $$;
