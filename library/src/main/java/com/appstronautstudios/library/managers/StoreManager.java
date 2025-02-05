package com.appstronautstudios.library.managers;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import com.appstronautstudios.library.utils.StoreEventListener;
import com.appstronautstudios.library.utils.SuccessFailListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StoreManager {

    private static final StoreManager INSTANCE = new StoreManager();

    private boolean debuggable;
    private String licenseKey;
    private ArrayList<String> subscriptionSkus = new ArrayList<>();
    private ArrayList<String> inAppSkus = new ArrayList<>();
    private final Map<String, Purchase> purchaseCache = new HashMap<>(); // MAP OF PURCHASE STATES
    private ArrayList<StoreEventListener> listeners = new ArrayList<>();

    private PurchasesUpdatedListener purchasesUpdatedListener;
    private BillingClient billingClient;

    private StoreManager() {
        if (INSTANCE != null) {
            throw new IllegalStateException("Already instantiated");
        }
    }

    public static StoreManager getInstance() {
        return INSTANCE;
    }

    public boolean isStoreLoaded() {
        return billingClient != null && billingClient.isReady();
    }

    public void setDebuggable(boolean debuggable) {
        this.debuggable = debuggable;
    }

    public void setLicenseKey(String licenseKey) {
        this.licenseKey = licenseKey;
    }

    // TODO how are we going to manage SKUs
    public void setManagedSkus(List<String> subscriptionSkus, List<String> consumableSkus) {
        if (subscriptionSkus != null) {
            this.subscriptionSkus.clear();
            this.subscriptionSkus.addAll(subscriptionSkus);
        } else {
            this.subscriptionSkus = new ArrayList<>();
        }

        if (consumableSkus != null) {
            this.inAppSkus.clear();
            this.inAppSkus.addAll(consumableSkus);
        } else {
            this.inAppSkus = new ArrayList<>();
        }
    }

    public void addEventListener(StoreEventListener l) {
        listeners.add(l);
    }

    public void removeEventListener(StoreEventListener l) {
        listeners.remove(l);
    }

    /**
     * utility function to force callback on main thread
     */
    private void storeBillingInitializedMain(boolean success, BillingResult result) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!listeners.isEmpty()) {
                for (StoreEventListener l : listeners) {
                    l.storeBillingInitialized(success, result.getDebugMessage());
                }
            }
        });
    }

    /**
     * utility function to force callback on main thread
     */
    private void storePurchaseCompleteMain(String json) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!listeners.isEmpty()) {
                for (StoreEventListener l : listeners) {
                    l.storePurchaseComplete(json);
                }
            }
        });
    }

    /**
     * utility function to force callback on main thread
     */
    private void storePurchaseErrorMain(int code) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (!listeners.isEmpty()) {
                    for (StoreEventListener l : listeners) {
                        l.storePurchaseError(code);
                    }
                }
            }
        });
    }

    public void setupBillingProcessor(final Context context, ArrayList<String> subs, ArrayList<String> inApps) {
        // initialize listener
        purchasesUpdatedListener = (billingResult, purchases) -> {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
                for (Purchase purchase : purchases) {
                    handlePurchase(purchase); // Process purchase
                }
            } else if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED) {
                storeBillingInitializedMain(false, billingResult);
            } else {
                storeBillingInitializedMain(false, billingResult);
            }
        };
        // store the sub and inApp ids
        subscriptionSkus = subs;
        inAppSkus = inApps;
        // initialize client and start connection
        if (billingClient == null) {
            billingClient = BillingClient.newBuilder(context).setListener(purchasesUpdatedListener).build();
        }
        // TODO should we only start connection if one isn't already started
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    // The BillingClient is ready. You can query purchases here.
                    handleBillingInitialize();
                } else {
                    // TODO retry mechanic
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                // Try to restart the connection on the next request to
                // Google Play by calling the startConnection() method.

                // TODO retry to establish disconnected
            }
        });
    }

    private void handleBillingInitialize() {
        if (isStoreLoaded()) {
            updatePurchaseCache(new SuccessFailListener() {
                @Override
                public void success(Object object) {
                    storeBillingInitializedMain(true, null);
                }

                @Override
                public void failure(Object object) {
                    storeBillingInitializedMain(false, null);
                }
            });
        }
    }

    public void purchase(Activity activity, String productId, boolean isSubscription) {
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(List.of(QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(isSubscription ? BillingClient.ProductType.SUBS : BillingClient.ProductType.INAPP)
                        .build()))
                .build();

        billingClient.queryProductDetailsAsync(params, (billingResult, productDetailsList) -> {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && productDetailsList != null && !productDetailsList.isEmpty()) {
                BillingFlowParams flowParams = BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(List.of(BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(productDetailsList.get(0))
                                .setOfferToken(isSubscription ? productDetailsList.get(0).getSubscriptionOfferDetails().get(0).getOfferToken() : null)
                                .build()))
                        .build();
                billingClient.launchBillingFlow(activity, flowParams);
            } else {
                // TODO what do we do here?
            }
        });
    }

    private void handlePurchase(Purchase purchase) {
        if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
            // Add purchase to cache
            for (String productId : purchase.getProducts()) {
                purchaseCache.put(productId, purchase);
            }

            if (!purchase.isAcknowledged()) {
                AcknowledgePurchaseParams acknowledgeParams = AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.getPurchaseToken())
                        .build();

                billingClient.acknowledgePurchase(acknowledgeParams, billingResult -> {
                    if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        storePurchaseCompleteMain(null);
                    } else {
                        storePurchaseErrorMain(billingResult.getResponseCode());
                    }
                });
            }
        }
    }

    private void acknowledgePurchase(Purchase purchase, SuccessFailListener listener) {
        if (!purchase.isAcknowledged() && purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
            AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.getPurchaseToken())
                    .build();
            billingClient.acknowledgePurchase(params, billingResult -> {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    if (listener != null) listener.success(null); // Success callback
                } else {
                    if (listener != null) listener.failure(billingResult); // Failure callback
                }
            });
        }
    }

    private void updatePurchaseCache(SuccessFailListener listener) {
        queryProductStates(BillingClient.ProductType.INAPP, new SuccessFailListener() {
            @Override
            public void success(Object object) {
                queryProductStates(BillingClient.ProductType.SUBS, new SuccessFailListener() {
                    @Override
                    public void success(Object object) {
                        if (listener != null) listener.success(object);
                    }

                    @Override
                    public void failure(Object object) {
                        if (listener != null) listener.failure(object);
                    }
                });
            }

            @Override
            public void failure(Object object) {
                if (listener != null) listener.failure(object);
            }
        });
    }

    /**
     * Query product type and update cache as needed
     *
     * @param skuType  - BillingClient.ProductType to query
     * @param listener - callback listener
     */
    private void queryProductStates(String skuType, SuccessFailListener listener) {
        QueryPurchasesParams params = QueryPurchasesParams.newBuilder()
                .setProductType(skuType)
                .build();

        billingClient.queryPurchasesAsync(params, (billingResult, purchases) -> {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                for (Purchase purchase : purchases) {
                    for (String productId : purchase.getProducts()) {
                        if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                            purchaseCache.put(productId, purchase);
                        } else {
                            purchaseCache.remove(productId);
                        }
                    }
                }

                if (listener != null) listener.success(purchaseCache);
            } else {
                if (listener != null) listener.failure(billingResult);
            }
        });
    }

    public void consumePurchase(Purchase purchase, SuccessFailListener listener) {
        ConsumeParams consumeParams = ConsumeParams.newBuilder()
                .setPurchaseToken(purchase.getPurchaseToken())
                .build();

        billingClient.consumeAsync(consumeParams, (billingResult, purchaseToken) -> {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                if (listener != null) listener.success(null);
            } else {
                if (listener != null) listener.failure(billingResult);
            }
        });
    }

    public void getSubDetails(SuccessFailListener listener) {
        getProductDetails(subscriptionSkus, BillingClient.ProductType.SUBS, listener);
    }

    public void getInAppDetails(SuccessFailListener listener) {
        getProductDetails(inAppSkus, BillingClient.ProductType.INAPP, listener);
    }

    private void getProductDetails(ArrayList<String> productIds, String productType, SuccessFailListener listener) {
        ArrayList<QueryProductDetailsParams.Product> products = new ArrayList<>();
        for (String productId : productIds) {
            products.add(QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(productId)
                    .setProductType(productType)
                    .build());
        }

        QueryProductDetailsParams queryProductDetailsParams =
                QueryProductDetailsParams.newBuilder()
                        .setProductList(products)
                        .build();

        billingClient.queryProductDetailsAsync(
                queryProductDetailsParams,
                (billingResult, productDetailsList) -> {
                    if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        if (listener != null) listener.success(productDetailsList);
                    } else {
                        if (listener != null) listener.failure(billingResult);
                    }
                }
        );
    }

    /**
     * @return - true if subscribed to any of the managed subscription SKUs or if owns any of the
     * managed consumable SKUs. False otherwise
     */
    public boolean hasAnySubOrConsumable() {
        return hasAnySubOrConsumable(subscriptionSkus, inAppSkus);
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
        return hasAnyConsumable(inAppSkus);
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
                    if (purchaseCache.containsKey(sku)) {
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
     * @return - true if subscribed to any managed SKUs, false otherwise
     */
    public boolean isSubscribedToAny() {
        return isSubscribedToAny(subscriptionSkus);
    }

    /**
     * @return - true if subscribed to any provided SKUs, false otherwise
     */
    public boolean isSubscribedToAny(@NonNull List<String> skus) {
        if (isStoreLoaded()) {
            if (debuggable) {
                return true;
            } else {
                for (String sku : skus) {
                    if (purchaseCache.containsKey(sku)) {
                        return true;
                    }
                }
                return false;
            }
        } else {
            return false;
        }
    }
}
