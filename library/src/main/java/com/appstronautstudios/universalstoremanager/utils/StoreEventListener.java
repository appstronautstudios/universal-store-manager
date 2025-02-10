package com.appstronautstudios.universalstoremanager.utils;

public interface StoreEventListener {
    void storeBillingInitialized(boolean success, int code); // error code on failure

    default void storePurchasePending(String sku) {
        // optional method. Do nothing by default
    }

    void storePurchaseComplete(String sku); // id of completed purchase

    void storePurchaseError(int errorCode);
}
