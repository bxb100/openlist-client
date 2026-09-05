package org.openlist.mobile.data.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openlist.mobile.data.account.AccountId

@RunWith(AndroidJUnit4::class)
class LoginCredentialCipherTest {
    @Test
    fun savedPasswordDecryptsInANewCipherButOnlyForItsAccountAndPurpose() {
        val account = AccountId("credential-cipher-test")
        val password = "a test password with spaces 密码"
        val encrypted = LoginCredentialCipher().encryptPassword(password, account)
        val reopened = LoginCredentialCipher()

        assertFalse(encrypted.contains(password))
        assertEquals(password, reopened.decryptPassword(encrypted, account))
        assertTrue(runCatching { reopened.decryptPassword(encrypted, AccountId("another-account")) }.isFailure)
        assertTrue(runCatching { reopened.decrypt(encrypted, account) }.isFailure)
    }

    @Test
    fun existingHashFormatRemainsReadableAndCannotBeMistakenForAPassword() {
        val account = AccountId("credential-hash-test")
        val hash = PasswordHasher.forOpenList("test login")
        val encrypted = LoginCredentialCipher().encrypt(hash, account)

        assertEquals(hash, LoginCredentialCipher().decrypt(encrypted, account))
        assertTrue(runCatching { LoginCredentialCipher().decryptPassword(encrypted, account) }.isFailure)
    }
}
