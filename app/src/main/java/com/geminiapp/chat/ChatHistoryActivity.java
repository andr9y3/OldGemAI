package com.geminiapp.chat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

public class ChatHistoryActivity extends Activity {

    private ChatDatabase chatDb;
    private Prefs prefs;
    private ListView lvChats;
    private TextView tvEmpty;
    private List<ChatDatabase.ChatInfo> chatList;
    private ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        chatDb = new ChatDatabase(this);
        prefs = new Prefs(this);
        chatList = new ArrayList<ChatDatabase.ChatInfo>();

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);

        tvEmpty = new TextView(this);
        tvEmpty.setText("Нет сохранённых чатов");
        tvEmpty.setPadding(20, 20, 20, 20);
        tvEmpty.setTextSize(16);
        mainLayout.addView(tvEmpty);

        lvChats = new ListView(this);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.FILL_PARENT, 0, 1.0f);
        mainLayout.addView(lvChats, listParams);

        setContentView(mainLayout);
        setTitle("История чатов");

        loadChats();

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
        chatList = chatDb.getAllChats();
        List<String> titles = new ArrayList<String>();

        for (ChatDatabase.ChatInfo chat : chatList) {
            String preview = chat.title.length() > 40
                ? chat.title.substring(0, 40) + "..."
                : chat.title;
            String item = preview + "\n" + chat.getFormattedDate();
            titles.add(item);
        }

        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, titles);
        lvChats.setAdapter(adapter);

        tvEmpty.setVisibility(chatList.isEmpty() ? View.VISIBLE : View.GONE);
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

        CharSequence[] items = {"Открыть", "Переименовать", "Удалить"};

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
                .setTitle("Переименовать чат")
                .setView(input)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String newTitle = input.getText().toString().trim();
                        if (!newTitle.isEmpty()) {
                            chatDb.renameChat(chatInfo.id, newTitle);
                            loadChats();
                        }
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void deleteChat(final int position) {
        if (position < 0 || position >= chatList.size()) return;

        final ChatDatabase.ChatInfo chatInfo = chatList.get(position);

        new AlertDialog.Builder(this)
                .setTitle("Удалить чат?")
                .setMessage(chatInfo.title)
                .setPositiveButton("Удалить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        chatDb.deleteChat(chatInfo.id);
                        loadChats();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Очистить всё");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            new AlertDialog.Builder(this)
                    .setTitle("Очистить всё?")
                    .setMessage("Это удалит все сохранённые чаты.")
                    .setPositiveButton("Удалить", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            for (ChatDatabase.ChatInfo chat : chatList) {
                                chatDb.deleteChat(chat.id);
                            }
                            loadChats();
                        }
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
