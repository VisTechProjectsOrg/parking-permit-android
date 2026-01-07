package com.visproj.parkingpermitsync;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class MainPagerAdapter extends FragmentStateAdapter {

    private BleStatusFragment bleStatusFragment;
    private WebViewFragment webViewFragment;

    public MainPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        if (position == 0) {
            bleStatusFragment = new BleStatusFragment();
            return bleStatusFragment;
        } else {
            webViewFragment = new WebViewFragment();
            return webViewFragment;
        }
    }

    @Override
    public int getItemCount() {
        return 2;
    }

    public BleStatusFragment getBleStatusFragment() {
        return bleStatusFragment;
    }

    public WebViewFragment getWebViewFragment() {
        return webViewFragment;
    }
}
