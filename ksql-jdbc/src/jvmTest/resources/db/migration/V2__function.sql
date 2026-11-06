-- Dollar quoting and a '?' in a comment must survive.
CREATE FUNCTION inc(i int) RETURNS int AS $$ BEGIN RETURN i + 1; END; $$ LANGUAGE plpgsql;
