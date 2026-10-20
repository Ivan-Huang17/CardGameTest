package org.cocos2dx.cpp.highlight;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 把录制好的 MP4 从应用私有目录搬到系统媒体库，使其出现在相册 / 文件管理器的
 * {@code Movies/GameRecorder} 下。
 *
 * <h3>为什么要「先录到私有目录，再搬一次」</h3>
 * <p>另一种做法是先用 MediaStore 建一个 {@code IS_PENDING=1} 的条目、拿到
 * {@code ParcelFileDescriptor} 直接交给 {@code MediaMuxer}，省掉一次拷贝。但那样存在两个问题：
 * <ol>
 *   <li>{@code MediaMuxer(FileDescriptor)} 需要 API 26，本工程 minSdk 是 24；</li>
 *   <li>录制中途进程被杀（低内存、系统回收）会留下一个永远 pending 的僵尸条目，
 *       在部分 ROM 上表现为「相册里有个打不开的空视频」，用户体验更差。</li>
 * </ol>
 * 先写私有目录则天然是原子的：只有完整录完并封盘成功的文件才会被发布。
 */
public final class MediaStoreSaver {

    private static final String TAG = "MediaStoreSaver";

    /** 媒体库中的相对目录（Android 10+ 生效）。 */
    public static final String RELATIVE_DIR = Environment.DIRECTORY_MOVIES + "/GameRecorder";

    /** 应用私有目录下的录制临时目录名。 */
    public static final String PRIVATE_DIR = "recordings";

    private MediaStoreSaver() {
    }

    /**
     * 录制文件应该先写到哪里。
     *
     * <p>优先外部私有目录（容量大、不占系统分区）；不可用时退回内部缓存。
     */
    public static File privateRecordDir(Context context) {
        File base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (base == null) {
            base = context.getFilesDir();
        }
        File dir = new File(base, PRIVATE_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "私有录制目录创建失败：" + dir.getAbsolutePath());
        }
        return dir;
    }

    /**
     * 生成一个尚未存在的临时录制文件。
     *
     * @param prefix 文件名前缀，通常形如 {@code "game_20260928_135401"}
     */
    public static File newTempFile(Context context, String prefix) {
        return new File(privateRecordDir(context), prefix + ".mp4");
    }

    /**
     * 把已封盘的 MP4 发布到系统媒体库。
     *
     * @return 成功时返回媒体库 Uri；失败返回 {@code null}（调用方应保留临时文件并提示用户）
     */
    public static Uri publish(Context context, File source, String displayName) {
        if (source == null || !source.isFile() || source.length() <= 0) {
            return null;
        }
        try {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? publishViaMediaStore(context, source, displayName)
                    : publishLegacy(context, source, displayName);
        } catch (Throwable t) {
            Log.e(TAG, "发布到媒体库失败", t);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Android 10+ 分区存储
    // ------------------------------------------------------------------

    private static Uri publishViaMediaStore(Context context, File source, String displayName)
            throws Exception {
        ContentResolver resolver = context.getContentResolver();

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_DIR);
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis());
        }

        Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri item = resolver.insert(collection, values);
        if (item == null) {
            throw new IllegalStateException("MediaStore.insert 返回 null");
        }

        try (InputStream in = new FileInputStream(source);
             OutputStream out = resolver.openOutputStream(item, "w")) {
            if (out == null) {
                throw new IllegalStateException("openOutputStream 返回 null");
            }
            byte[] buffer = new byte[1 << 16]; // 64KB
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } catch (Throwable t) {
            // 拷贝失败必须把半成品条目删掉，否则相册里会留一个 0 字节的坏视频
            try {
                resolver.delete(item, null, null);
            } catch (Throwable ignored) {
            }
            throw t;
        }

        ContentValues done = new ContentValues();
        done.put(MediaStore.Video.Media.IS_PENDING, 0);
        resolver.update(item, done, null, null);

        Log.i(TAG, "已发布到媒体库：" + item);
        return item;
    }

    // ------------------------------------------------------------------
    // Android 9 及以下：直接写公共 Movies 目录 + 通知媒体扫描
    // ------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private static Uri publishLegacy(Context context, File source, String displayName)
            throws Exception {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES), "GameRecorder");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建 " + dir.getAbsolutePath());
        }
        File target = uniqueTarget(dir, displayName);

        try (InputStream in = new FileInputStream(source);
             OutputStream out = new java.io.FileOutputStream(target)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }

        final Uri[] holder = new Uri[1];
        final Object lock = new Object();
        MediaScannerConnection.scanFile(context,
                new String[]{target.getAbsolutePath()},
                new String[]{"video/mp4"},
                (path, uri) -> {
                    synchronized (lock) {
                        holder[0] = uri;
                        lock.notifyAll();
                    }
                });
        synchronized (lock) {
            if (holder[0] == null) {
                lock.wait(2000);
            }
        }
        Log.i(TAG, "已发布到 " + target + " → " + holder[0]);
        return holder[0] != null
                ? holder[0]
                : Uri.fromFile(target);
    }

    private static File uniqueTarget(File dir, String displayName) {
        File target = new File(dir, displayName);
        if (!target.exists()) {
            return target;
        }
        int dot = displayName.lastIndexOf('.');
        String base = dot > 0 ? displayName.substring(0, dot) : displayName;
        String ext = dot > 0 ? displayName.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            File candidate = new File(dir, base + "_" + i + ext);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return target;
    }

    /** 默默删除临时文件。 */
    public static void deleteQuietly(File file) {
        if (file == null) {
            return;
        }
        try {
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "临时文件删除失败：" + file.getAbsolutePath());
            }
        } catch (Throwable ignored) {
        }
    }
}
