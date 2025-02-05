package com.appstronautstudios.universalstoremanagerdemo;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.appstronautstudios.universalstoremanager.managers.StoreManager;
import com.appstronautstudios.universalstoremanager.utils.StoreEventListener;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final TextView messageTV = findViewById(R.id.message);

        StoreManager.getInstance().setLicenseKey("abcdefg");
        StoreManager.getInstance().setManagedSkus(null, null);
        StoreManager.getInstance().addEventListener(new StoreEventListener() {
            @Override
            public void storeBillingInitialized(boolean success, int code) {
                if (success) {
                    messageTV.setText("store billing initialized");
                } else {
                    messageTV.setText("store billing failed to initialize. Error code: " + code);
                }
            }

            @Override
            public void storePurchaseComplete(String sku) {
                messageTV.setText("purchased: " + sku);
            }

            @Override
            public void storePurchasePending(String sku) {

            }

            @Override
            public void storePurchaseError(int errorCode) {
                messageTV.setText("failed to purchase: " + errorCode);
            }
        });
        StoreManager.getInstance().setupBillingProcessor(this, null, null);
    }
}
