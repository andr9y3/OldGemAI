package com.geminiapp.chat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ChatHistoryActivity extends Activity {

    private ChatDatabase chatDb;
    private Prefs prefs;
    private ListView lvChats;
    private TextView tvEmpty;
    private EditText etSearch;
    private ImageButton btnSearch;
    // Все сохранённые чаты
    private List<ChatDatabase.ChatInfo> allChats = new ArrayList<ChatDatabase.ChatInfo>();
    // Показанные сейчас в списке (все или результат поиска): позиции в списке считаются по ним
    private List<ChatDatabase.ChatInfo> chatList;
    // Тексты сообщений по чатам для поиска по содержимому; собираются при первом поиске
    private Map<Long, String> chatTexts;
    private ArrayAdapter<String> adapter;
    private boolean isRu;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        chatDb = new ChatDatabase(this);
        prefs = new Prefs(this);
        chatList = new ArrayList<ChatDatabase.ChatInfo>();

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);

        isRu = Prefs.LANG_RU.equals(prefs.getLanguage());
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button btnSettings = new Button(this);
        btnSettings.setText(isRu ? "Настройки" : "Settings");
        Button btnNew = new Button(this);
        btnNew.setText(isRu ? "Новый чат" : "New chat");
        Button btnBack = new Button(this);
        btnBack.setText(isRu ? "К чату" : "Back");
        buttons.addView(btnSettings, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        buttons.addView(btnNew, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        buttons.addView(btnBack, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        mainLayout.addView(buttons);

        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ChatHistoryActivity.this, SettingsActivity.class));
            }
        });
        btnNew.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent result = new Intent();
                result.putExtra("new_chat", true);
                setResult(RESULT_OK, result);
                finish();
            }
        });
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        // Строка поиска: поле ввода и кнопка-лупа. Список фильтруется по мере ввода.
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(dp(6), 0, dp(6), 0);

        etSearch = new EditText(this);
        etSearch.setHint(isRu ? "Поиск по чатам..." : "Search chats...");
        etSearch.setSingleLine(true);
        try { etSearch.setImeOptions(EditorInfo.IME_ACTION_SEARCH); } catch (Throwable ignored) {} // API 3+
        searchRow.addView(etSearch, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        btnSearch = new ImageButton(this);
        btnSearch.setImageDrawable(ChatAdapter.SvgIcon.fromRaw(this, R.raw.ic_search, dp(24), 0xFF000000));
        try {
            btnSearch.setContentDescription(isRu ? "Поиск" : "Search");
        } catch (Throwable ignored) {}
        searchRow.addView(btnSearch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        mainLayout.addView(searchRow);

        tvEmpty = new TextView(this);
        tvEmpty.setText(isRu ? "Нет сохранённых чатов" : "No saved chats");
        tvEmpty.setPadding(20, 20, 20, 20);
        tvEmpty.setTextSize(16);
        mainLayout.addView(tvEmpty);

        lvChats = new ListView(this);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.FILL_PARENT, 0, 1.0f);
        mainLayout.addView(lvChats, listParams);

        setContentView(mainLayout);
        setTitle(isRu ? "История чатов" : "Chat history");
        // Клавиатура не должна всплывать сама при открытии экрана
        try { getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN); } catch (Throwable ignored) {} // API 3+

        loadChats();

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                applyFilter();
            }
        });
        try {
        etSearch.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                hideKeyboard();
                return true;
            }
        });
        } catch (Throwable ignored) {} // API 3+
        // Лупа: при пустом поле ставит курсор в поиск и показывает клавиатуру, иначе убирает клавиатуру
        btnSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (etSearch.getText().toString().trim().length() == 0) {
                    etSearch.requestFocus();
                    showKeyboard();
                } else {
                    hideKeyboard();
                }
            }
        });

        lvChats.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                openChat(position);
            }
        });

        lvChats.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, final int position, long id) {
                showChatMenu(position);
                return true;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadChats();
    }

    private void loadChats() {
        allChats = chatDb.getAllChats();
        // Чаты могли измениться: индекс содержимого пересоберётся при следующем поиске
        chatTexts = null;
        applyFilter();
    }

    /**
     * Заполняет список по тексту в поле поиска. Пустое поле - все чаты. Иначе сначала идут чаты,
     * в названии которых есть запрос, потом чаты, где запрос есть только в сообщениях
     * (у них под названием показывается фрагмент текста). Регистр не важен.
     */
    private void applyFilter() {
        String q = etSearch != null ? etSearch.getText().toString().trim() : "";
        chatList = new ArrayList<ChatDatabase.ChatInfo>();
        List<String> titles = new ArrayList<String>();

        if (q.length() == 0) {
            for (ChatDatabase.ChatInfo chat : allChats) {
                chatList.add(chat);
                titles.add(titlePreview(chat) + "\n" + chat.getFormattedDate());
            }
        } else {
            // 1. По названию
            for (ChatDatabase.ChatInfo chat : allChats) {
                if (indexOfIgnoreCase(chat.title, q) >= 0) {
                    chatList.add(chat);
                    titles.add(titlePreview(chat) + "\n" + chat.getFormattedDate());
                }
            }
            // 2. По содержимому (только чаты, не найденные по названию)
            if (chatTexts == null) chatTexts = chatDb.getAllChatTexts();
            for (ChatDatabase.ChatInfo chat : allChats) {
                if (indexOfIgnoreCase(chat.title, q) >= 0) continue;
                String text = chatTexts.get(Long.valueOf(chat.id));
                int idx = indexOfIgnoreCase(text, q);
                if (idx >= 0) {
                    chatList.add(chat);
                    titles.add(titlePreview(chat) + "\n" + makeSnippet(text, idx, q.length())
                            + "\n" + chat.getFormattedDate());
                }
            }
        }

        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, titles);
        lvChats.setAdapter(adapter);

        if (allChats.isEmpty()) {
            tvEmpty.setText(isRu ? "Нет сохранённых чатов" : "No saved chats");
        } else {
            tvEmpty.setText(isRu ? "Ничего не найдено" : "Nothing found");
        }
        tvEmpty.setVisibility(chatList.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private String titlePreview(ChatDatabase.ChatInfo chat) {
        String title = chat.title != null ? chat.title : "";
        return title.length() > 40 ? title.substring(0, 40) + "..." : title;
    }

    // Поиск подстроки без учёта регистра (в том числе для кириллицы: LIKE в SQLite с ней не справляется)
    private static int indexOfIgnoreCase(String hay, String needle) {
        if (hay == null || needle == null) return -1;
        int n = needle.length();
        int max = hay.length() - n;
        for (int i = 0; i <= max; i++) {
            if (hay.regionMatches(true, i, needle, 0, n)) return i;
        }
        return -1;
    }

    // Фрагмент текста вокруг найденного места, одной строкой
    private static String makeSnippet(String text, int idx, int len) {
        int start = Math.max(0, idx - 25);
        int end = Math.min(text.length(), idx + len + 45);
        String s = text.substring(start, end).replace('\n', ' ').replace('\r', ' ').trim();
        return (start > 0 ? "..." : "") + s + (end < text.length() ? "..." : "");
    }

    private void showKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(etSearch, 0);
        } catch (Throwable ignored) {}
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
        } catch (Throwable ignored) {}
    }

    private void openChat(int position) {
        if (position < 0 || position >= chatList.size()) return;

        ChatDatabase.ChatInfo chatInfo = chatList.get(position);
        List<GeminiApi.Message> messages = chatDb.getChatMessages(chatInfo.id);

        Intent intent = new Intent();
        intent.putExtra("chat_id", chatInfo.id);
        intent.putExtra("chat_messages_count", messages.size());

        setResult(RESULT_OK, intent);
        finish();
    }

    private void showChatMenu(final int position) {
        if (position < 0 || position >= chatList.size()) return;

        final ChatDatabase.ChatInfo chatInfo = chatList.get(position);

        CharSequence[] items = isRu
            ? new CharSequence[]{"Открыть", "Переименовать", "Удалить"}
            : new CharSequence[]{"Open", "Rename", "Delete"};

        new AlertDialog.Builder(this)
                .setTitle(chatInfo.title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            openChat(position);
                        } else if (which == 1) {
                            renameChat(position);
                        } else if (which == 2) {
                            deleteChat(position);
                        }
                    }
                })
                .show();
    }

    private void renameChat(final int position) {
        if (position < 0 || position >= chatList.size()) return;

        final ChatDatabase.ChatInfo chatInfo = chatList.get(position);

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(chatInfo.title);

        new AlertDialog.Builder(this)
                .setTitle(isRu ? "Переименовать чат" : "Rename chat")
                .setView(input)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String newTitle = input.getText().toString().trim();
                        if (newTitle.length() > 0) {
                            chatDb.renameChat(chatInfo.id, newTitle);
                            loadChats();
                        }
                    }
                })
                .setNegativeButton(isRu ? "Отмена" : "Cancel", null)
                .show();
    }

    private void deleteChat(final int position) {
        if (position < 0 || position >= chatList.size()) return;

        final ChatDatabase.ChatInfo chatInfo = chatList.get(position);

        new AlertDialog.Builder(this)
                .setTitle(isRu ? "Удалить чат?" : "Delete chat?")
                .setMessage(chatInfo.title)
                .setPositiveButton(isRu ? "Удалить" : "Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        chatDb.deleteChat(chatInfo.id);
                        loadChats();
                    }
                })
                .setNegativeButton(isRu ? "Отмена" : "Cancel", null)
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, isRu ? "Очистить всё" : "Clear all");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            new AlertDialog.Builder(this)
                    .setTitle(isRu ? "Очистить всё?" : "Clear all?")
                    .setMessage(isRu ? "Это удалит все сохранённые чаты."
                                     : "This will delete all saved chats.")
                    .setPositiveButton(isRu ? "Удалить" : "Delete", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            // Удаляем все чаты, а не только найденные поиском
                            for (ChatDatabase.ChatInfo chat : allChats) {
                                chatDb.deleteChat(chat.id);
                            }
                            loadChats();
                        }
                    })
                    .setNegativeButton(isRu ? "Отмена" : "Cancel", null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private int dp(int val) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (val * density);
    }
}
