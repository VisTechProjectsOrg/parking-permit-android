package com.visproj.parkingpermitsync;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class WebViewFragment extends Fragment {

    private static final String URL = "https://fucktorontoparking.ca/history/";

    private WebView webView;
    private ProgressBar progressBar;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_webview, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        webView = view.findViewById(R.id.webView);
        progressBar = view.findViewById(R.id.progressBar);

        setupWebView();
        webView.loadUrl(URL);
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // Prevent ViewPager from intercepting vertical scroll gestures in WebView
        webView.setOnTouchListener(new View.OnTouchListener() {
            private float startX, startY;
            private boolean isScrolling = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getX();
                        startY = event.getY();
                        isScrolling = false;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float dx = Math.abs(event.getX() - startX);
                        float dy = Math.abs(event.getY() - startY);
                        // If scrolling more vertically than horizontally, block ViewPager
                        if (!isScrolling && (dx > 10 || dy > 10)) {
                            isScrolling = true;
                            if (dy > dx) {
                                v.getParent().requestDisallowInterceptTouchEvent(true);
                            }
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        isScrolling = false;
                        break;
                }
                return false;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
            }
        });
    }

    public void refresh() {
        if (webView != null) {
            webView.reload();
        }
    }

    public boolean canGoBack() {
        return webView != null && webView.canGoBack();
    }

    public void goBack() {
        if (webView != null) {
            webView.goBack();
        }
    }

    public void loadUrl(String url) {
        if (webView != null) {
            webView.loadUrl(url);
        }
    }
}
