package com.visproj.parkingpermitsync;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class GitHubSyncTask {
    private static final String TAG = "GitHubSyncTask";

    public interface SyncCallback {
        void onSuccess(PermitData permit, boolean isNew);
        void onError(String error);
    }

    private final Context context;
    private final PermitRepository repository;
    private final OkHttpClient client;
    private final Gson gson;
    private final ExecutorService executor;
    private final Handler mainHandler;

    public GitHubSyncTask(Context context) {
        this.context = context;
        this.repository = new PermitRepository(context);
        this.client = new OkHttpClient();
        this.gson = new Gson();
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void sync(SyncCallback callback) {
        executor.execute(() -> {
            try {
                String url = repository.getGitHubUrl();
                Log.d(TAG, "Syncing from: " + url);

                Request request = new Request.Builder()
                    .url(url)
                    .header("Cache-Control", "no-cache")
                    .build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        notifyError(callback, "HTTP " + response.code());
                        return;
                    }

                    String json = response.body().string();
                    PermitData newPermit = gson.fromJson(json, PermitData.class);

                    if (newPermit == null || !newPermit.isValid()) {
                        notifyError(callback, "Invalid permit data");
                        return;
                    }

                    PermitData oldPermit = repository.getPermit();
                    boolean isNew = oldPermit == null ||
                        !oldPermit.permitNumber.equals(newPermit.permitNumber);

                    repository.savePermit(newPermit);
                    Log.d(TAG, "Synced permit: " + newPermit.permitNumber + " (new=" + isNew + ")");

                    notifySuccess(callback, newPermit, isNew);
                }
            } catch (IOException e) {
                Log.e(TAG, "Sync failed", e);
                notifyError(callback, e.getMessage());
            }
        });
    }

    private void notifySuccess(SyncCallback callback, PermitData permit, boolean isNew) {
        if (callback != null) {
            mainHandler.post(() -> callback.onSuccess(permit, isNew));
        }
    }

    private void notifyError(SyncCallback callback, String error) {
        if (callback != null) {
            mainHandler.post(() -> callback.onError(error));
        }
    }
}
