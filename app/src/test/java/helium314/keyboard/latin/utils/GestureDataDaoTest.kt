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
        insertGestureWord("alpha", active = false)
        insertGestureWord("bravo", active = false)
        insertGestureWord("charlie", active = false)
        insertGestureWord("alpha", active = true) // active rows must survive passive deletion

        dao.deletePassiveWords(listOf("alpha", "bravo"))

        assertEquals(listOf("charlie"), dao.filterInfos(activeMode = false).map { it.targetWord })
        assertEquals(listOf("alpha"), dao.filterInfos(activeMode = true).map { it.targetWord })
    }

    @Test
    fun idSelectionsAreChunkedBelowSqliteBindVariableLimit() {
        val dao = freshDao()
        repeat(1100) { insertGestureWord("word$it", active = true) }
        val ids = dao.filterInfos(activeMode = true, limit = 2000).map { it.id }
        assertEquals(1100, ids.size)

        dao.markAsExported(ids, context)
        assertEquals(1100, dao.getJsonData(ids).count())
        assertEquals(1100, dao.delete(ids, onlyExported = false, context))
        assertEquals(0, dao.filterInfos(activeMode = true).size)
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
