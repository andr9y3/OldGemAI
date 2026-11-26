package com.ialwaysloveyou.oldgemai;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;

/**
 * Открывает системный выбор картинок или файлов. С Android 4.3 (API 18) в выборе можно отметить
 * сразу несколько файлов; на более старых версиях выбирается по одному, а новые вложения
 * добавляются к уже выбранным. Классы с ClipData вынесены в PickerClip, чтобы эта часть
 * не ссылалась на API, которого нет на старых Android.
 */
public final class PickerCompat {

    private PickerCompat() {}

    /** С этой версии Android выбор нескольких файлов (EXTRA_ALLOW_MULTIPLE) поддерживается. */
    public static final int MULTI_SDK = 18;

    /** С этой версии Android результат с несколькими файлами приходит в ClipData. */
    public static final int CLIP_SDK = 16;

    /**
     * images = true: выбор картинок, false: выбор любых файлов.
     * Результат придёт в onActivityResult с этим requestCode. false - нет приложения для выбора.
     */
    public static boolean start(Activity activity, int requestCode, boolean images) {
        int sdk = Http.sdk();
        Intent i;
        if (images && sdk < MULTI_SDK) {
            // Старые Android: привычная галерея, по одной картинке за раз
            i = new Intent(Intent.ACTION_PICK);
            i.setType("image/*");
        } else {
            i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType(images ? "image/*" : "*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            if (sdk >= MULTI_SDK) {
                // Значение Intent.EXTRA_ALLOW_MULTIPLE: строкой, чтобы не зависеть от версии SDK
                i.putExtra("android.intent.extra.ALLOW_MULTIPLE", true);
            }
        }
        try {
            activity.startActivityForResult(i, requestCode);
            return true;
        } catch (ActivityNotFoundException e) {
            AppLog.w("UI", "Picker: no activity found");
            return false;
        } catch (Throwable t) {
            AppLog.w("UI", "Picker failed: " + t);
            return false;
        }
    }
}
