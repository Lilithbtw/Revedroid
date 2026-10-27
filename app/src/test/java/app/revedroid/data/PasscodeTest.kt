package app.revedroid.data

import app.revedroid.data.store.Passcode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasscodeTest {
    @Test
    fun rightPasscodeVerifies() {
        assertTrue(Passcode.verify("2468", Passcode.hash("2468")))
    }

    @Test
    fun wrongPasscodeFails() {
        val stored = Passcode.hash("2468")
        assertFalse(Passcode.verify("2469", stored))
        assertFalse(Passcode.verify("24680", stored))
    }

    @Test
    fun hashesAreSalted() {
        assertNotEquals(Passcode.hash("1234"), Passcode.hash("1234"))
    }

    @Test
    fun damagedHashNeverVerifies() {
        assertFalse(Passcode.verify("1234", ""))
        assertFalse(Passcode.verify("1234", "not base64:at all"))
    }

    @Test
    fun onlyFourToEightDigitsAreValid() {
        assertTrue(Passcode.isValid("1234"))
        assertTrue(Passcode.isValid("12345678"))
        assertFalse(Passcode.isValid("123"))
        assertFalse(Passcode.isValid("123456789"))
        assertFalse(Passcode.isValid("12a4"))
    }
}
