// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import helium314.keyboard.latin.utils.GestureDataDao
import helium314.keyboard.latin.utils.Log
import java.io.File

class Database private constructor(context: Context, name: String = NAME) : SQLiteOpenHelper(context, name, null, VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(ClipboardDao.CREATE_TABLE)
        onUpgrade(db, 0, VERSION)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion <= 1) {
            db.execSQL(GestureDataDao.CREATE_TABLE)
        }
    }

    companion object {
        private val TAG = Database::class.java.simpleName
        private const val VERSION = 2
        const val NAME = "heliboard.db"
        private var instance: Database? = null
        @Synchronized
        fun getInstance(context: Context): Database {
            if (instance == null)
                instance = Database(context.applicationContext)
            return instance!!
        }

        // needs to be in sync with db version
        fun copyFromDb(file: File, context: Context) {
            if (!file.exists())
                return
            val otherDb = Database(context, file.name) // this upgrades the DB if necessary
            val clipDao = ClipboardDao.getInstance(context) // insert to dao because of cache
            if (clipDao == null) {
                Log.e(TAG, "can't transfer clipboard data because ClipboardDao is null")
            } else {
                otherDb.readableDatabase.rawQuery("SELECT TIMESTAMP, PINNED, TEXT FROM CLIPBOARD", null)
                    .use {
                        clipDao.clear()
                        while (it.moveToNext())
                            clipDao.addClip(it.getLong(0), it.getInt(1) != 0, it.getString(2))
                    }
            }
            val db = getInstance(context)
            otherDb.readableDatabase.rawQuery("SELECT TIMESTAMP, WORD, EXPORTED, SOURCE_ACTIVE, DATA FROM GESTURE_DATA", null)
                .use { c ->
                    db.writableDatabase.transaction {
                        // Wipe inside the transaction so a failed insert rolls back the delete too,
                        // instead of leaving the destination emptied with no replacement data.
                        execSQL("DELETE FROM GESTURE_DATA")
                        while (c.moveToNext()) {
                            val cv = ContentValues(5)
                            cv.put("TIMESTAMP", c.getLong(0))
                            cv.put("WORD", c.getString(1))
                            cv.put("EXPORTED", c.getInt(2))
                            cv.put("SOURCE_ACTIVE", c.getInt(3))
                            cv.put("DATA", c.getString(4))
                            // throw on failure so the transaction rolls back instead of silently dropping rows
                            insertOrThrow("GESTURE_DATA", null, cv)
                        }
                    }
                }
            otherDb.close()
            file.delete()
        }
    }
}
