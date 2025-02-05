package com.appstronautstudios.universalstoremanager.utils;

public interface StoreEventListener {
    void storeBillingInitialized(boolean success, int code); // error code on failure

    void storePurchaseComplete(String sku); // id of completed purchase

    void storePurchasePending(String sku);

    void storePurchaseError(int errorCode);
}
