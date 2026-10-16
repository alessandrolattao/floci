package io.github.hectorvent.floci.services.dsql.dataplane;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DsqlSessionTest {

    @Test
    void aSerializationFailureIsDsqlsOc000() {
        byte[] postgres = error(Map.of('C', "40001", 'M', "could not serialize access due to concurrent update",
                'F', "heapam.c", 'L', "1", 'R', "heap_update"));

        Map<Character, String> fields = PgWire.parseFields(DsqlSession.inDsqlWords((byte) 'E', postgres));

        assertEquals("40001", fields.get('C'));
        assertEquals("change conflicts with another transaction (OC000)", fields.get('M'));
        assertFalse(fields.containsKey('F'));
    }

    @Test
    void anErrorRaisedInsideSysCarriesNeitherContextNorLocation() {
        byte[] postgres = error(Map.of('C', "0A000", 'M', "unsupported statement: Truncate",
                'W', "PL/pgSQL function sys._reject(text,text,text) line 4 at RAISE",
                'F', "pl_exec.c", 'L', "3926", 'R', "exec_stmt_raise"));

        Map<Character, String> fields = PgWire.parseFields(DsqlSession.inDsqlWords((byte) 'E', postgres));

        assertEquals("unsupported statement: Truncate", fields.get('M'));
        assertFalse(fields.containsKey('W'));
        assertFalse(fields.containsKey('F'));
        assertFalse(fields.containsKey('L'));
        assertFalse(fields.containsKey('R'));
    }

    @Test
    void aNoticeRaisedInsideSysKeepsItsLocationAsWaitForJobsDoesOnDsql() {
        byte[] postgres = error(Map.of('S', "NOTICE", 'C', "00000", 'M', "Job execution succeeded for job with id x",
                'W', "PL/pgSQL function sys.wait_for_job(text,boolean) line 7 at RAISE",
                'F', "pl_exec.c", 'L', "3926", 'R', "exec_stmt_raise"));

        Map<Character, String> fields = PgWire.parseFields(DsqlSession.inDsqlWords((byte) 'N', postgres));

        assertFalse(fields.containsKey('W'));
        assertTrue(fields.containsKey('F'));
        assertEquals("exec_stmt_raise", fields.get('R'));
    }

    @Test
    void anErrorFromTheClientsOwnSqlIsLeftAlone() {
        byte[] postgres = error(Map.of('C', "23505", 'M', "duplicate key value violates unique constraint \"t_u\"",
                'W', "SQL function \"f\" statement 1"));

        assertArrayEquals(postgres, DsqlSession.inDsqlWords((byte) 'E', postgres));
    }

    private static byte[] error(Map<Character, String> fields) {
        Map<Character, String> ordered = new LinkedHashMap<>();
        ordered.put('S', fields.getOrDefault('S', "ERROR"));
        ordered.put('V', fields.getOrDefault('S', "ERROR"));
        fields.forEach((code, value) -> {
            if (code != 'S') {
                ordered.put(code, value);
            }
        });
        return PgWire.fields(ordered);
    }
}
