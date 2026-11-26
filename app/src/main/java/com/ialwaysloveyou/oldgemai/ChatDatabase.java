package com.ialwaysloveyou.oldgemai;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChatDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "oldgemai_chats.db";
    // 2: добавлена колонка attachments_json (несколько картинок и файлы в сообщении)
    private static final int DB_VERSION = 2;

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
    // JSON-массив вложений: [{"k":тип,"n":имя,"m":mime,"f":имя файла с данными}]
    private static final String COL_MSG_ATTACHMENTS = "attachments_json";

    private final Context appContext;

    public ChatDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        Context app = context.getApplicationContext();
        this.appContext = app != null ? app : context;
    }

    // Каталог файлов вложений (приватное хранилище приложения)
    private File attDir() {
        return new File(appContext.getFilesDir(), "att");
    }

    // Удаляет файлы вложений тех сообщений, что подходят под условие (вызывать ДО удаления строк)
    private void deleteAttachmentFiles(String where, String[] args) {
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor c = db.rawQuery("SELECT " + COL_MSG_ATTACHMENTS + " FROM " + TABLE_MESSAGES
                    + " WHERE " + COL_MSG_ATTACHMENTS + " IS NOT NULL AND " + where, args);
            try {
                File dir = attDir();
                while (c.moveToNext()) {
                    Attachment.deleteFilesInJson(c.getString(0), dir);
                }
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            AppLog.w("DB", "Delete attachment files failed: " + t);
        }
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
                COL_MSG_ATTACHMENTS + " TEXT," +
                "FOREIGN KEY(" + COL_MSG_CHAT_ID + ") REFERENCES " + TABLE_CHATS + "(" + COL_CHAT_ID + ")" +
                ")";

        db.execSQL(createChatsTable);
        db.execSQL(createMessagesTable);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Версия 2: новая колонка для вложений. Чаты не удаляются
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE_MESSAGES + " ADD COLUMN " + COL_MSG_ATTACHMENTS + " TEXT");
        }
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
        values.put(COL_MSG_ATTACHMENTS, msg.attachments.isEmpty()
                ? (String) null : Attachment.toJson(msg.attachments, attDir()));
        values.put(COL_MSG_CREATED, now);

        db.insert(TABLE_MESSAGES, null, values);

        // Обновляем время последнего обновления чата
        ContentValues chatUpdate = new ContentValues();
        chatUpdate.put(COL_CHAT_UPDATED, now);
        db.update(TABLE_CHATS, chatUpdate, COL_CHAT_ID + "=?", new String[]{String.valueOf(chatId)});
    }

    /** Удаляет последнее (по id) сообщение чата. Нужно для перегенерации ответа. */
    public synchronized void deleteLastMessage(long chatId) {
        SQLiteDatabase db = getWritableDatabase();
        deleteAttachmentFiles(COL_MSG_ID + "=(SELECT MAX(" + COL_MSG_ID + ") FROM " + TABLE_MESSAGES
                + " WHERE " + COL_MSG_CHAT_ID + "=?)", new String[]{String.valueOf(chatId)});
        db.delete(TABLE_MESSAGES,
                COL_MSG_ID + "=(SELECT MAX(" + COL_MSG_ID + ") FROM " + TABLE_MESSAGES
                        + " WHERE " + COL_MSG_CHAT_ID + "=?)",
                new String[]{String.valueOf(chatId)});
    }

    /**
     * Заменяет текст сообщения с порядковым номером index (счёт с нуля, по возрастанию id) в чате.
     * Нужно для изменения ответа модели.
     */
    public synchronized void updateMessageText(long chatId, int index, String text) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE " + TABLE_MESSAGES + " SET " + COL_MSG_TEXT + "=? WHERE " + COL_MSG_ID
                + "=(SELECT " + COL_MSG_ID + " FROM " + TABLE_MESSAGES + " WHERE " + COL_MSG_CHAT_ID
                + "=? ORDER BY " + COL_MSG_ID + " ASC LIMIT 1 OFFSET " + index + ")",
                new Object[]{text != null ? text : "", Long.valueOf(chatId)});

        ContentValues chatUpdate = new ContentValues();
        chatUpdate.put(COL_CHAT_UPDATED, System.currentTimeMillis());
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

    /**
     * Тексты всех сообщений по чатам: id чата -> тексты сообщений по порядку через перевод строки.
     * Нужен для поиска по содержимому. Картинки (base64) не читаются, только текст.
     */
    public java.util.Map<Long, String> getAllChatTexts() {
        SQLiteDatabase db = getReadableDatabase();
        java.util.HashMap<Long, StringBuilder> tmp = new java.util.HashMap<Long, StringBuilder>();

        Cursor c = db.rawQuery("SELECT " + COL_MSG_CHAT_ID + ", " + COL_MSG_TEXT
                + " FROM " + TABLE_MESSAGES
                + " WHERE " + COL_MSG_TEXT + " IS NOT NULL AND " + COL_MSG_TEXT + " != ''"
                + " ORDER BY " + COL_MSG_ID + " ASC", null);
        try {
            while (c.moveToNext()) {
                Long id = Long.valueOf(c.getLong(0));
                String text = c.getString(1);
                if (text == null) continue;
                StringBuilder sb = tmp.get(id);
                if (sb == null) {
                    sb = new StringBuilder();
                    tmp.put(id, sb);
                } else {
                    sb.append('\n');
                }
                sb.append(text);
            }
        } finally {
            c.close();
        }

        java.util.HashMap<Long, String> result = new java.util.HashMap<Long, String>();
        for (java.util.Map.Entry<Long, StringBuilder> e : tmp.entrySet()) {
            result.put(e.getKey(), e.getValue().toString());
        }
        return result;
    }

    public List<GeminiApi.Message> getChatMessages(long chatId) {
        SQLiteDatabase db = getReadableDatabase();
        List<GeminiApi.Message> messages = new ArrayList<GeminiApi.Message>();

        Cursor cursor = db.query(TABLE_MESSAGES,
                new String[]{COL_MSG_ROLE, COL_MSG_TEXT, COL_MSG_IMAGE_BASE64,
                        COL_MSG_IMAGE_MIME, COL_MSG_IS_GENERATED, COL_MSG_ATTACHMENTS},
                COL_MSG_CHAT_ID + "=?", new String[]{String.valueOf(chatId)},
                null, null, COL_MSG_ID + " ASC");

        while (cursor.moveToNext()) {
            String role = cursor.getString(cursor.getColumnIndex(COL_MSG_ROLE));
            String text = cursor.getString(cursor.getColumnIndex(COL_MSG_TEXT));
            String imageB64 = cursor.getString(cursor.getColumnIndex(COL_MSG_IMAGE_BASE64));
            String imageMime = cursor.getString(cursor.getColumnIndex(COL_MSG_IMAGE_MIME));
            int isGen = cursor.getInt(cursor.getColumnIndex(COL_MSG_IS_GENERATED));
            String attJson = cursor.getString(cursor.getColumnIndex(COL_MSG_ATTACHMENTS));

            GeminiApi.Message msg;
            if (attJson != null && attJson.length() > 0) {
                msg = new GeminiApi.Message(role, text, Attachment.fromJson(attJson, attDir()));
            } else if (imageB64 != null && imageB64.length() > 0) {
                if (isGen == 1) {
                    msg = new GeminiApi.Message(role, text, imageB64, true);
                } else {
                    // Сообщения, сохранённые до появления нескольких вложений: одна картинка в колонке image_base64
                    List<Attachment> legacy = new ArrayList<Attachment>();
                    legacy.add(Attachment.fromLegacyImage(imageB64, imageMime));
                    msg = new GeminiApi.Message(role, text, legacy);
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
        deleteAttachmentFiles(COL_MSG_CHAT_ID + "=?", new String[]{String.valueOf(chatId)});
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
