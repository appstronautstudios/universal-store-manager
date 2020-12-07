package com.appstronautstudios.library.managers;

import android.app.Activity;
import android.content.Context;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.SkuDetails;
import com.anjlab.android.iab.v3.BillingProcessor;
import com.appstronautstudios.library.utils.StoreEventListener;

import java.util.ArrayList;
import java.util.List;

public class StoreManager {

    private static final StoreManager INSTANCE = new StoreManager();

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

    private boolean isStoreLoaded() {
        return bp != null && bp.isInitialized();
    }

    public void setLicenseKey(String licenseKey) {
        this.licenseKey = licenseKey;
    }

    public void setManagedSkus(ArrayList<String> subscriptionSkus, ArrayList<String> consumableSkus) {
        if (subscriptionSkus != null) {
            this.subscriptionSkus = subscriptionSkus;
        } else {
            this.subscriptionSkus = new ArrayList<>();
        }

        if (consumableSkus != null) {
            this.consumableSkus = consumableSkus;
        } else {
            this.consumableSkus = new ArrayList<>();
        }
    }

    public void setupBillingProcessor(final Context context) {
        if (BillingProcessor.isIabServiceAvailable(context)) {
            if (isStoreLoaded()) {
                // DANGER created already! If you dump the old one the callbacks attached to it are
                // at risk. Manually call initialize like it succeeded again.
                handleBillingInitialize();
            } else {
                bp = new BillingProcessor(context, licenseKey, new BillingProcessor.IBillingHandler() {
                    @Override
                    public void onBillingError(BillingResult result) {
                        switch (result.getResponseCode()) {
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
                        if (listener != null) {
                            listener.storePurchaseError(result.getResponseCode());
                        }
                    }

                    @Override
                    public void onBillingInitialized() {
                        handleBillingInitialize();
                    }

                    @Override
                    public void onProductPurchased(Purchase purchase) {
                        handlePurchaseResult(purchase);
                    }

                    @Override
                    public void onQuerySkuDetails(List<SkuDetails> skuDetails) {
                        if (listener != null) {
                            listener.storeBillingInitialized();
                        }
                    }

                    @Override
                    public void onPurchaseHistoryRestored(List<String> products) {
                        handleBillingInitialize();
                    }

                    @Override
                    public void onConsumeSuccess(Purchase purchase) {
                    }

                    @Override
                    public void onAcknowledgeSuccess(Purchase purchase) {
                    }
                });
            }
        }
    }

    private void handleBillingInitialize() {
        if (isStoreLoaded()) {
            bp.getSkuDetailsAsync(subscriptionSkus, BillingClient.SkuType.SUBS);
            bp.getSkuDetailsAsync(consumableSkus, BillingClient.SkuType.INAPP);

            // safety ACK all transactions
            checkAndAcknowledgeTransactionDetails();
        }
    }

    private void handlePurchaseResult(Purchase purchase) {
        if (isStoreLoaded()) {
            // safety ACK and toggle local prefs premium
            checkAndAcknowledgeTransactionDetails();

            if (listener != null) {
                listener.storePurchaseComplete(purchase.getOriginalJson());
            }
        }
    }

    public void makeSubscription(String SKU, Activity context) {
        bp.subscribe(context, SKU);
    }

    public void makePurchase(String SKU, Activity context) {
        bp.purchase(context, SKU);
    }

    public void consumePurchase(String SKU) {
        bp.consumePurchase(SKU);
    }

    /**
     * @return - SkuDetails objects for all consumables and subscriptions being managed. Make sure
     * setManagedSkus is called before setupBillingProcessor
     */
    public List<SkuDetails> getAllManagedSkuDetails() {
        ArrayList<SkuDetails> skuDetails = new ArrayList<>();

        if (isStoreLoaded()) {
            for (String sku : subscriptionSkus) {
                skuDetails.add(bp.getSubscriptionListingDetails(sku));
            }
            for (String sku : consumableSkus) {
                skuDetails.add(bp.getPurchaseListingDetails(sku));
            }
        }
        return skuDetails;
    }

    /**
     * @return - true if subscribed to any of the managed subscription SKUs or if owns any of the
     * managed consumable SKUs. False otherwise
     */
    public boolean hasPremiumOrSub() {
        return hasPremiumOrSub(null, null);
    }

    /**
     * @return - true if subscribed to any of the provided subscription SKUs or if owns any of the
     * provided consumable SKUs. False otherwise. null for either param means default back to
     * managed set
     */
    public boolean hasPremiumOrSub(ArrayList<String> subscriptionSkus, ArrayList<String> consumableSkus) {
        ArrayList<String> subSkus = subscriptionSkus;
        ArrayList<String> consSkus = consumableSkus;
        if (subscriptionSkus == null) {
            subSkus = this.subscriptionSkus;
        }
        if (consumableSkus == null) {
            consSkus = this.consumableSkus;
        }

        return isSubscribedTo(subSkus) || hasAtLeastOnePremium(consSkus);
    }

    public boolean hasPremium(String sku) {
        if (sku != null) {
            ArrayList<String> skus = new ArrayList<>();
            skus.add(sku);
            return hasAtLeastOnePremium(skus);
        } else {
            return false;
        }
    }

    public boolean hasAtLeastOnePremium(ArrayList<String> consumableSkus) {
        if (bp == null || consumableSkus == null) {
            return false; // probably unnecessary but lets just be safe
        } else {
            for (String sku : consumableSkus) {
                if (bp.isPurchased(sku)) {
                    return true;
                }
            }
            return false;
        }
    }

    public boolean isSubscribedTo(String sku) {
        if (sku != null) {
            ArrayList<String> skus = new ArrayList<>();
            skus.add(sku);
            return isSubscribedTo(skus);
        } else {
            return false;
        }
    }

    /**
     * @param subscriptionSkus - SKUs to check subscription status
     * @return - true if subscribed to any provided SKUs, false otherwise
     */
    public boolean isSubscribedTo(ArrayList<String> subscriptionSkus) {
        if (bp == null || subscriptionSkus == null) {
            return false; // probably unnecessary but lets just be safe
        } else {
            for (String sku : subscriptionSkus) {
                if (bp.isSubscribed(sku)) {
                    return true;
                }
            }
            return false;
        }
    }

    private void checkAndAcknowledgeTransactionDetails() {
        if (isStoreLoaded()) {
            for (String sku : subscriptionSkus) {
                Purchase transaction = bp.getSubscriptionTransactionDetails(sku);
                if (transaction != null && !transaction.isAcknowledged()) {
                    bp.acknowledgeSubscription(transaction.getSku());
                }
            }
            for (String sku : consumableSkus) {
                Purchase transaction = bp.getPurchaseTransactionDetails(sku);
                if (transaction != null && !transaction.isAcknowledged()) {
                    bp.acknowledgeManagedProduct(transaction.getSku());
                }
            }
        }
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

    public void removeListener() {
        listener = null;
    }

    public void setEventListener(StoreEventListener l) {
        listener = l;
    }
}
