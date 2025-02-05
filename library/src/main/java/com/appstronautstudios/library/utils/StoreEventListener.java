package com.appstronautstudios.library.utils;

public interface StoreEventListener {
    void storeBillingInitialized(boolean success, int code); // error code on failure

    void storePurchaseComplete(String sku); // id of completed purchase

    void storePurchaseError(int errorCode);
}
