package herdr.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LoginTest {
    @Test
    fun every_saved_auth_is_key_login() {
        assertEquals("key", Login.auth("password"))
        assertEquals("key", Login.auth("key"))
        assertEquals("key", Login.auth(null))
        assertEquals("key", Login.auth(""))
    }
}
