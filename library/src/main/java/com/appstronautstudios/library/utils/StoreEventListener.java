package com.appstronautstudios.library.utils;


public interface StoreEventListener {
    void storeBillingInitialized(boolean success, String message);

    void storePurchaseComplete(String purchaseJson);

    void storePurchaseError(int errorCode);
}
