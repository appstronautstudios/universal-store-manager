package com.appstronautstudios.universalstoremanager.managers;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.core.util.Consumer;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

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
import com.google.gson.Gson;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.GeneralSecurityException;
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

    // listeners that get called when store lifecycle points are hit
    private ArrayList<StoreEventListener> storeEventListeners = new ArrayList<>();
    // listeners that get called when setup completes
    private final List<SuccessFailListener> setupListeners = new ArrayList<>();

    private PurchasesUpdatedListener purchasesUpdatedListener;
    private BillingClient billingClient;

    // caches
    private Map<String, Purchase> storeMemoryCache = new HashMap<>();
    private SharedPreferences storeDiskCache;

    private StoreManager() {
        if (INSTANCE != null) {
            throw new IllegalStateException("Already instantiated");
        }
    }

    public static StoreManager getInstance() {
        return INSTANCE;
    }

    public void setDebuggable(boolean debuggable) {
        this.debuggable = debuggable;
    }

    private void setManagedSkus(List<String> subscriptionSkus, List<String> consumableSkus) {
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
        if (!storeEventListeners.contains(l)) {
            storeEventListeners.add(l);
        }
    }

    public void removeEventListener(StoreEventListener l) {
        storeEventListeners.remove(l);
    }

    private void listenerSuccessOnMain(SuccessFailListener listener, Object object) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (listener != null) listener.success(object);
        });
    }

    private void listenerFailureOnMain(SuccessFailListener listener, Object object) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (listener != null) listener.failure(object);
        });
    }

    /**
     * Generic helper to post listener callbacks safely on the main thread.
     */
    private void notifyListeners(Consumer<StoreEventListener> action) {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (StoreEventListener listener : storeEventListeners) {
                action.accept(listener);
            }
        });
    }

    public void setupBillingProcessor(final Context context, ArrayList<String> subs, ArrayList<String> inApps) {
        setupBillingProcessor(context, subs, inApps, null);
    }

    public void setupBillingProcessor(final Context context, ArrayList<String> subs, ArrayList<String> inApps, SuccessFailListener listener) {
        // initialize listener
        purchasesUpdatedListener = (billingResult, purchases) -> {
            if (purchases != null) {
                for (Purchase purchase : purchases) {
                    handlePurchase(purchase, billingResult.getResponseCode()); // Process purchase
                }
            }
        };

        // store the sub and inApp ids
        setManagedSkus(subs, inApps);

        // initialize client and start connection
        if (billingClient == null) {
            // initialize encrypted disk cache and then load our purchases from it. This is only
            // meant to be a redundancy against play store issues and startup timing. These loaded
            // purchases should always be immediately overwritten by billing connection and fetch
            initDiskCache(context);
            loadPurchasesFromDiskCache();

            PendingPurchasesParams params = PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build();
            billingClient = BillingClient.newBuilder(context)
                    .enablePendingPurchases(params)
                    .enableAutoServiceReconnection()
                    .setListener(purchasesUpdatedListener)
                    .build();
        }

        // make sure that we're connected
        connectBillingClient(listener);
    }

    private void connectBillingClient(SuccessFailListener listener) {
        if (listener != null) {
            setupListeners.add(listener);
        }

        int connectedState = billingClient.getConnectionState();
        if (connectedState == BillingClient.ConnectionState.CONNECTED) {
            // already connected. Update cache and notify setupListeners
            updateMemoryCacheFromPlayAndNotify();
        } else if (connectedState == BillingClient.ConnectionState.CONNECTING) {
            // connection in progress; listener was added to setupListeners and will be flushed on completion
        } else {
            // start initial connection
            billingClient.startConnection(new BillingClientStateListener() {
                @Override
                public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
                    if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        // The BillingClient is ready. Update purchase cache and notify setupListeners
                        updateMemoryCacheFromPlayAndNotify();
                    } else {
                        // failure code. Inform listeners and clear list
                        notifySetupListenersFailure(billingResult.getResponseCode());
                    }
                }

                @Override
                public void onBillingServiceDisconnected() {
                    // Left intentionally empty because enableAutoServiceReconnection() handles reconnections
                }
            });
        }
    }

    private void notifySetupListenersSuccess(Object successObject) {
        for (SuccessFailListener l : setupListeners) {
            listenerSuccessOnMain(l, successObject);
        }
        setupListeners.clear();
    }

    private void notifySetupListenersFailure(Object failureObject) {
        for (SuccessFailListener l : setupListeners) {
            listenerFailureOnMain(l, failureObject);
        }
        setupListeners.clear();
    }

    public void purchase(Activity activity, String productId, boolean isSubscription) {
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(List.of(QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(isSubscription ? BillingClient.ProductType.SUBS : BillingClient.ProductType.INAPP)
                        .build()))
                .build();

        billingClient.queryProductDetailsAsync(params, (billingResult, productDetailsResult) -> {
            List<ProductDetails> detailsList = productDetailsResult.getProductDetailsList();
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && !detailsList.isEmpty()) {
                BillingFlowParams.ProductDetailsParams.Builder productDetailsParamsBuilder =
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(detailsList.get(0));

                // Ensure SubscriptionOfferDetails is not null before accessing it
                if (isSubscription) {
                    List<ProductDetails.SubscriptionOfferDetails> offerDetails = detailsList.get(0).getSubscriptionOfferDetails();
                    if (offerDetails != null && !offerDetails.isEmpty()) {
                        productDetailsParamsBuilder.setOfferToken(offerDetails.get(0).getOfferToken());
                    }
                }

                BillingFlowParams flowParams = BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(List.of(productDetailsParamsBuilder.build()))
                        .build();

                billingClient.launchBillingFlow(activity, flowParams);
            } else {
                notifyListeners(listener -> listener.storePurchaseError(billingResult.getResponseCode()));
            }
        });
    }

    private void handlePurchase(Purchase purchase, int responseCode) {
        if (responseCode == BillingClient.BillingResponseCode.OK) {
            if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                // purchase is like a "receipt" and here we add all products on that receipt to
                // our mem cache. Theoretically only a problem if we have consumables which we don't
                for (String productId : purchase.getProducts()) {
                    addToMemoryCache(productId, purchase, true);
                }

                acknowledgePurchase(purchase, new SuccessFailListener() {
                    @Override
                    public void success(Object object) {
                        notifyListeners(listener -> listener.storePurchaseComplete(purchase));
                    }

                    @Override
                    public void failure(Object object) {
                        if (object instanceof BillingResult) {
                            notifyListeners(listener -> listener.storePurchaseError(((BillingResult) object).getResponseCode()));
                        } else {
                            notifyListeners(listener -> listener.storePurchaseError(PURCHASE_FAIL_UNKNOWN));
                        }
                    }
                });
            } else if (purchase.getPurchaseState() == Purchase.PurchaseState.PENDING) {
                notifyListeners(listener -> listener.storePurchasePending(purchase));
            }
        } else if (responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                // purchase is like a "receipt" and here we add all products on that receipt to
                // our mem cache. Theoretically only a problem if we have consumables which we don't
                for (String productId : purchase.getProducts()) {
                    addToMemoryCache(productId, purchase, true);
                }
                notifyListeners(listener -> listener.storePurchaseComplete(purchase));
            }
        } else {
            // https://developer.android.com/reference/com/android/billingclient/api/BillingClient.BillingResponseCode
            // as of 9.1.0 there are several new errors types, but we are currently still treating
            // them all as generic failures
            // PAYMENT_DECLINED_DUE_TO_INSUFFICIENT_FUNDS
            // USER_INELIGIBLE
            // NO_APPLICABLE_SUB_RESPONSE_CODE
            notifyListeners(listener -> listener.storePurchaseError(responseCode));
        }
    }

    private void acknowledgePurchase(Purchase purchase, SuccessFailListener listener) {
        if (!purchase.isAcknowledged() && purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
            AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.getPurchaseToken())
                    .build();
            billingClient.acknowledgePurchase(params, billingResult -> {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    listenerSuccessOnMain(listener, null);
                } else {
                    listenerFailureOnMain(listener, billingResult.getResponseCode());
                }
            });
        }
    }

    /**
     * Query product type and update cache as needed
     *
     * @param skuType  - BillingClient.ProductType to query
     * @param listener - callback listener. Failure return response code
     */
    private void getPurchases(String skuType, SuccessFailListener listener) {
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
                listenerSuccessOnMain(listener, updatedCache);
            } else {
                listenerFailureOnMain(listener, billingResult.getResponseCode());
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
        Purchase purchase = storeMemoryCache.get(sku); // Retrieve from cache

        if (purchase == null) {
            // Purchase not in cache. Cannot consume without it.
            listenerFailureOnMain(listener, BillingClient.BillingResponseCode.ITEM_NOT_OWNED);
            return; // Exit early
        }

        // Create consumption parameters using the cached purchase token
        ConsumeParams consumeParams = ConsumeParams.newBuilder()
                .setPurchaseToken(purchase.getPurchaseToken())
                .build();

        billingClient.consumeAsync(consumeParams, (billingResult, purchaseToken) -> {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                // Remove the purchase from cache since it's now consumed
                removeFromMemoryCache(sku, true);
                listenerSuccessOnMain(listener, purchaseToken);
            } else {
                listenerFailureOnMain(listener, billingResult.getResponseCode());
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
                        listenerSuccessOnMain(listener, allProductDetails);
                    }

                    @Override
                    public void failure(Object object) {
                        if (object instanceof Integer) {
                            listenerFailureOnMain(listener, object);
                        } else {
                            listenerFailureOnMain(listener, DETAIL_FAIL_UNKNOWN);
                        }
                    }
                });
            }

            @Override
            public void failure(Object object) {
                listenerFailureOnMain(listener, object);
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

        billingClient.queryProductDetailsAsync(queryProductDetailsParams, (billingResult, productDetailsResult) -> {
            List<ProductDetails> detailsList = productDetailsResult.getProductDetailsList();
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                try {
                    ArrayList<UniversalProductDetails> details = new ArrayList<>();
                    for (ProductDetails productDetails : detailsList) {
                        details.add(UniversalProductDetails.fromProductDetails(productDetails));
                    }
                    listenerSuccessOnMain(listener, details);
                } catch (Exception e) {
                    listenerFailureOnMain(listener, PARSING_FAIL_UNKNOWN);
                }
            } else {
                listenerFailureOnMain(listener, billingResult.getResponseCode());
            }
        });
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
        if (debuggable) {
            return true;
        } else {
            for (String sku : consumableSkus) {
                if (storeMemoryCache.containsKey(sku)) {
                    return true;
                }
            }
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
        if (debuggable) {
            return true;
        } else {
            for (String sku : skus) {
                if (storeMemoryCache.containsKey(sku)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * @return true if billing is connected to play, false otherwise
     */
    public boolean isReady() {
        return billingClient != null && billingClient.isReady();
    }

    // MEMORY CACHE UTILS
    private void addToMemoryCache(Purchase p, boolean alsoDiskCache) {
        if (p != null && p.getProducts() != null) {
            for (String productId : p.getProducts()) {
                addToMemoryCache(productId, p, alsoDiskCache);
            }
        }
    }

    private void addToMemoryCache(String productId, Purchase purchase, boolean alsoDiskCache) {
        storeMemoryCache.put(productId, purchase);
        if (alsoDiskCache) {
            savePurchasesToDiskCache();
        }
    }

    private void removeFromMemoryCache(String productId, boolean alsoDiskCache) {
        storeMemoryCache.remove(productId);
        if (alsoDiskCache) {
            savePurchasesToDiskCache();
        }
    }

    private void updateMemoryCacheFromPlayAndNotify() {
        updateMemoryCacheFromPlay(new SuccessFailListener() {
            @Override
            public void success(Object object) {
                // purchase cache successfully update. Inform setupBilling listeners and then de-reg
                notifySetupListenersSuccess(object);
            }

            @Override
            public void failure(Object object) {
                // purchase cache update failure. Inform setupBilling listeners and then de-reg
                notifySetupListenersFailure(object);
            }
        });
    }

    private void updateMemoryCacheFromPlay(SuccessFailListener listener) {
        getPurchases(BillingClient.ProductType.INAPP, new SuccessFailListener() {
            @Override
            public void success(Object object1) {
                getPurchases(BillingClient.ProductType.SUBS, new SuccessFailListener() {
                    @Override
                    public void success(Object object2) {
                        Map<String, Purchase> updatedPurchases = new HashMap<>();
                        updatedPurchases.putAll((Map<String, Purchase>) object1);
                        updatedPurchases.putAll((Map<String, Purchase>) object2);
                        // update memory cache and prefs cache
                        storeMemoryCache = updatedPurchases;
                        savePurchasesToDiskCache();
                        // inform callback
                        listenerSuccessOnMain(listener, updatedPurchases);
                    }

                    @Override
                    public void failure(Object object) {
                        listenerFailureOnMain(listener, object);
                    }
                });
            }

            @Override
            public void failure(Object object) {
                listenerFailureOnMain(listener, object);
            }
        });
    }

    // DISK CACHE UTILS
    private void initDiskCache(Context context) {
        if (storeDiskCache == null) {
            try {
                MasterKey masterKey = new MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();

                storeDiskCache = EncryptedSharedPreferences.create(
                        context,
                        "encr",
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
            } catch (GeneralSecurityException | IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * load purchases currently saved in disk cache and overwrite the memory cache with them
     */
    private void loadPurchasesFromDiskCache() {
        if (storeDiskCache == null) return;

        String jsonString = storeDiskCache.getString("purchases", "[]");

        try {
            JSONArray jsonArray = new JSONArray(jsonString);
            for (int i = 0; i < jsonArray.length(); i++) {
                try {
                    // TRY NEW FORMAT FIRST
                    JSONObject obj = jsonArray.getJSONObject(i);
                    String originalJson = obj.getString("originalJson");
                    String signature = obj.getString("signature");

                    Purchase p = new Purchase(originalJson, signature);
                    addToMemoryCache(p, false);
                } catch (JSONException e) {
                    // FALLBACK TO LEGACY GSON FORMAT
                    try {
                        Gson gson = new Gson();
                        String rawJsonItem = jsonArray.getString(i);
                        Purchase legacyPurchase = gson.fromJson(rawJsonItem, Purchase.class);

                        if (legacyPurchase != null && legacyPurchase.getOriginalJson() != null) {
                            // Reconstruct properly using valid native API
                            Purchase p = new Purchase(legacyPurchase.getOriginalJson(), legacyPurchase.getSignature());
                            addToMemoryCache(p, false);
                        }
                    } catch (Exception legacyEx) {
                        legacyEx.printStackTrace();
                        // Skip unparseable legacy entry
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * overwrite disk cache with purchases currently stored in the memory cache
     */
    private void savePurchasesToDiskCache() {
        if (storeDiskCache == null) return;

        JSONArray jsonArray = new JSONArray();

        // Deduplicate purchases by token/object before saving
        for (Purchase purchase : storeMemoryCache.values()) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("originalJson", purchase.getOriginalJson());
                obj.put("signature", purchase.getSignature());
                jsonArray.put(obj);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        storeDiskCache.edit().putString("purchases", jsonArray.toString()).apply();
    }
}