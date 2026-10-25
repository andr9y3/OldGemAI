package com.geminiapp.chat;

import android.app.Application;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.init(this);
        AppLog.installCrashHandler();
        AppLog.logStartup();
    }
}
