package com.appstronautstudios.universalstoremanagerdemo;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.appstronautstudios.universalstoremanager.managers.StoreManager;
import com.appstronautstudios.universalstoremanager.utils.StoreEventListener;
import com.appstronautstudios.universalstoremanager.utils.SuccessFailListener;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final TextView messageTV = findViewById(R.id.message);

        StoreManager.getInstance().setManagedSkus(null, null);
        StoreManager.getInstance().addEventListener(new StoreEventListener() {
            @Override
            public void storePurchaseComplete(String sku) {
                messageTV.setText("purchased: " + sku);
            }

            @Override
            public void storePurchaseError(int errorCode) {
                messageTV.setText("failed to purchase: " + errorCode);
            }
        });
        StoreManager.getInstance().setupBillingProcessor(this, null, null, new SuccessFailListener() {
            @Override
            public void success(Object object) {
                messageTV.setText("store billing initialized");
            }

            @Override
            public void failure(Object object) {
                messageTV.setText("store billing failed to initialize.");
            }
        });
    }
}
