package com.appstronautstudios.universalstoremanager.utils;

import com.android.billingclient.api.Purchase;

public interface StoreEventListener {
    default void storePurchasePending(Purchase purchase) {
        // optional method. Do nothing by default
    }

    void storePurchaseComplete(Purchase purchase); // id of completed purchase

    void storePurchaseError(int errorCode);
}
