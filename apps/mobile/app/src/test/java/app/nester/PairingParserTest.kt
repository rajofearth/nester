package app.nester

import app.nester.pair.PairParseResult
import app.nester.pair.PairingParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingParserTest {

    @Test
    fun parsesValidUri() {
        val result = PairingParser.parse("nester://pair?host=192.168.1.10&port=8080&token=abcdefgh12345678")
        assertTrue(result is PairParseResult.Ok)
        val config = (result as PairParseResult.Ok).config
        assertEquals("192.168.1.10", config.host)
        assertEquals(8080, config.port)
        assertEquals("abcdefgh12345678", config.token)
        assertEquals("http://192.168.1.10:8080", config.baseUrl)
    }

    @Test
    fun urlDecodesParams() {
        val result = PairingParser.parse("nester://pair?host=fe80%3A%3A1&port=99&token=%2Btoken%2Fwith%3Dspecials")
        assertTrue(result is PairParseResult.Ok)
        val config = (result as PairParseResult.Ok).config
        assertEquals("fe80::1", config.host)
        assertEquals(99, config.port)
        assertEquals("+token/with=specials", config.token)
    }

    @Test
    fun rejectsNonNesterScheme() {
        val result = PairingParser.parse("https://example.com/pair?host=h&port=1&token=abcdefghijklmno")
        assertTrue(result is PairParseResult.Invalid)
    }

    @Test
    fun rejectsMissingHost() {
        val result = PairingParser.parse("nester://pair?port=8080&token=abcdefgh12345678")
        assertTrue(result is PairParseResult.Invalid)
    }

    @Test
    fun rejectsEmptyHost() {
        val result = PairingParser.parse("nester://pair?host=&port=8080&token=abcdefgh12345678")
        assertTrue(result is PairParseResult.Invalid)
    }

    @Test
    fun rejectsNonNumericPort() {
        val result = PairingParser.parse("nester://pair?host=h&port=abc&token=abcdefgh12345678")
        assertTrue(result is PairParseResult.Invalid)
    }

    @Test
    fun rejectsPortOutOfRange() {
        assertTrue(PairingParser.parse("nester://pair?host=h&port=0&token=abcdefgh12345678") is PairParseResult.Invalid)
        assertTrue(PairingParser.parse("nester://pair?host=h&port=65536&token=abcdefgh12345678") is PairParseResult.Invalid)
    }

    @Test
    fun rejectsShortToken() {
        val result = PairingParser.parse("nester://pair?host=h&port=8080&token=short")
        assertTrue(result is PairParseResult.Invalid)
    }

    @Test
    fun acceptsMinimalTokenLength() {
        val result = PairingParser.parse("nester://pair?host=h&port=1&token=abcdefghijklmnop")
        assertTrue(result is PairParseResult.Ok)
    }
}
