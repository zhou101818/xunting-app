package com.zhou101818.xunting;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(FolderImportPlugin.class);
        super.onCreate(savedInstanceState);
    }
}