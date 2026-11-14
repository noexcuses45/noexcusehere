package com.noexcusehere.vpn

import com.noexcusehere.vpn.data.Profile
import com.noexcusehere.vpn.data.ProfileStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file: File by lazy { File(folder.root, "profiles.json") }

    private val home = Profile("a", "Home", "[Interface]…", "home.example.com:51820")
    private val work = Profile("b", "Work", "[Interface]…", "work.example.com:51820")

    @Test
    fun firstSavedProfileBecomesSelected() {
        val store = ProfileStore(file)
        store.save(home)
        store.save(work)

        assertEquals(listOf(home, work), store.state.value.profiles)
        assertEquals(home, store.state.value.selected)
    }

    @Test
    fun everythingSurvivesARestart() {
        ProfileStore(file).apply {
            save(home)
            save(work)
            select("b")
        }

        val reopened = ProfileStore(file).state.value

        assertEquals(listOf(home, work), reopened.profiles)
        assertEquals("b", reopened.selectedId)
    }

    @Test
    fun savingAnExistingIdReplacesIt() {
        val store = ProfileStore(file)
        store.save(home)
        store.save(home.copy(name = "Home (new)"))

        assertEquals(listOf("Home (new)"), store.state.value.profiles.map { it.name })
    }

    @Test
    fun deletingTheSelectedProfileSelectsAnother() {
        val store = ProfileStore(file)
        store.save(home)
        store.save(work)

        store.delete("a")
        assertEquals("b", store.state.value.selectedId)

        store.delete("b")
        assertNull(store.state.value.selectedId)
        assertTrue(store.state.value.profiles.isEmpty())
    }

    @Test
    fun selectingAnUnknownIdChangesNothing() {
        val store = ProfileStore(file)
        store.save(home)

        store.select("missing")

        assertEquals("a", store.state.value.selectedId)
    }

    @Test
    fun anUnreadableFileIsSetAsideNotOverwritten() {
        file.writeText("{ this is not json")

        val store = ProfileStore(file)

        assertTrue(store.state.value.profiles.isEmpty())
        assertEquals("{ this is not json", File(folder.root, "profiles.json.unreadable").readText())
    }
}
