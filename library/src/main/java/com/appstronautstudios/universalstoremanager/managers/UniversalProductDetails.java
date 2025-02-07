package com.appstronautstudios.universalstoremanager.managers;

import com.android.billingclient.api.ProductDetails;

import java.util.List;

public class UniversalProductDetails {

    private final String productId;
    private final String title;
    private final String description;
    private final String price;
    private final String priceCurrencyCode;
    private final boolean isSubscription;

    public UniversalProductDetails(String productId, String title, String description, String price, String priceCurrencyCode, boolean isSubscription) {
        this.productId = productId;
        this.title = title;
        this.description = description;
        this.price = price;
        this.priceCurrencyCode = priceCurrencyCode;
        this.isSubscription = isSubscription;
    }

    public static UniversalProductDetails fromProductDetails(ProductDetails details) {
        if (details == null) {
            return new UniversalProductDetails("N/A", "N/A", "N/A", "N/A", "N/A", false);
        }

        boolean isSubscription = details.getSubscriptionOfferDetails() != null;

        if (isSubscription) {
            // Handle Subscription Pricing
            List<ProductDetails.SubscriptionOfferDetails> offerDetails = details.getSubscriptionOfferDetails();
            if (offerDetails.isEmpty()) {
                return new UniversalProductDetails(details.getProductId(), details.getTitle(), details.getDescription(), "N/A", "N/A", true);
            }

            ProductDetails.PricingPhase pricingPhase = offerDetails.get(0).getPricingPhases().getPricingPhaseList().get(0);

            return new UniversalProductDetails(
                    details.getProductId(),
                    details.getTitle(),
                    details.getDescription(),
                    pricingPhase.getFormattedPrice(),
                    pricingPhase.getPriceCurrencyCode(),
                    true
            );
        } else {
            // Handle In-App Product Pricing
            ProductDetails.OneTimePurchaseOfferDetails oneTimePurchaseDetails = details.getOneTimePurchaseOfferDetails();
            if (oneTimePurchaseDetails == null) {
                return new UniversalProductDetails(details.getProductId(), details.getTitle(), details.getDescription(), "N/A", "N/A", false);
            }

            return new UniversalProductDetails(
                    details.getProductId(),
                    details.getTitle(),
                    details.getDescription(),
                    oneTimePurchaseDetails.getFormattedPrice(),
                    oneTimePurchaseDetails.getPriceCurrencyCode(),
                    false
            );
        }
    }

    public String getProductId() {
        return productId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getPrice() {
        return price;
    }

    public String getPriceCurrencyCode() {
        return priceCurrencyCode;
    }

    public boolean isSubscription() {
        return isSubscription;
    }
}
