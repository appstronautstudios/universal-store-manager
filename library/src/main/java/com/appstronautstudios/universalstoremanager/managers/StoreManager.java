package com.appstronautstudios.universalstoremanager.managers;

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
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import com.appstronautstudios.universalstoremanager.utils.StoreEventListener;
import com.appstronautstudios.universalstoremanager.utils.SuccessFailListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StoreManager {

    public static final int INIT_FAIL_UNKNOWN = -99;
    public static final int PURCHASE_FAIL_UNKNOWN = -199;
    public static final int DETAIL_FAIL_UNKNOWN = -299;
    public static final int PARSING_FAIL_UNKNOWN = -399;

    private static final StoreManager INSTANCE = new StoreManager();

    private boolean debuggable;
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
        if (!listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeEventListener(StoreEventListener l) {
        listeners.remove(l);
    }

    /**
     * utility function to force callback on main thread
     */
    private void storeBillingInitializedMain(boolean success, int code) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!listeners.isEmpty()) {
                for (StoreEventListener l : listeners) {
                    l.storeBillingInitialized(success, code);
                }
            }
        });
    }

    /**
     * utility function to force callback on main thread
     */
    private void storePurchaseCompleteMain(String sku) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!listeners.isEmpty()) {
                for (StoreEventListener l : listeners) {
                    l.storePurchaseComplete(sku);
                }
            }
        });
    }

    /**
     * utility function to force callback on main thread
     */
    private void storePurchasePendingMain(String sku) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!listeners.isEmpty()) {
                for (StoreEventListener l : listeners) {
                    l.storePurchasePending(sku);
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
            if (purchases != null) {
                for (Purchase purchase : purchases) {
                    handlePurchase(purchase, billingResult.getResponseCode()); // Process purchase
                }
            }
        };
        // store the sub and inApp ids
        subscriptionSkus = subs;
        inAppSkus = inApps;

        // initialize client and start connection
        if (billingClient == null) {
            PendingPurchasesParams params = PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build();
            billingClient = BillingClient.newBuilder(context)
                    .enablePendingPurchases(params)
                    .setListener(purchasesUpdatedListener)
                    .build();
        }

        // make sure that we're connected (this will internally check)
        connectBillingClient(2);
    }

    private void connectBillingClient(int retryCounter) {
        int connectedState = billingClient.getConnectionState();
        if (connectedState == BillingClient.ConnectionState.CONNECTED) {
            // already connected. Update cache and inform user
            handleBillingInitialize();
        } else if (connectedState == BillingClient.ConnectionState.CONNECTING) {
            // respect that whatever process starting the connection process will eventually
            // complete and launch the necessary callback. Do nothing
        } else {
            // connection closed or disconnected. Restart
            billingClient.startConnection(new BillingClientStateListener() {
                @Override
                public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
                    if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        // The BillingClient is ready. You can query purchases here.
                        handleBillingInitialize();
                    } else {
                        if (retryCounter > 0) {
                            connectBillingClient(retryCounter - 1);
                        } else {
                            storeBillingInitializedMain(false, billingResult.getResponseCode());
                        }
                    }
                }

                @Override
                public void onBillingServiceDisconnected() {
                    // Try to restart the connection on the next request to
                    // Google Play by calling the startConnection() method.
                    connectBillingClient(0);
                }
            });
        }
    }

    private void handleBillingInitialize() {
        if (isStoreLoaded()) {
            updatePurchaseCache(new SuccessFailListener() {
                @Override
                public void success(Object object) {
                    storeBillingInitializedMain(true, 0);
                }

                @Override
                public void failure(Object object) {
                    if (object instanceof Integer) {
                        storeBillingInitializedMain(false, (int) object);
                    } else {
                        storeBillingInitializedMain(false, INIT_FAIL_UNKNOWN);
                    }
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
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && !productDetailsList.isEmpty()) {
                BillingFlowParams.ProductDetailsParams.Builder productDetailsParamsBuilder =
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(productDetailsList.get(0));

                // Ensure SubscriptionOfferDetails is not null before accessing it
                if (isSubscription) {
                    List<ProductDetails.SubscriptionOfferDetails> offerDetails = productDetailsList.get(0).getSubscriptionOfferDetails();
                    if (offerDetails != null && !offerDetails.isEmpty()) {
                        productDetailsParamsBuilder.setOfferToken(offerDetails.get(0).getOfferToken());
                    }
                }

                BillingFlowParams flowParams = BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(List.of(productDetailsParamsBuilder.build()))
                        .build();

                billingClient.launchBillingFlow(activity, flowParams);
            } else {
                storePurchaseErrorMain(billingResult.getResponseCode());
            }
        });
    }

    private void handlePurchase(Purchase purchase, int responseCode) {
        if (responseCode == BillingClient.BillingResponseCode.OK) {
            if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                // Add purchase to cache
                for (String productId : purchase.getProducts()) {
                    purchaseCache.put(productId, purchase);
                }

                acknowledgePurchase(purchase, new SuccessFailListener() {
                    @Override
                    public void success(Object object) {
                        storePurchaseCompleteMain(null);
                    }

                    @Override
                    public void failure(Object object) {
                        if (object instanceof BillingResult) {
                            storePurchaseErrorMain(((BillingResult) object).getResponseCode());
                        } else {
                            storePurchaseErrorMain(PURCHASE_FAIL_UNKNOWN);
                        }
                    }
                });
            } else if (purchase.getPurchaseState() == Purchase.PurchaseState.PENDING) {
                storePurchasePendingMain(null);
            }
        } else if (responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                // Add purchase to cache
                for (String productId : purchase.getProducts()) {
                    purchaseCache.put(productId, purchase);
                }
                storePurchaseCompleteMain(null);
            }
        } else {
            storePurchaseErrorMain(responseCode);
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

    /**
     * Clear cache and then fetch INAPP and SUBS purchases
     *
     * @param listener - success/fail of cache update operation. Returns int code on failure
     */
    private void updatePurchaseCache(SuccessFailListener listener) {
        purchaseCache.clear(); // Remove old entries
        queryProductStates(BillingClient.ProductType.INAPP, new SuccessFailListener() {
            @Override
            public void success(Object object) {
                queryProductStates(BillingClient.ProductType.SUBS, listener);
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
     * @param listener - callback listener. Failure return response code
     */
    private void queryProductStates(String skuType, SuccessFailListener listener) {
        QueryPurchasesParams params = QueryPurchasesParams.newBuilder()
                .setProductType(skuType)
                .build();

        billingClient.queryPurchasesAsync(params, (billingResult, purchases) -> {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                Map<String, Purchase> updatedCache = new HashMap<>(); // Temporary cache

                for (Purchase purchase : purchases) {
                    for (String productId : purchase.getProducts()) {
                        // Check if the purchase should be kept in cache
                        if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                            updatedCache.put(productId, purchase);
                        }
                    }
                }

                // Add valid purchases
                purchaseCache.putAll(updatedCache);

                if (listener != null) listener.success(purchaseCache);
            } else {
                if (listener != null) listener.failure(billingResult.getResponseCode());
            }
        });
    }

    /**
     * Consume an in-app product. Has to be in our purchase cache already to succeed.
     *
     * @param sku      - sku of purchase to remove
     * @param listener - success fail listener. Will fail if async removal request fails OR if the
     *                 sku doesn't exist in our purchase cache. Failure will return response code.
     *                 Success will return token.
     */
    public void consumePurchase(String sku, SuccessFailListener listener) {
        Purchase purchase = purchaseCache.get(sku); // Retrieve from cache

        if (purchase == null) {
            // Purchase not in cache. Cannot consume without it.
            if (listener != null) {
                listener.failure(BillingClient.BillingResponseCode.ITEM_NOT_OWNED);
            }
            return; // Exit early
        }

        // Create consumption parameters using the cached purchase token
        ConsumeParams consumeParams = ConsumeParams.newBuilder()
                .setPurchaseToken(purchase.getPurchaseToken())
                .build();

        billingClient.consumeAsync(consumeParams, (billingResult, purchaseToken) -> {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                // Remove the purchase from cache since it's now consumed
                purchaseCache.remove(sku);
                if (listener != null) {
                    listener.success(purchaseToken);
                }
            } else {
                if (listener != null) {
                    listener.failure(billingResult.getResponseCode());
                }
            }
        });
    }


    /**
     * Fetch all product details as UniversalProductDetails wrapper class
     *
     * @param listener - success return arraylist of UniversalProductDetails. Failure returns code
     */
    public void getAllProductDetails(SuccessFailListener listener) {
        getProductDetails(subscriptionSkus, BillingClient.ProductType.SUBS, new SuccessFailListener() {
            @Override
            public void success(Object object1) {
                getProductDetails(inAppSkus, BillingClient.ProductType.INAPP, new SuccessFailListener() {
                    @Override
                    public void success(Object object2) {
                        ArrayList<UniversalProductDetails> allProductDetails = new ArrayList<>();
                        if (object1 instanceof List<?> rawList) {
                            if (!rawList.isEmpty() && rawList.get(0) instanceof UniversalProductDetails) {
                                allProductDetails.addAll((List<UniversalProductDetails>) object1);
                            }
                        }
                        if (object2 instanceof List<?> rawList) {
                            if (!rawList.isEmpty() && rawList.get(0) instanceof UniversalProductDetails) {
                                allProductDetails.addAll((List<UniversalProductDetails>) object2);
                            }
                        }
                        new Handler(Looper.getMainLooper()).post(() -> { // don't trust android billing to return back to main thread
                            if (listener != null) listener.success(allProductDetails);
                        });
                    }

                    @Override
                    public void failure(Object object) {
                        if (object instanceof Integer) {
                            new Handler(Looper.getMainLooper()).post(() -> { // don't trust android billing to return back to main thread
                                if (listener != null) listener.failure(object);
                            });
                        } else {
                            new Handler(Looper.getMainLooper()).post(() -> { // don't trust android billing to return back to main thread
                                if (listener != null) listener.failure(DETAIL_FAIL_UNKNOWN);
                            });
                        }
                    }
                });
            }

            @Override
            public void failure(Object object) {
                new Handler(Looper.getMainLooper()).post(() -> { // don't trust android billing to return back to main thread
                    if (listener != null) listener.failure(object);
                });
            }
        });
    }

    /**
     * Fetch sub product details as UniversalProductDetails wrapper class
     *
     * @param listener - success return arraylist of UniversalProductDetails. Failure returns code
     */
    public void getSubDetails(SuccessFailListener listener) {
        getProductDetails(subscriptionSkus, BillingClient.ProductType.SUBS, listener);
    }

    /**
     * Fetch in-app product details as UniversalProductDetails wrapper class
     *
     * @param listener - success return arraylist of UniversalProductDetails. Failure returns code
     */
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
                        try {
                            ArrayList<UniversalProductDetails> details = new ArrayList<>();
                            for (ProductDetails productDetails : productDetailsList) {
                                details.add(UniversalProductDetails.fromProductDetails(productDetails));
                            }
                            if (listener != null) listener.success(details);
                        } catch (Exception e) {
                            if (listener != null) listener.failure(PARSING_FAIL_UNKNOWN);
                        }
                    } else {
                        if (listener != null) listener.failure(billingResult.getResponseCode());
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
