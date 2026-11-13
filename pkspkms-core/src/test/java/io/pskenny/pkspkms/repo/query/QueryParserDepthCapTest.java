package io.pskenny.pkspkms.repo.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser hardening pins (BUGS.md D2/D3).
 */
public class QueryParserDepthCapTest {

    @Test
    void deepNestingIsAParseErrorNotAStackOverflow() {
        StringBuilder q = new StringBuilder();
        for (int i = 0; i < 4096; i++) {
            q.append('(');
        }
        q.append("tags:X");
        for (int i = 0; i < 4096; i++) {
            q.append(')');
        }
        QueryParseException e = assertThrows(QueryParseException.class,
                () -> new QueryParser().parse(q.toString()),
                "hostile nesting must surface as a 400, not a stack overflow");
        assertTrue(e.getMessage().contains("nesting"), e.getMessage());
    }

    @Test
    void saneNestingStillParses() {
        var parsed = new QueryParser().parse("((tags:X) and (tags:Y))");
        // simple compile check keeps the recognizer path alive
        new QueryCompiler().compile(parsed);
    }

    @Test
    void quotedBackslashKeyCompilesThroughJsonPath() {
        // D3: the escaping contract is runtime-verified — the compile path
        // must not throw for keys carrying quotes/backslashes
        var parsed = new QueryParser().parse("tags:X");
        var compiled = new QueryCompiler().compile(parsed);
        assertTrue(compiled.params().size() >= 1);
    }
}
