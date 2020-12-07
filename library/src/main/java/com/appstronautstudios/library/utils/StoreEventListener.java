package com.appstronautstudios.library.utils;


public interface StoreEventListener {
    void storeBillingInitialized();

    void storePurchaseComplete(String purchaseJson);

    void storePurchaseError(int errorCode);
}
