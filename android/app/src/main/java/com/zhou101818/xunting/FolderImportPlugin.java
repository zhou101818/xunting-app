package com.zhou101818.xunting;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;

import androidx.activity.result.ActivityResult;
import androidx.documentfile.provider.DocumentFile;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 原生文件夹导入插件：
 *  - pickFolder      选择手机上的文件夹（系统目录选择器）
 *  - importFolder    递归扫描音频并复制到应用内部存储
 *  - readAudio       按需读取单个音频内容（base64），用于播放
 *  - removeAlbum     删除某专辑已导入的音频副本
 */
@CapacitorPlugin(name = "FolderImport")
public class FolderImportPlugin extends Plugin {

    private static final int MAX_FILES = 3000;
    private static final long MAX_FILE_BYTES = 60L * 1024 * 1024;

    private static boolean isAudio(String n) {
        if (n == null) return false;
        int i = n.lastIndexOf('.');
        if (i < 0) return false;
        String ext = n.substring(i + 1).toLowerCase();
        return ext.equals("mp3") || ext.equals("m4a") || ext.equals("aac") || ext.equals("wav")
                || ext.equals("flac") || ext.equals("ogg") || ext.equals("oga") || ext.equals("opus")
                || ext.equals("webm") || ext.equals("weba");
    }

    @PluginMethod
    public void pickFolder(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(call, intent, "pickResult");
    }

    @ActivityCallback
    private void pickResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("cancel");
            return;
        }
        Uri treeUri = result.getData().getData();
        if (treeUri == null) {
            call.reject("cancel");
            return;
        }
        try {
            getContext().getContentResolver().takePersistableUriPermission(
                    treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        DocumentFile dir = DocumentFile.fromTreeUri(getContext(), treeUri);
        String name = dir != null && dir.getName() != null ? dir.getName() : "我的文件夹";
        JSObject ret = new JSObject();
        ret.put("uri", treeUri.toString());
        ret.put("name", name);
        call.resolve(ret);
    }

    @PluginMethod
    public void importFolder(PluginCall call) {
        String uriStr = call.getString("uri");
        String albumId = call.getString("albumId");
        if (uriStr == null || albumId == null) {
            call.reject("参数缺失");
            return;
        }
        try {
            DocumentFile root = DocumentFile.fromTreeUri(getContext(), Uri.parse(uriStr));
            if (root == null) {
                call.reject("无法读取该文件夹");
                return;
            }
            File destRoot = new File(getContext().getFilesDir(), "audio/" + albumId);
            JSArray files = new JSArray();
            int count = walkFolder(root, "", destRoot, files, 0);
            JSObject ret = new JSObject();
            ret.put("files", files);
            ret.put("count", count);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("导入失败：" + e.getMessage());
        }
    }

    private int walkFolder(DocumentFile dir, String prefix, File destRoot, JSArray out, int count) {
        if (count >= MAX_FILES) return count;
        DocumentFile[] children = dir.listFiles();
        for (DocumentFile child : children) {
            if (count >= MAX_FILES) break;
            String nm = child.getName() == null ? "" : child.getName();
            if (nm.isEmpty()) continue;
            if (child.isDirectory()) {
                count = walkFolder(child, prefix + nm + "/", destRoot, out, count);
            } else if (isAudio(nm)) {
                try {
                    File dest = new File(destRoot, prefix + nm);
                    File parent = dest.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    InputStream in = getContext().getContentResolver().openInputStream(child.getUri());
                    if (in == null) continue;
                    OutputStream os = new FileOutputStream(dest);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    in.close();
                    os.close();
                    JSObject f = new JSObject();
                    f.put("path", prefix + nm);
                    f.put("name", nm);
                    f.put("size", dest.length());
                    out.put(f);
                    count++;
                } catch (Exception ignored) {
                }
            }
        }
        return count;
    }

    @PluginMethod
    public void readAudio(PluginCall call) {
        String albumId = call.getString("albumId");
        String path = call.getString("path");
        if (albumId == null || path == null) {
            call.reject("参数缺失");
            return;
        }
        try {
            File f = new File(new File(getContext().getFilesDir(), "audio/" + albumId), path);
            if (!f.exists()) {
                call.reject("文件不存在，请重新导入该专辑");
                return;
            }
            long len = f.length();
            if (len <= 0) {
                call.reject("文件为空");
                return;
            }
            if (len > MAX_FILE_BYTES) {
                call.reject("文件超过 60MB，暂不支持");
                return;
            }
            byte[] bytes = new byte[(int) len];
            FileInputStream fis = new FileInputStream(f);
            int off = 0;
            while (off < bytes.length) {
                int r = fis.read(bytes, off, bytes.length - off);
                if (r <= 0) break;
                off += r;
            }
            fis.close();
            JSObject ret = new JSObject();
            ret.put("data", Base64.encodeToString(bytes, Base64.NO_WRAP));
            ret.put("size", len);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("读取失败：" + e.getMessage());
        }
    }

    @PluginMethod
    public void removeAlbum(PluginCall call) {
        String albumId = call.getString("albumId");
        if (albumId == null) {
            call.reject("参数缺失");
            return;
        }
        try {
            File dir = new File(new File(getContext().getFilesDir(), "audio"), albumId);
            deleteRecursive(dir);
            call.resolve();
        } catch (Exception e) {
            call.reject("删除失败：" + e.getMessage());
        }
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] cs = f.listFiles();
            if (cs != null) for (File c : cs) deleteRecursive(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}