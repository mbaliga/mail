package dev.aarso.mail.sync

import dev.aarso.mail.imap.ImapAuth
import dev.aarso.mail.imap.ImapClient
import dev.aarso.mail.jmap.JmapAuth
import dev.aarso.mail.jmap.JmapClient
import dev.aarso.mail.model.Account
import dev.aarso.mail.model.AuthKind
import dev.aarso.mail.model.SecretRef
import dev.aarso.mail.net.AuthenticationException
import dev.aarso.mail.net.MailException
import dev.aarso.mail.net.ProtocolLog
import dev.aarso.mail.net.TransportFactory
import dev.aarso.mail.pop3.Pop3Client
import dev.aarso.mail.smtp.SmtpAuth
import dev.aarso.mail.smtp.SmtpClient
import okhttp3.OkHttpClient

/** Where secrets live. On Android: AES-GCM under an Android Keystore key. In tests: a map. */
interface SecretStore {
    suspend fun get(ref: SecretRef): String?
    suspend fun put(ref: SecretRef, value: String)
    suspend fun delete(ref: SecretRef)
}

/**
 * Fresh OAuth access tokens for an account. Implemented by the app (Google's AuthorizationClient,
 * AppAuth for Microsoft) because obtaining consent needs an Activity; mail-core only ever asks for a
 * token, and asks for a forced refresh once when the server rejects one.
 */
interface TokenProvider {
    suspend fun accessToken(account: Account, forceRefresh: Boolean = false): String
}

fun interface Clock {
    fun now(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

/** Opens authenticated protocol sessions for an account, retrying once with a refreshed OAuth token. */
class AccountConnector(
    private val secrets: SecretStore,
    private val tokens: TokenProvider,
    private val transports: TransportFactory = TransportFactory.DEFAULT,
    private val http: OkHttpClient = OkHttpClient(),
    private val log: ProtocolLog = ProtocolLog.NONE,
) {

    private suspend fun password(account: Account, outgoing: Boolean = false): String {
        val ref = if (outgoing) account.outgoingSecret ?: account.secret else account.secret
        return secrets.get(ref) ?: throw AuthenticationException("No saved credentials for ${account.email}")
    }

    suspend fun imap(account: Account): ImapClient {
        val endpoint = account.incoming ?: throw MailException("${account.email} has no incoming server")
        var lastAuth: AuthenticationException? = null
        for (attempt in 0..1) {
            val client = ImapClient(endpoint, transports, log)
            try {
                client.connect()
                val auth = if (account.auth == AuthKind.OAUTH2) {
                    ImapAuth.OAuth(endpoint.username, tokens.accessToken(account, forceRefresh = attempt > 0))
                } else ImapAuth.Password(endpoint.username, password(account))
                client.login(auth)
                return client
            } catch (e: AuthenticationException) {
                client.close()
                lastAuth = e
                if (account.auth != AuthKind.OAUTH2) throw e
            } catch (e: Exception) {
                client.close()
                throw e
            }
        }
        throw lastAuth ?: AuthenticationException("Sign-in failed")
    }

    suspend fun smtp(account: Account): SmtpClient {
        val endpoint = account.outgoing ?: throw MailException("${account.email} has no sending server")
        var lastAuth: AuthenticationException? = null
        for (attempt in 0..1) {
            val client = SmtpClient(endpoint, transports, log)
            try {
                client.connect()
                val auth = if (account.auth == AuthKind.OAUTH2) {
                    SmtpAuth.OAuth(endpoint.username, tokens.accessToken(account, forceRefresh = attempt > 0))
                } else SmtpAuth.Password(endpoint.username, password(account, outgoing = true))
                client.authenticate(auth)
                return client
            } catch (e: AuthenticationException) {
                client.close()
                lastAuth = e
                if (account.auth != AuthKind.OAUTH2) throw e
            } catch (e: Exception) {
                client.close()
                throw e
            }
        }
        throw lastAuth ?: AuthenticationException("Sign-in failed")
    }

    suspend fun jmap(account: Account): JmapClient {
        val url = account.jmapSessionUrl ?: throw MailException("${account.email} has no JMAP session URL")
        val auth = when (account.auth) {
            AuthKind.API_TOKEN -> JmapAuth.Bearer(password(account))
            AuthKind.OAUTH2 -> JmapAuth.Bearer(tokens.accessToken(account))
            else -> JmapAuth.Basic(account.incoming?.username ?: account.email, password(account))
        }
        return JmapClient(url, auth, http)
    }

    suspend fun pop3(account: Account): Pop3Client {
        val endpoint = account.incoming ?: throw MailException("${account.email} has no incoming server")
        val client = Pop3Client(endpoint, transports, log)
        client.connect()
        try {
            if (account.auth == AuthKind.OAUTH2) client.loginOAuth(endpoint.username, tokens.accessToken(account))
            else client.login(endpoint.username, password(account))
        } catch (e: Exception) {
            client.close(); throw e
        }
        return client
    }

    /** Verify an account before saving it: sign in to incoming and outgoing, then hang up. */
    suspend fun verify(account: Account) {
        when (account.protocol) {
            dev.aarso.mail.model.IncomingProtocol.IMAP -> imap(account).logout()
            dev.aarso.mail.model.IncomingProtocol.JMAP -> jmap(account).session(refresh = true)
            dev.aarso.mail.model.IncomingProtocol.POP3 -> pop3(account).quit()
        }
        if (account.protocol != dev.aarso.mail.model.IncomingProtocol.JMAP || account.outgoing != null) {
            if (account.outgoing != null) smtp(account).quit()
        }
    }
}
