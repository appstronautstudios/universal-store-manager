package com.appstronautstudios.universalstoremanager;

import android.os.Bundle;
import android.widget.Toast;

import com.appstronautstudios.library.managers.StoreManager;
import com.appstronautstudios.library.utils.StoreEventListener;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        StoreManager.getInstance().setLicenseKey("abcdefg");
        StoreManager.getInstance().setManagedSkus(null, null);
        StoreManager.getInstance().setupBillingProcessor(this);
        StoreManager.getInstance().setEventListener(new StoreEventListener() {
            @Override
            public void storeBillingInitialized() {
                Toast.makeText(MainActivity.this, "store billing initialized", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void storePurchaseComplete(String purchaseJson) {
                Toast.makeText(MainActivity.this, "purchased: " + purchaseJson, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void storePurchaseError(int errorCode) {
                Toast.makeText(MainActivity.this, "failed to purchase: " + errorCode, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
