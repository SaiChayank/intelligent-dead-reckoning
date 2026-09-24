package com.intelligentdeadreckoning.app

import com.google.gson.JsonParser
import com.intelligentdeadreckoning.app.map.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class OfflineMapTest {
    @Test fun hyderabadBoundsAreFiniteAndExplicit() {
        assertTrue(HyderabadMap.contains(17.425,78.475))
        assertTrue(HyderabadMap.contains(17.30,78.35))
        assertFalse(HyderabadMap.contains(0.0,0.0))
        assertFalse(HyderabadMap.contains(Double.NaN,78.4))
        assertFalse(HyderabadMap.contains(17.4,Double.POSITIVE_INFINITY))
        assertFalse(HyderabadMap.contains(17.8,78.4))
    }
    @Test fun styleOnlyReferencesLocalFilesAndContainsNoPositionSource() {
        val raw = OfflineMapStyle.json("/data/user/0/test/map.mbtiles","file:///data/user/0/test/fonts/{fontstack}/{range}.pbf")
        val style = JsonParser.parseString(raw).asJsonObject
        assertEquals(8,style["version"].asInt)
        assertFalse(raw.contains("http://")); assertFalse(raw.contains("https://"))
        assertFalse(style.has("sprite"))
        val sources = style["sources"].asJsonObject
        assertEquals(setOf("hyderabad"),sources.keySet())
        assertTrue(sources["hyderabad"].asJsonObject["url"].asString.startsWith("mbtiles:///"))
        assertTrue(style["glyphs"].asString.startsWith("file:///"))
        assertTrue(style["layers"].asJsonArray.any { it.asJsonObject["id"].asString == "places" })
    }
    @Test fun remoteAndTraversalResourcesRejected() {
        listOf("https://example.com/map.mbtiles","relative.mbtiles","/data/../map.mbtiles").forEach {
            try { OfflineMapStyle.json(it,"file:///fonts/{fontstack}/{range}.pbf"); fail() }
            catch (_: IllegalArgumentException) { }
        }
        try { OfflineMapStyle.json("/data/map.mbtiles","https://example.com/fonts"); fail() }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun bundledResourcesMatchRecordedHashesAndBudget() {
        val root = File("src/main/assets/offline/hyderabad")
        val manifest = JsonParser.parseString(File(root,"manifest.json").readText()).asJsonObject
        assertEquals("hyderabad-v1",manifest["id"].asString)
        assertEquals(247,manifest["tile_count"].asInt)
        var total = 0L
        manifest["files"].asJsonArray.forEach {
            val item = it.asJsonObject
            val file = File(root,item["path"].asString)
            assertTrue(file.isFile)
            assertEquals(item["bytes"].asLong,file.length())
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while(true) { val n = input.read(buffer); if(n < 0) break; digest.update(buffer,0,n) }
            }
            assertEquals(item["sha256"].asString,digest.digest().joinToString("") { b -> "%02x".format(b) })
            total += file.length()
        }
        assertTrue(total < 100L*1024*1024)
        assertTrue(File(root,"OFL.txt").isFile)
    }
}
