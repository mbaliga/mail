package dev.aarso.mail.app

import dev.aarso.mail.app.ai.CatalogModel
import dev.aarso.mail.app.ai.DownloadState
import dev.aarso.mail.app.ai.ModelDownloads
import dev.aarso.mail.model.SecretRef
import dev.aarso.mail.sync.SecretStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelDownloadsTest {
    private val store = object : SecretStore {
        val m = HashMap<String, String>()
        override suspend fun get(ref: SecretRef) = m[ref.key]
        override suspend fun put(ref: SecretRef, value: String) { m[ref.key] = value }
        override suspend fun delete(ref: SecretRef) { m.remove(ref.key) }
    }
    private val bytes = ByteArray(1_000_000) { (it % 251).toByte() }

    private fun model(server: MockWebServer) = CatalogModel("t", "T", "", server.url("/m.task").toString(), "m.task", bytes.size.toLong(), needsToken = true)

    @Test fun downloadsAndResumesFromAPartial() = runBlocking {
        val dir = File.createTempFile("models", "").apply { delete(); mkdirs() }
        MockWebServer().use { server ->
            val m = model(server)
            File(dir, "m.task.part").writeBytes(bytes.copyOfRange(0, 400_000))
            server.enqueue(MockResponse().setResponseCode(206).setBody(Buffer().write(bytes, 400_000, 600_000)))
            val d = ModelDownloads(dir, OkHttpClient(), store)
            store.put(SecretRef("models.hf.token"), "hf_abc")
            val f = d.download(m)!!
            val req = server.takeRequest()
            assertEquals("bytes=400000-", req.getHeader("Range"))
            assertEquals("Bearer hf_abc", req.getHeader("Authorization"))
            assertTrue(f.readBytes().contentEquals(bytes))
            assertTrue(d.installed(m))
            d.delete(m)
            assertTrue(!d.installed(m))
        }
    }

    @Test fun gatedModelWithoutAccessExplainsWhy() = runBlocking {
        val dir = File.createTempFile("models", "").apply { delete(); mkdirs() }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            val d = ModelDownloads(dir, OkHttpClient(), store)
            assertNull(d.download(model(server)))
            val s = d.state.value["t"] as DownloadState.Failed
            assertTrue(s.message.contains("Hugging Face"))
        }
    }
}
