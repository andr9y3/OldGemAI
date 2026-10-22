package com.geminiapp.chat;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChatDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "oldgemai_chats.db";
    private static final int DB_VERSION = 1;

    private static final String TABLE_CHATS = "chats";
    private static final String TABLE_MESSAGES = "messages";

    // Таблица чатов
    private static final String COL_CHAT_ID = "id";
    private static final String COL_CHAT_TITLE = "title";
    private static final String COL_CHAT_CREATED = "created_at";
    private static final String COL_CHAT_UPDATED = "updated_at";

    // Таблица сообщений
    private static final String COL_MSG_ID = "id";
    private static final String COL_MSG_CHAT_ID = "chat_id";
    private static final String COL_MSG_ROLE = "role";
    private static final String COL_MSG_TEXT = "text";
    private static final String COL_MSG_IMAGE_BASE64 = "image_base64";
    private static final String COL_MSG_IMAGE_MIME = "image_mime_type";
    private static final String COL_MSG_IS_GENERATED = "is_generated_image";
    private static final String COL_MSG_CREATED = "created_at";

    public ChatDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createChatsTable = "CREATE TABLE " + TABLE_CHATS + " (" +
                COL_CHAT_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                COL_CHAT_TITLE + " TEXT," +
                COL_CHAT_CREATED + " INTEGER," +
                COL_CHAT_UPDATED + " INTEGER" +
                ")";

        String createMessagesTable = "CREATE TABLE " + TABLE_MESSAGES + " (" +
                COL_MSG_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                COL_MSG_CHAT_ID + " INTEGER," +
                COL_MSG_ROLE + " TEXT," +
                COL_MSG_TEXT + " TEXT," +
                COL_MSG_IMAGE_BASE64 + " TEXT," +
                COL_MSG_IMAGE_MIME + " TEXT," +
                COL_MSG_IS_GENERATED + " INTEGER," +
                COL_MSG_CREATED + " INTEGER," +
                "FOREIGN KEY(" + COL_MSG_CHAT_ID + ") REFERENCES " + TABLE_CHATS + "(" + COL_CHAT_ID + ")" +
                ")";

        db.execSQL(createChatsTable);
        db.execSQL(createMessagesTable);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_MESSAGES);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_CHATS);
        onCreate(db);
    }

    public long createChat(String title) {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();

        ContentValues values = new ContentValues();
        values.put(COL_CHAT_TITLE, title);
        values.put(COL_CHAT_CREATED, now);
        values.put(COL_CHAT_UPDATED, now);

        return db.insert(TABLE_CHATS, null, values);
    }

    public void saveMessage(long chatId, GeminiApi.Message msg) {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();

        ContentValues values = new ContentValues();
        values.put(COL_MSG_CHAT_ID, chatId);
        values.put(COL_MSG_ROLE, msg.role);
        values.put(COL_MSG_TEXT, msg.text != null ? msg.text : "");
        values.put(COL_MSG_IMAGE_BASE64, msg.imageBase64);
        values.put(COL_MSG_IMAGE_MIME, msg.imageMimeType);
        values.put(COL_MSG_IS_GENERATED, msg.isGeneratedImage ? 1 : 0);
        values.put(COL_MSG_CREATED, now);

        db.insert(TABLE_MESSAGES, null, values);

        // Обновляем время последнего обновления чата
        ContentValues chatUpdate = new ContentValues();
        chatUpdate.put(COL_CHAT_UPDATED, now);
        db.update(TABLE_CHATS, chatUpdate, COL_CHAT_ID + "=?", new String[]{String.valueOf(chatId)});
    }

    public int getMessageCount(long chatId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_MESSAGES +
                " WHERE " + COL_MSG_CHAT_ID + "=?", new String[]{String.valueOf(chatId)});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    public boolean chatExists(long chatId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_CHATS +
                " WHERE " + COL_CHAT_ID + "=?", new String[]{String.valueOf(chatId)});
        try {
            return c.moveToFirst() && c.getInt(0) > 0;
        } finally {
            c.close();
        }
    }

    /**
     * Дописывает в БД только те сообщения из списка (индексы [0, end)),
     * которых там ещё нет. Количество уже сохранённых берётся из самой БД,
     * поэтому повторный вызов никогда не создаёт дубликатов.
     */
    public synchronized void syncMessages(long chatId, List<GeminiApi.Message> msgs, int end) {
        int saved = getMessageCount(chatId);
        if (end > msgs.size()) end = msgs.size();
        if (saved >= end) return;

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = saved; i < end; i++) {
                saveMessage(chatId, msgs.get(i));
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<ChatInfo> getAllChats() {
        SQLiteDatabase db = getReadableDatabase();
        List<ChatInfo> chats = new ArrayList<ChatInfo>();

        Cursor cursor = db.query(TABLE_CHATS,
                new String[]{COL_CHAT_ID, COL_CHAT_TITLE, COL_CHAT_CREATED, COL_CHAT_UPDATED},
                null, null, null, null,
                COL_CHAT_UPDATED + " DESC");

        while (cursor.moveToNext()) {
            long id = cursor.getLong(cursor.getColumnIndex(COL_CHAT_ID));
            String title = cursor.getString(cursor.getColumnIndex(COL_CHAT_TITLE));
            long created = cursor.getLong(cursor.getColumnIndex(COL_CHAT_CREATED));
            long updated = cursor.getLong(cursor.getColumnIndex(COL_CHAT_UPDATED));

            chats.add(new ChatInfo(id, title, created, updated));
        }
        cursor.close();

        return chats;
    }

    public List<GeminiApi.Message> getChatMessages(long chatId) {
        SQLiteDatabase db = getReadableDatabase();
        List<GeminiApi.Message> messages = new ArrayList<GeminiApi.Message>();

        Cursor cursor = db.query(TABLE_MESSAGES,
                new String[]{COL_MSG_ROLE, COL_MSG_TEXT, COL_MSG_IMAGE_BASE64,
                        COL_MSG_IMAGE_MIME, COL_MSG_IS_GENERATED},
                COL_MSG_CHAT_ID + "=?", new String[]{String.valueOf(chatId)},
                null, null, COL_MSG_ID + " ASC");

        while (cursor.moveToNext()) {
            String role = cursor.getString(cursor.getColumnIndex(COL_MSG_ROLE));
            String text = cursor.getString(cursor.getColumnIndex(COL_MSG_TEXT));
            String imageB64 = cursor.getString(cursor.getColumnIndex(COL_MSG_IMAGE_BASE64));
            String imageMime = cursor.getString(cursor.getColumnIndex(COL_MSG_IMAGE_MIME));
            int isGen = cursor.getInt(cursor.getColumnIndex(COL_MSG_IS_GENERATED));

            GeminiApi.Message msg;
            if (imageB64 != null && imageB64.length() > 0) {
                if (isGen == 1) {
                    msg = new GeminiApi.Message(role, text, imageB64, true);
                } else {
                    msg = new GeminiApi.Message(role, text, imageB64, imageMime);
                }
            } else {
                msg = new GeminiApi.Message(role, text);
            }

            messages.add(msg);
        }
        cursor.close();

        return messages;
    }

    public void deleteChat(long chatId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_MESSAGES, COL_MSG_CHAT_ID + "=?", new String[]{String.valueOf(chatId)});
        db.delete(TABLE_CHATS, COL_CHAT_ID + "=?", new String[]{String.valueOf(chatId)});
    }

    public void renameChat(long chatId, String newTitle) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_CHAT_TITLE, newTitle);
        db.update(TABLE_CHATS, values, COL_CHAT_ID + "=?", new String[]{String.valueOf(chatId)});
    }

    public static class ChatInfo {
        public long id;
        public String title;
        public long createdAt;
        public long updatedAt;

        public ChatInfo(long id, String title, long createdAt, long updatedAt) {
            this.id = id;
            this.title = title;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        public String getFormattedDate() {
            SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault());
            return sdf.format(new Date(updatedAt));
        }
    }
}
