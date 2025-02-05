package com.appstronautstudios.library.utils;

// TODO how do we want this store event listener to look and what data do we want to pass back
// TODO has to be objects that are generic or can be exposed to the client (e.g. can't use BillingResult)
public interface StoreEventListener {
    void storeBillingInitialized(boolean success, String message);

    void storePurchaseComplete(String purchaseJson);

    void storePurchaseError(int errorCode);
}
y