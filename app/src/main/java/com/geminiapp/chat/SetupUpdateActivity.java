package com.geminiapp.chat;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Страница первоначальной настройки между выбором языка и вводом API ключа:
 * сообщает, что в приложении есть автообновление, и даёт галку, чтобы его отключить.
 */
public class SetupUpdateActivity extends Activity {

    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);

        final boolean isRu = Prefs.LANG_RU.equals(prefs.getLanguage());
        setTitle(isRu ? "Автообновление" : "Auto-update");

        ScrollView scrollView = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(20, 20, 20, 20);
        scrollView.addView(layout);

        TextView tvTitle = new TextView(this);
        tvTitle.setTextSize(20);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setText(isRu ? "Автообновление" : "Auto-update");
        tvTitle.setPadding(0, 0, 0, 16);
        layout.addView(tvTitle);

        TextView tvInfo = new TextView(this);
        tvInfo.setTextSize(16);
        if (isRu) {
            tvInfo.setText(
                "В приложении есть автообновление.\n\n" +
                "Раз в сутки приложение смотрит на GitHub (github.com/andr9y3/OldGemAI/releases), " +
                "не вышла ли новая версия. Если вышла, оно покажет окно с номерами версий " +
                "и кнопкой \"Установить\".\n\n" +
                "Ничего не скачивается и не устанавливается без Вашего нажатия на эту кнопку.\n\n" +
                "Автообновление можно отключить сейчас галкой ниже или позже в настройках.\n");
        } else {
            tvInfo.setText(
                "The app has auto-update.\n\n" +
                "Once a day the app checks GitHub (github.com/andr9y3/OldGemAI/releases) " +
                "for a new version. If there is one, it shows a window with the version numbers " +
                "and an \"Install\" button.\n\n" +
                "Nothing is downloaded or installed until you tap that button.\n\n" +
                "You can turn auto-update off now with the checkbox below, or later in Settings.\n");
        }
        layout.addView(tvInfo);

        final CheckBox cbDisable = new CheckBox(this);
        cbDisable.setText(isRu ? "Отключить автообновление" : "Turn off auto-update");
        cbDisable.setChecked(!prefs.isAutoUpdate());
        layout.addView(cbDisable);

        Button btnContinue = new Button(this);
        btnContinue.setText(isRu ? "Продолжить" : "Continue");
        layout.addView(btnContinue);

        setContentView(scrollView);

        btnContinue.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setAutoUpdate(!cbDisable.isChecked());
                prefs.setUpdateSetupDone(true);
                startActivity(new Intent(SetupUpdateActivity.this, SetupApiKeyActivity.class));
                finish();
            }
        });
    }
}
