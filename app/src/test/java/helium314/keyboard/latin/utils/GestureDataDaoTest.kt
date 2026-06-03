// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
import helium314.keyboard.latin.database.Database
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GestureDataDaoTest {
    private val context = ApplicationProvider.getApplicationContext<App>()

    @Test
    fun multiWordDeletionUsesIndividualSelectionArgs() {
        val dao = freshDao()
        insertGestureWord("alpha", active = true)
        insertGestureWord("bravo", active = true)
        insertGestureWord("charlie", active = true)

        dao.deletePassiveWords(listOf("alpha", "bravo"))

        assertEquals(listOf("charlie"), dao.filterInfos(activeMode = true).map { it.targetWord })
    }

    @Test
    fun emptyJsonExportReturnsNoRows() {
        assertEquals(emptyList<String>(), freshDao().getJsonData(emptyList()).toList())
    }

    @Test
    fun idBasedExportAndDeleteUsePlaceholders() {
        val dao = freshDao()
        insertGestureWord("alpha", active = true)
        insertGestureWord("bravo", active = true)
        val ids = dao.filterInfos(activeMode = true).map { it.id }

        dao.markAsExported(ids, context)
        assertEquals(2, dao.filterInfos(activeMode = true).count { it.exported })

        assertEquals(2, dao.delete(ids, onlyExported = true, context))
        assertEquals(emptyList<String>(), dao.filterInfos(activeMode = true).map { it.targetWord })
    }

    private fun freshDao(): GestureDataDao {
        val db = Database.getInstance(context).writableDatabase
        db.delete("GESTURE_DATA", null, null)
        return requireNotNull(GestureDataDao.getInstance(context))
    }

    private fun insertGestureWord(word: String, active: Boolean) {
        val cv = ContentValues(5).apply {
            put("TIMESTAMP", System.currentTimeMillis())
            put("WORD", word)
            put("EXPORTED", 0)
            put("SOURCE_ACTIVE", if (active) 1 else 0)
            put("DATA", """{"word":"$word"}""")
        }
        Database.getInstance(context).writableDatabase.insert("GESTURE_DATA", null, cv)
    }
}
