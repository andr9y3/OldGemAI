package com.geminiapp.chat;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

public class SetupLanguageActivity extends Activity {

    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = new Prefs(this);

        if (prefs.isSetupDone()) {
            goToMain();
            return;
        }

        if (prefs.getLanguage() != null) {
            goToApiKey();
            return;
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(30, 30, 30, 30);

        Button btnRu = new Button(this);
        btnRu.setText("Русский");
        LinearLayout.LayoutParams lpRu = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpRu.setMargins(0, 0, 0, 20);
        layout.addView(btnRu, lpRu);

        Button btnEn = new Button(this);
        btnEn.setText("English");
        layout.addView(btnEn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(layout);

        btnRu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setLanguage(Prefs.LANG_RU);
                goToApiKey();
            }
        });

        btnEn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setLanguage(Prefs.LANG_EN);
                goToApiKey();
            }
        });
    }

    private void goToApiKey() {
        startActivity(new Intent(this, SetupApiKeyActivity.class));
        finish();
    }

    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}