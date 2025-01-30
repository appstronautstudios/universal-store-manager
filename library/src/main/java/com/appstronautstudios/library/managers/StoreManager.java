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
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
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
    private final Map<String, Purchase> purchaseCache = new HashMap<>();
    private List<ProductDetails> subscriptionProducts;
    private List<ProductDetails> inAppProducts;
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
    private void storeBillingInitializedMain(boolean success, Object result) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!listeners.isEmpty()) {
                for (StoreEventListener l : listeners) {
                    l.storeBillingInitialized(success, result);
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

                if (listeners.size() > 0) {
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
            // To be implemented in a later section.
        };
        // store the sub and inApp ids
        subscriptionSkus = subs;
        inAppSkus = inApps;
        // initialize client and start connection
        billingClient = BillingClient.newBuilder(context)
                .setListener(purchasesUpdatedListener)
                .build();
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


//        if (BillingProcessor.isIabServiceAvailable(context)) {
//            if (isStoreLoaded()) {
//                // DANGER created already! If you dump the old one the callbacks attached to it are
//                // at risk. Manually call initialize like it succeeded again.
//                handleBillingInitialize();
//            } else {
//                bp = new BillingProcessor(context, licenseKey, new BillingProcessor.IBillingHandler() { // this does initialize on its own
//                    @Override
//                    public void onBillingError(int responseCode, Throwable error) {
//                        switch (responseCode) {
//                            case 1:
//                                // User pressed back or canceled a dialog
//                                break;
//                            case 2:
//                                // Network connection is down
//                                break;
//                            case 3:
//                                bp = null;
//                                // Billing API version is not supported for the type requested
//                                break;
//                            case 4:
//                                // Requested product is not available for purchase
//                                break;
//                            case 5:
//                                // Invalid arguments provided to the API. This error can also indicate that
//                                // the application was not correctly signed or properly set up for In-app Billing
//                                // in Google Play, or does not have the necessary permissions in its manifest
//                                break;
//                            case 6:
//                                bp = null;
//                                // Fatal error during the API action
//                                break;
//                            case 7:
//                                // Failure to purchase since item is already owned
//                                break;
//                            case 8:
//                                // Failure to consume since item is not owned
//                                break;
//                            default:
//                                break;
//                        }
//
//                        // alert user to store error
//                        storePurchaseErrorMain(responseCode);
//                    }
//
//                    @Override
//                    public void onBillingInitialized() {
//                        handleBillingInitialize();
//                    }
//
//                    @Override
//                    public void onProductPurchased(@NonNull String productId, TransactionDetails details) {
//                        handlePurchaseResult(productId);
//                    }
//
//                    // TODO
//                    /*
//                    @Override
//                    public void onQuerySkuDetails(List<SkuDetails> skuDetails) {
//                        storeBillingInitializedMain(true, "");
//                    }
//                     */
//
//                    @Override
//                    public void onPurchaseHistoryRestored() {
//                        handleBillingInitialize();
//                    }
//                });
//            }
//        } else {
//            // alert user to failed init
//            storeBillingInitializedMain(false, "In app billing not supported on this device. Make sure you have Google Play Service installed and are signed into the Play Store.");
//        }
    }

    private void handleBillingInitialize() {
        if (isStoreLoaded()) {
            queryInApp(inAppSkus, new SuccessFailListener() {
                @Override
                public void success(Object object) {
                    querySubs(subscriptionSkus, new SuccessFailListener() {
                        @Override
                        public void success(Object object) {
                            // alert user to completed init
                            storeBillingInitializedMain(true, object);
                        }

                        @Override
                        public void failure(Object object) {
                            storeBillingInitializedMain(false, object);
                        }
                    });
                }

                @Override
                public void failure(Object object) {
                    storeBillingInitializedMain(false, object);
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
            }
        });
    }

    private void onPurchasesUpdated(BillingResult billingResult, List<Purchase> purchases) {
        if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (Purchase purchase : purchases) {
                purchaseCache.put(purchase.getProducts().get(0), purchase);
                acknowledgePurchase(purchase);
                storePurchaseCompleteMain(null);
            }
        } else if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            storePurchaseErrorMain(billingResult.getResponseCode());
        }
    }

    private void acknowledgePurchase(Purchase purchase) {
        if (!purchase.isAcknowledged() && purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
            AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.getPurchaseToken())
                    .build();
            billingClient.acknowledgePurchase(params, billingResult -> {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    // Successfully acknowledged purchase
                }
            });
        }
    }

    // TODO put this back we need it for testing
    public void consumePurchase(String SKU) {
        if (isStoreLoaded()) {
            bp.consumePurchase(SKU);
        }
    }

    private void queryInApp(List<String> productIds, SuccessFailListener listener) {
        ArrayList<QueryProductDetailsParams.Product> products = new ArrayList<>();
        for (String productId : productIds) {
            products.add(QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(productId)
                    .setProductType(BillingClient.ProductType.INAPP)
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
                        // TODO update cache with product return
                        inAppProducts = productDetailsList;
                        if (listener != null) listener.success(productDetailsList);
                    } else {
                        // TODO what should we do if products fail to fetch?
                        if (listener != null) listener.failure(billingResult);
                    }
                }
        );
    }

    private void querySubs(List<String> productIds, SuccessFailListener listener) {
        ArrayList<QueryProductDetailsParams.Product> products = new ArrayList<>();
        for (String productId : productIds) {
            products.add(QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(productId)
                    .setProductType(BillingClient.ProductType.SUBS)
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
                        // TODO update cache with product return
                        subscriptionProducts = productDetailsList;
                        if (listener != null) listener.success(productDetailsList);
                    } else {
                        // TODO what should we do if products fail to fetch?
                        if (listener != null) listener.failure(billingResult);
                    }
                }
        );
    }

    /*
    public void getAllManagedSkuDetailsAsync(final SuccessFailListener successFailListener) {
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
                                    if (successFailListener != null) {
                                        successFailListener.success(skuDetails);
                                    }
                                }
                            });
                        }

                        @Override
                        public void fail(final Object object) {
                            new Handler(Looper.getMainLooper()).post(new Runnable() {
                                @Override
                                public void run() {
                                    if (successFailListener != null) {
                                        successFailListener.fail(object);
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
                            if (successFailListener != null) {
                                successFailListener.fail(object);
                            }
                        }
                    });
                }
            });
        }
    }
     */

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
     * @return - true if subscribed to any provided SKUs, false otherwise
     */
    public boolean isSubscribedToAny() {
        if (isStoreLoaded()) {
            if (debuggable) {
                return true;
            } else {
               for (ProductDetails details : subscriptionProducts) {
                   details.
               }
            }
        } else {
            return false;
        }
    }
}
