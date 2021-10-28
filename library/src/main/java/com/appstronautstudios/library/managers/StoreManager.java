package com.appstronautstudios.library.managers;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.anjlab.android.iab.v3.BillingProcessor;
import com.anjlab.android.iab.v3.SkuDetails;
import com.anjlab.android.iab.v3.SuccessFailListener;
import com.anjlab.android.iab.v3.TransactionDetails;
import com.appstronautstudios.library.utils.StoreEventListener;

import java.util.ArrayList;
import java.util.List;

public class StoreManager {

    private static final StoreManager INSTANCE = new StoreManager();

    private boolean debuggable;
    private String licenseKey;
    private ArrayList<String> subscriptionSkus = new ArrayList<>();
    private ArrayList<String> consumableSkus = new ArrayList<>();
    private StoreEventListener listener;

    private BillingProcessor bp;

    private StoreManager() {
        if (INSTANCE != null) {
            throw new IllegalStateException("Already instantiated");
        }
    }

    public static StoreManager getInstance() {
        return INSTANCE;
    }

    public boolean isStoreLoaded() {
        return bp != null && bp.isInitialized();
    }

    public void setDebuggable(boolean debuggable) {
        this.debuggable = debuggable;
    }

    public void setLicenseKey(String licenseKey) {
        this.licenseKey = licenseKey;
    }

    public void setManagedSkus(List<String> subscriptionSkus, List<String> consumableSkus) {
        if (subscriptionSkus != null) {
            this.subscriptionSkus.clear();
            this.subscriptionSkus.addAll(subscriptionSkus);
        } else {
            this.subscriptionSkus = new ArrayList<>();
        }

        if (consumableSkus != null) {
            this.consumableSkus.clear();
            this.consumableSkus.addAll(consumableSkus);
        } else {
            this.consumableSkus = new ArrayList<>();
        }
    }

    public void setEventListener(StoreEventListener l) {
        listener = l;
    }

    public void removeListener() {
        listener = null;
    }

    public void setupBillingProcessor(final Context context) {
        if (BillingProcessor.isIabServiceAvailable(context)) {
            if (isStoreLoaded()) {
                // DANGER created already! If you dump the old one the callbacks attached to it are
                // at risk. Manually call initialize like it succeeded again.
                handleBillingInitialize();
            } else {
                bp = new BillingProcessor(context, licenseKey, new BillingProcessor.IBillingHandler() { // this does initialize on its own
                    @Override
                    public void onBillingError(final int responseCode, Throwable error) {
                        switch (responseCode) {
                            case 1:
                                // User pressed back or canceled a dialog
                                break;
                            case 2:
                                // Network connection is down
                                break;
                            case 3:
                                bp = null;
                                // Billing API version is not supported for the type requested
                                break;
                            case 4:
                                // Requested product is not available for purchase
                                break;
                            case 5:
                                // Invalid arguments provided to the API. This error can also indicate that
                                // the application was not correctly signed or properly set up for In-app Billing
                                // in Google Play, or does not have the necessary permissions in its manifest
                                break;
                            case 6:
                                bp = null;
                                // Fatal error during the API action
                                break;
                            case 7:
                                // Failure to purchase since item is already owned
                                break;
                            case 8:
                                // Failure to consume since item is not owned
                                break;
                            default:
                                break;
                        }
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                if (listener != null) {
                                    listener.storePurchaseError(responseCode);
                                }
                            }
                        });
                    }

                    @Override
                    public void onBillingInitialized() {
                        handleBillingInitialize();
                    }

                    @Override
                    public void onProductPurchased(@NonNull String productId, TransactionDetails details) {
                        handlePurchaseResult(productId);
                    }

                    @Override
                    public void onPurchaseHistoryRestored() {
                        handleBillingInitialize();
                    }
                });
            }
        } else {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    if (listener != null) {
                        listener.storeBillingInitialized(false, "In app billing not supported on this device. Make sure you have Google Play Service installed and are signed into the Play Store.");
                    }
                }
            });
        }
    }

    private void handleBillingInitialize() {
        if (isStoreLoaded()) {
            bp.loadOwnedPurchasesFromGoogle(); // apparently this is synchronous now

            // safety ACK all transactions
            checkAndAcknowledgeTransactionDetails();

            // alert user to completed init
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    if (listener != null) {
                        listener.storeBillingInitialized(true, "");
                    }
                }
            });
        }
    }

    private void handlePurchaseResult(final String id) {
        if (isStoreLoaded()) {
            // safety ACK and toggle local prefs premium
            checkAndAcknowledgeTransactionDetails();

            // alert user to completed purhcase
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    if (listener != null) {
                        listener.storePurchaseComplete(id);
                    }
                }
            });
        }
    }

    public void makeSubscription(String SKU, Activity context) {
        if (isStoreLoaded()) {
            bp.subscribe(context, SKU);
        }
    }

    public void makePurchase(String SKU, Activity context) {
        if (isStoreLoaded()) {
            bp.purchase(context, SKU);
        }
    }

    public void consumePurchase(String SKU) {
        if (isStoreLoaded()) {
            bp.consumePurchase(SKU);
        }
    }

    public void getAllManagedSkuDetailsAsync(final SuccessFailListener listener) {
        final ArrayList<SkuDetails> skuDetails = new ArrayList<>();

        if (isStoreLoaded()) {
            bp.getSubscriptionListingDetails(subscriptionSkus, new SuccessFailListener() {
                @Override
                public void success(Object object) {
                    skuDetails.addAll((ArrayList<SkuDetails>) object);
                    bp.getPurchaseListingDetails(consumableSkus, new SuccessFailListener() {
                        @Override
                        public void success(Object object) {
                            skuDetails.addAll((ArrayList<SkuDetails>) object);
                            new Handler(Looper.getMainLooper()).post(new Runnable() {
                                @Override
                                public void run() {
                                    if (listener != null) {
                                        listener.success(skuDetails);
                                    }
                                }
                            });
                        }

                        @Override
                        public void fail(final Object object) {
                            new Handler(Looper.getMainLooper()).post(new Runnable() {
                                @Override
                                public void run() {
                                    if (listener != null) {
                                        listener.fail(object);
                                    }
                                }
                            });
                        }
                    });
                }

                @Override
                public void fail(final Object object) {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            if (listener != null) {
                                listener.fail(object);
                            }
                        }
                    });
                }
            });
        }
    }

    /**
     * @return - true if subscribed to any of the managed subscription SKUs or if owns any of the
     * managed consumable SKUs. False otherwise
     */
    public boolean hasAnySubOrConsumable() {
        return hasAnySubOrConsumable(subscriptionSkus, consumableSkus);
    }

    /**
     * @return - true if subscribed to any of the provided subscription SKUs or if owns any of the
     * provided consumable SKUs. False otherwise. null for either param means default back to
     * managed set
     */
    public boolean hasAnySubOrConsumable(@NonNull List<String> subscriptionSkus, @NonNull List<String> consumableSkus) {
        if (debuggable) {
            return true;
        } else {
            return isSubscribedToAny(subscriptionSkus) || hasAnyConsumable(consumableSkus);
        }
    }

    /**
     * @param sku - SKU to check
     * @return - true if purchased provided SKU, false otherwise
     */
    public boolean hasConsumable(String sku) {
        ArrayList<String> skus = new ArrayList<>();
        skus.add(sku);
        return hasAnyConsumable(skus);
    }

    /**
     * @return - true if purchased any managed consumable, false otherwise
     */
    public boolean hasAnyConsumable() {
        return hasAnyConsumable(consumableSkus);
    }

    /**
     * @param consumableSkus - SKUs to check purchase status
     * @return - true if purchased any provided SKUs, false otherwise
     */
    public boolean hasAnyConsumable(@NonNull List<String> consumableSkus) {
        if (isStoreLoaded()) {
            if (debuggable) {
                return true;
            } else {
                for (String sku : consumableSkus) {
                    if (!consumableSkus.contains(sku)) {
                        throw new RuntimeException(sku + " is not managed. Make sure you call setManagedSkus() before setupBillingProcessor() and try again");
                    } else if (bp.isPurchased(sku)) {
                        return true;
                    }
                }
                return false;
            }
        } else {
            return false;
        }
    }

    /**
     * @param sku - SKU to check
     * @return - true if subscribed to provided SKU, false otherwise
     */
    public boolean isSubscribedTo(@NonNull String sku) {
        ArrayList<String> skus = new ArrayList<>();
        skus.add(sku);
        return isSubscribedToAny(skus);
    }

    /**
     * @return - true if subscribed to any SKUs in the managed set, false otherwise
     */
    public boolean isSubscribedToAny() {
        return isSubscribedToAny(subscriptionSkus);
    }

    /**
     * @param subscriptionSkus - SKUs to check subscription status
     * @return - true if subscribed to any provided SKUs, false otherwise
     */
    public boolean isSubscribedToAny(@NonNull List<String> subscriptionSkus) {
        if (isStoreLoaded()) {
            if (debuggable) {
                return true;
            } else {
                for (String sku : subscriptionSkus) {
                    if (!subscriptionSkus.contains(sku)) {
                        throw new RuntimeException(sku + " is not managed. Make sure you call setManagedSkus() before setupBillingProcessor() and try again");
                    } else if (bp.isSubscribed(sku)) {
                        return true;
                    }
                }
                return false;
            }
        } else {
            return false;
        }
    }

    private void checkAndAcknowledgeTransactionDetails() {
        // TODO
        /*
        if (isStoreLoaded()) {
            for (String sku : subscriptionSkus) {
                TransactionDetails transaction = bp.getSubscriptionTransactionDetails(sku);
                if (transaction != null && !transaction.isAcknowledged()) {
                    bp.acknowledgeSubscription(transaction.getSku());
                }
            }
            for (String sku : consumableSkus) {
                TransactionDetails transaction = bp.getPurchaseTransactionDetails(sku);
                if (transaction != null && !transaction.isAcknowledged()) {
                    bp.acknowledgeManagedProduct(transaction.getSku());
                }
            }
        }
         */
    }

    /**
     * load fresh subscription data and update subscription end dates. Also ack purchase and subs
     * as necessary
     */
    public void checkSubscriptionStatus() {
        if (isStoreLoaded()) {
            bp.loadOwnedPurchasesFromGoogle();
            checkAndAcknowledgeTransactionDetails();
        }
    }
}
