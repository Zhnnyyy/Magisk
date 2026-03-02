package com.topjohnwu.magisk;

import static android.R.string.no;
import static android.R.string.ok;
import static android.R.string.yes;
import static android.widget.Toast.LENGTH_LONG;
import static com.topjohnwu.magisk.R.string.dling;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.widget.Toast;

import com.topjohnwu.magisk.net.Networking;
import com.topjohnwu.magisk.net.Request;
import com.topjohnwu.magisk.utils.APKInstall;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class DownloadActivity extends Activity {

    private Context themed;
    private boolean dynLoad;
    private ProgressDialog progress;
    private String appName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        themed = new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault);
        appName = getApplicationInfo().loadLabel(getPackageManager()).toString();

        // Only download and dynamic load full APK if hidden
        dynLoad = !getPackageName().equals(BuildConfig.APPLICATION_ID);

        // Inject resources
        try {
            loadResources();
        } catch (Exception e) {
            error(e);
        }

        ProviderInstaller.install(this);

        if (Networking.checkNetworkStatus(this)) {
            showDialog();
        } else {
            new AlertDialog.Builder(themed)
                    .setCancelable(false)
                    .setTitle(appName)
                    .setMessage(noInternetMessage())
                    .setNegativeButton(ok, (d, w) -> finish())
                    .show();
        }
    }

    @Override
    public void finish() {
        super.finish();
        Runtime.getRuntime().exit(0);
    }

    private void error(Throwable e) {
        Log.e(getClass().getSimpleName(), Log.getStackTraceString(e));
        dismissProgress();
        finish();
    }

    private Request request(String url) {
        return Networking.get(url).setErrorHandler((conn, e) -> error(e));
    }

    private void showDialog() {
        new AlertDialog.Builder(themed)
                .setCancelable(false)
                .setTitle(appName)
                .setMessage(upgradeMessage())
                .setPositiveButton(yes, (d, w) -> dlAPK())
                .setNegativeButton(no, (d, w) -> finish())
                .show();
    }

    private void dlAPK() {
        progress = ProgressDialog.show(themed, getString(dling), getString(dling) + " " + appName, true);
        // Download and upgrade the app
        var request = request(BuildConfig.APK_URL).setExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        if (dynLoad) {
            request.getAsFile(StubApk.current(this), file -> {
                dismissProgress();
                StubApk.restartProcess(this);
            });
        } else {
            request.getAsInputStream(input -> {
                var session = APKInstall.startSession(this, null, this::installFailed, this::dismissProgress);
                try (input; var out = session.openStream(this)) {
                    if (out != null)
                        APKInstall.transfer(input, out);
                } catch (IOException e) {
                    error(e);
                    return;
                }
                Intent intent = session.waitIntent();
                if (intent != null) {
                    dismissProgress();
                    startActivity(intent);
                }
            });
        }
    }

    private void dismissProgress() {
        runOnUiThread(() -> {
            if (progress != null) {
                progress.dismiss();
                progress = null;
            }
        });
    }

    private void installFailed() {
        runOnUiThread(() -> {
            dismissProgress();
            Toast.makeText(
                    this,
                    "Install failed. Make sure the downloaded APK uses the same package name and signing key as this app.",
                    LENGTH_LONG
            ).show();
            finish();
        });
    }

    private String upgradeMessage() {
        return "Upgrade to full " + appName + " to finish the setup. Download and install?";
    }

    private String noInternetMessage() {
        return "Please connect to the Internet! Upgrading to full " + appName + " is required.";
    }

    private void decryptResources(OutputStream out) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        SecretKey key = new SecretKeySpec(Bytes.key(), "AES");
        IvParameterSpec iv = new IvParameterSpec(Bytes.iv());
        cipher.init(Cipher.DECRYPT_MODE, key, iv);
        var is = new InflaterInputStream(new CipherInputStream(
                new ByteArrayInputStream(Bytes.res()), cipher));
        try (is; out) {
            APKInstall.transfer(is, out);
        }
    }

    private void loadResources() throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            var fd = Os.memfd_create("res", 0);
            try {
                decryptResources(new FileOutputStream(fd));
                Os.lseek(fd, 0, OsConstants.SEEK_SET);
                var loader = new ResourcesLoader();
                try (var pfd = ParcelFileDescriptor.dup(fd)) {
                    loader.addProvider(ResourcesProvider.loadFromTable(pfd, null));
                    getResources().addLoaders(loader);
                }
            } finally {
                Os.close(fd);
            }
        } else {
            File res = new File(getCodeCacheDir(), "res.apk");
            try (var out = new ZipOutputStream(new FileOutputStream(res))) {
                // AndroidManifest.xml is required on Android 6-, and directory support is broken on Android 9-10
                out.putNextEntry(new ZipEntry("AndroidManifest.xml"));
                try (var stubApk = new ZipFile(getPackageCodePath())) {
                    APKInstall.transfer(stubApk.getInputStream(stubApk.getEntry("AndroidManifest.xml")), out);
                }
                out.putNextEntry(new ZipEntry("resources.arsc"));
                decryptResources(out);
            }
            StubApk.addAssetPath(getResources(), res.getPath());
        }
    }
}
