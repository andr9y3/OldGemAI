package com.ialwaysloveyou.oldgemai;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
 * Список выбранных файлов из ClipData (Android 4.1+, API 16). Вызывать только когда
 * Http.sdk() >= PickerCompat.CLIP_SDK: на более старых версиях класса ClipData нет.
 */
final class PickerClip {

    private PickerClip() {}

    static List<Uri> uris(Intent data) {
        List<Uri> out = new ArrayList<Uri>();
        if (data == null) return out;
        ClipData clip = data.getClipData();
        if (clip == null) return out;
        for (int i = 0; i < clip.getItemCount(); i++) {
            Uri u = clip.getItemAt(i).getUri();
            if (u != null) out.add(u);
        }
        return out;
    }
}
